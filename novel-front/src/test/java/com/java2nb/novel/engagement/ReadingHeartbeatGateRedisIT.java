package com.java2nb.novel.engagement;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "redis.it", matches = "true")
class ReadingHeartbeatGateRedisIT {

    private static final LocalDate STAT_DATE = LocalDate.of(2026, 9, 10);
    private static final long BASE_TIME_MILLIS = 1_789_000_000_000L;

    private final Set<String> exactCleanupKeys = ConcurrentHashMap.newKeySet();
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private ReadingEngagementProperties properties;
    private ReadingHeartbeatGate gate;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration("127.0.0.1", 6380);
        redis.setPassword(RedisPassword.of("123456"));
        connectionFactory = new LettuceConnectionFactory(redis);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        properties = new ReadingEngagementProperties();
        properties.setIpHmacSecret("integration-test-secret");
        gate = new ReadingHeartbeatGate(redisTemplate, properties, new SimpleMeterRegistry());
    }

    @AfterEach
    void deleteOnlyKeysCreatedByThisTest() {
        try {
            if (redisTemplate != null && !exactCleanupKeys.isEmpty()) {
                redisTemplate.delete(exactCleanupKeys);
                assertThat(exactCleanupKeys.stream().noneMatch(redisTemplate::hasKey)).isTrue();
            }
        } finally {
            if (connectionFactory != null) {
                connectionFactory.destroy();
            }
        }
    }

    @Test
    void appliesDuplicateAndSessionSlidingWindowTransitions() {
        TestIdentity identity = newIdentity();
        registerPage(identity.pageVisitId(), identity.sessionHash(), 42L, 99L);

        assertThat(gate.evaluate(command(identity, 1L, BASE_TIME_MILLIS)))
            .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
        assertThat(gate.evaluate(command(identity, 1L, BASE_TIME_MILLIS + 1L)))
            .isEqualTo(ReadingHeartbeatOutcome.DUPLICATE);
        for (long sequence = 2; sequence <= 8; sequence++) {
            assertThat(gate.evaluate(command(identity, sequence, BASE_TIME_MILLIS + sequence)))
                .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
        }
        assertThat(gate.evaluate(command(identity, 9L, BASE_TIME_MILLIS + 9L)))
            .isEqualTo(ReadingHeartbeatOutcome.SESSION_RATE_LIMITED);
    }

    @Test
    void storesExactlyTheDailyCapAndRejectsTheNextSpacedHeartbeat() {
        TestIdentity identity = newIdentity();
        registerPage(identity.pageVisitId(), identity.sessionHash(), 42L, 99L);
        properties.setDailyCapSeconds(30);
        gate = new ReadingHeartbeatGate(redisTemplate, properties, new SimpleMeterRegistry());

        for (long sequence = 1; sequence <= 3; sequence++) {
            long now = BASE_TIME_MILLIS + (sequence - 1L) * 61_000L;
            assertThat(gate.evaluate(command(identity, sequence, now)))
                .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
        }

        assertThat(redisTemplate.opsForValue().get(creditKey(identity.sessionHash(), 42L, 99L)))
            .isEqualTo("30");
        assertThat(gate.evaluate(command(identity, 4L, BASE_TIME_MILLIS + 3L * 61_000L)))
            .isEqualTo(ReadingHeartbeatOutcome.DAILY_CAP_REACHED);
    }

    @Test
    void permitsExactlyOneConcurrentCreditAtTheThirtySecondCap() throws Exception {
        properties.setSessionLimit(100);
        properties.setIpLimit(100);
        properties.setDailyCapSeconds(30);
        gate = new ReadingHeartbeatGate(redisTemplate, properties, new SimpleMeterRegistry());

        String suffix = randomSuffix();
        String sessionHash = "it-session-" + suffix;
        String ipHmac = "it-ip-" + suffix;
        List<TestIdentity> identities = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            TestIdentity identity = new TestIdentity("it-page-" + suffix + "-" + index, sessionHash, ipHmac);
            identities.add(identity);
            registerPage(identity.pageVisitId(), identity.sessionHash(), 42L, 99L);
        }

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ReadingHeartbeatOutcome>> futures = identities.stream()
                .map(identity -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return gate.evaluate(command(identity, 1L, BASE_TIME_MILLIS));
                }))
                .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ReadingHeartbeatOutcome> outcomes = new ArrayList<>();
            for (Future<ReadingHeartbeatOutcome> future : futures) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(ReadingHeartbeatOutcome.ACCEPTED::equals).hasSize(3);
            assertThat(outcomes).allMatch(outcome -> outcome == ReadingHeartbeatOutcome.ACCEPTED
                || outcome == ReadingHeartbeatOutcome.DAILY_CAP_REACHED
                || outcome == ReadingHeartbeatOutcome.DUPLICATE);
            assertThat(redisTemplate.opsForValue().get(creditKey(sessionHash, 42L, 99L))).isEqualTo("30");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private TestIdentity newIdentity() {
        String suffix = randomSuffix();
        return new TestIdentity("it-page-" + suffix, "it-session-" + suffix, "it-ip-" + suffix);
    }

    private String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void registerPage(String pageVisitId, String sessionHash, Long bookId, Long chapterId) {
        String pageKey = pageKey(pageVisitId);
        redisTemplate.opsForHash().putAll(pageKey, Map.of(
            "sessionHash", sessionHash,
            "bookId", String.valueOf(bookId),
            "chapterId", String.valueOf(chapterId),
            "lastSequence", "0"));
        redisTemplate.expire(pageKey, Duration.ofHours(2));
    }

    private ReadingHeartbeatCommand command(TestIdentity identity, long sequence, long nowEpochMillis) {
        trackTransitionKeys(identity.pageVisitId(), identity.sessionHash(), identity.ipHmac(), 42L, 99L);
        return new ReadingHeartbeatCommand(42L, 99L, identity.pageVisitId(), sequence,
            identity.sessionHash(), identity.ipHmac(), STAT_DATE, nowEpochMillis);
    }

    private void trackTransitionKeys(String pageVisitId, String sessionHash, String ipHmac, Long bookId,
        Long chapterId) {
        exactCleanupKeys.add(pageKey(pageVisitId));
        exactCleanupKeys.add("reading:rate:session:" + sessionHash);
        exactCleanupKeys.add("reading:rate:ip:" + ipHmac);
        exactCleanupKeys.add(creditKey(sessionHash, bookId, chapterId));
    }

    private String pageKey(String pageVisitId) {
        String key = "reading:page:{" + pageVisitId + "}";
        exactCleanupKeys.add(key);
        return key;
    }

    private String creditKey(String sessionHash, Long bookId, Long chapterId) {
        String key = "reading:credit:" + STAT_DATE + ":" + sessionHash + ":" + bookId + ":" + chapterId;
        exactCleanupKeys.add(key);
        return key;
    }

    private record TestIdentity(String pageVisitId, String sessionHash, String ipHmac) {
    }
}
