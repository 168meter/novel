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
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in real Redis coverage. The test replaces only the script's Redis TIME call with a fixed
 * Redis-compatible response, so exact millisecond boundaries are deterministic without sleeping.
 */
@EnabledIfSystemProperty(named = "redis.it", matches = "true")
class ReadingHeartbeatGateRedisIT {

    private static final LocalDate STAT_DATE = LocalDate.of(2026, 9, 10);
    private static final long BASE_TIME_MILLIS = 1_789_000_000_000L;

    private final Set<String> exactCleanupKeys = ConcurrentHashMap.newKeySet();
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private ReadingEngagementProperties properties;
    private ReadingIdentityHasher identityHasher;
    private ReadingPageVisitRegistrar registrar;
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
        identityHasher = new ReadingIdentityHasher(properties);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        registrar = new ReadingPageVisitRegistrar(redisTemplate, identityHasher, properties, meterRegistry);
        gate = new ReadingHeartbeatGate(redisTemplate, properties, meterRegistry);
    }

    @Test
    void productionRegistrarAndGateUseRealRedisTimeWithoutClientTime() {
        String userMark = "it-reader-" + randomSuffix();
        String sessionHash = identityHasher.sessionHash(userMark);
        String ipHmac = identityHasher.ipHmac("127.0.0.1");
        String pageVisitId = registrar.register(userMark, 42L, 99L).orElseThrow();
        TestPage page = new TestPage(pageVisitId, sessionHash, ipHmac, 42L, 99L);
        exactCleanupKeys.addAll(transitionKeys(page));

        Object createdAtMillis = redisTemplate.opsForHash().get(pageKey(pageVisitId), "createdAtMillis");
        assertThat(createdAtMillis).asString().matches("[1-9]\\d*");
        assertThat(gate.evaluate(new ReadingHeartbeatCommand(
            42L, 99L, pageVisitId, 1L, sessionHash, ipHmac, STAT_DATE)))
            .isEqualTo(ReadingHeartbeatOutcome.TOO_EARLY);
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
    void firstHeartbeatRequiresTheFullTenSecondPageLifetime() {
        TestPage page = newPage(42L, 99L, BASE_TIME_MILLIS);

        assertThat(evaluateAt(page, 1L, BASE_TIME_MILLIS)).isEqualTo(ReadingHeartbeatOutcome.TOO_EARLY);
        assertThat(evaluateAt(page, 1L, BASE_TIME_MILLIS + 9_999L))
            .isEqualTo(ReadingHeartbeatOutcome.TOO_EARLY);
        assertThat(evaluateAt(page, 1L, BASE_TIME_MILLIS + 10_000L))
            .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
    }

    @Test
    void tooEarlyDoesNotAdvanceSequenceOrConsumeCreditOrRateQuota() {
        TestPage page = newPage(42L, 99L, BASE_TIME_MILLIS);

        assertThat(evaluateAt(page, 1L, BASE_TIME_MILLIS + 10_000L))
            .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
        assertThat(evaluateAt(page, 2L, BASE_TIME_MILLIS + 10_000L))
            .isEqualTo(ReadingHeartbeatOutcome.TOO_EARLY);

        assertThat(redisTemplate.opsForHash().get(pageKey(page.pageVisitId()), "lastSequence")).isEqualTo("1");
        assertThat(redisTemplate.opsForValue().get(sessionTimeKey(page.sessionHash())))
            .isEqualTo(String.valueOf(BASE_TIME_MILLIS + 10_000L));
        assertThat(redisTemplate.opsForValue().get(creditKey(page))).isEqualTo("10");
        assertThat(redisTemplate.opsForZSet().size(sessionRateKey(page.sessionHash()))).isEqualTo(1L);
        assertThat(redisTemplate.opsForZSet().size(ipRateKey(page.ipHmac()))).isEqualTo(1L);
        assertThat(redisTemplate.getExpire(sessionTimeKey(page.sessionHash()), TimeUnit.SECONDS))
            .isBetween(7_199L, 7_200L);

        assertThat(evaluateAt(page, 2L, BASE_TIME_MILLIS + 20_000L))
            .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
    }

    @Test
    void oneSessionCannotCreditParallelPagesAcrossChaptersOrBooks() {
        String suffix = randomSuffix();
        String sessionHash = "it-session-" + suffix;
        String ipHmac = "it-ip-" + suffix;
        TestPage first = newPage(sessionHash, ipHmac, 42L, 99L, BASE_TIME_MILLIS);
        TestPage otherChapter = newPage(sessionHash, ipHmac, 42L, 100L, BASE_TIME_MILLIS);
        TestPage otherBook = newPage(sessionHash, ipHmac, 77L, 1L, BASE_TIME_MILLIS);

        assertThat(evaluateAt(first, 1L, BASE_TIME_MILLIS + 10_000L))
            .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
        assertThat(evaluateAt(otherChapter, 1L, BASE_TIME_MILLIS + 10_000L))
            .isEqualTo(ReadingHeartbeatOutcome.TOO_EARLY);
        assertThat(evaluateAt(otherBook, 1L, BASE_TIME_MILLIS + 10_000L))
            .isEqualTo(ReadingHeartbeatOutcome.TOO_EARLY);
        assertThat(evaluateAt(otherBook, 1L, BASE_TIME_MILLIS + 20_000L))
            .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
    }

    @Test
    void refreshedPageWaitsForItsOwnIntervalAndKeepsTheSessionInterval() {
        String suffix = randomSuffix();
        String sessionHash = "it-session-" + suffix;
        String ipHmac = "it-ip-" + suffix;
        TestPage oldPage = newPage(sessionHash, ipHmac, 42L, 99L, BASE_TIME_MILLIS);
        assertThat(evaluateAt(oldPage, 1L, BASE_TIME_MILLIS + 10_000L))
            .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);

        TestPage refreshed = newPage(sessionHash, ipHmac, 42L, 99L, BASE_TIME_MILLIS + 10_000L);
        assertThat(evaluateAt(refreshed, 1L, BASE_TIME_MILLIS + 19_999L))
            .isEqualTo(ReadingHeartbeatOutcome.TOO_EARLY);
        assertThat(evaluateAt(refreshed, 1L, BASE_TIME_MILLIS + 20_000L))
            .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
    }

    @Test
    void normalTenTwentyThirtySecondHeartbeatsCreditThirtySeconds() {
        TestPage page = newPage(42L, 99L, BASE_TIME_MILLIS);

        for (long sequence = 1; sequence <= 3; sequence++) {
            assertThat(evaluateAt(page, sequence, BASE_TIME_MILLIS + sequence * 10_000L))
                .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
        }

        assertThat(redisTemplate.opsForValue().get(creditKey(page))).isEqualTo("30");
    }

    @Test
    void creditsExactlyToANonzeroDailyCapAndRejectsTheNextHeartbeat() {
        properties.setDailyCapSeconds(20);
        TestPage page = newPage(42L, 99L, BASE_TIME_MILLIS);

        assertThat(evaluateAt(page, 1L, BASE_TIME_MILLIS + 10_000L))
            .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
        assertThat(evaluateAt(page, 2L, BASE_TIME_MILLIS + 20_000L))
            .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
        assertThat(evaluateAt(page, 3L, BASE_TIME_MILLIS + 30_000L))
            .isEqualTo(ReadingHeartbeatOutcome.DAILY_CAP_REACHED);
        assertThat(redisTemplate.opsForValue().get(creditKey(page))).isEqualTo("20");
        assertThat(redisTemplate.opsForHash().get(pageKey(page.pageVisitId()), "lastSequence")).isEqualTo("2");
    }

    @Test
    void preservesBindingDuplicateRateLimitAndDailyCapRules() {
        TestPage page = newPage(42L, 99L, BASE_TIME_MILLIS);
        assertThat(evaluateAt(page, 1L, BASE_TIME_MILLIS + 10_000L))
            .isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
        assertThat(evaluateAt(page, 1L, BASE_TIME_MILLIS + 20_000L))
            .isEqualTo(ReadingHeartbeatOutcome.DUPLICATE);

        assertThat(evaluateAt(page.withBookId(43L), 2L, BASE_TIME_MILLIS + 20_000L))
            .isEqualTo(ReadingHeartbeatOutcome.INVALID_PAGE);
        assertThat(evaluateAt(page.withChapterId(100L), 2L, BASE_TIME_MILLIS + 20_000L))
            .isEqualTo(ReadingHeartbeatOutcome.INVALID_PAGE);
        assertThat(evaluateAt(page.withSessionHash("wrong-session"), 2L, BASE_TIME_MILLIS + 20_000L))
            .isEqualTo(ReadingHeartbeatOutcome.INVALID_PAGE);

        properties.setSessionLimit(1);
        assertThat(evaluateAt(page, 2L, BASE_TIME_MILLIS + 20_000L))
            .isEqualTo(ReadingHeartbeatOutcome.SESSION_RATE_LIMITED);

        TestPage ipLimited = newPage(77L, 1L, BASE_TIME_MILLIS);
        properties.setSessionLimit(100);
        properties.setIpLimit(0);
        assertThat(evaluateAt(ipLimited, 1L, BASE_TIME_MILLIS + 10_000L))
            .isEqualTo(ReadingHeartbeatOutcome.IP_RATE_LIMITED);

        TestPage capped = newPage(88L, 1L, BASE_TIME_MILLIS);
        properties.setIpLimit(100);
        properties.setDailyCapSeconds(0);
        assertThat(evaluateAt(capped, 1L, BASE_TIME_MILLIS + 10_000L))
            .isEqualTo(ReadingHeartbeatOutcome.DAILY_CAP_REACHED);

        TestPage absent = new TestPage("absent-" + randomSuffix(), page.sessionHash(), page.ipHmac(), 42L, 99L);
        assertThat(evaluateAt(absent, 1L, BASE_TIME_MILLIS + 10_000L))
            .isEqualTo(ReadingHeartbeatOutcome.INVALID_PAGE);
    }

    @Test
    void exactlyOneParallelTabCanCreditAtOneRedisInstant() throws Exception {
        properties.setSessionLimit(100);
        properties.setIpLimit(100);
        String suffix = randomSuffix();
        String sessionHash = "it-session-" + suffix;
        String ipHmac = "it-ip-" + suffix;
        List<TestPage> pages = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            pages.add(newPage(sessionHash, ipHmac, 42L, 99L + index, BASE_TIME_MILLIS));
        }

        var executor = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ReadingHeartbeatOutcome>> futures = pages.stream()
                .map(page -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return evaluateAt(page, 1L, BASE_TIME_MILLIS + 10_000L);
                }))
                .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ReadingHeartbeatOutcome> outcomes = new ArrayList<>();
            for (Future<ReadingHeartbeatOutcome> future : futures) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(ReadingHeartbeatOutcome.ACCEPTED::equals).hasSize(1);
            assertThat(outcomes).allMatch(outcome -> outcome == ReadingHeartbeatOutcome.ACCEPTED
                || outcome == ReadingHeartbeatOutcome.TOO_EARLY);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private TestPage newPage(Long bookId, Long chapterId, long createdAtMillis) {
        String suffix = randomSuffix();
        return newPage("it-session-" + suffix, "it-ip-" + suffix, bookId, chapterId, createdAtMillis);
    }

    private TestPage newPage(String sessionHash, String ipHmac, Long bookId, Long chapterId,
        long createdAtMillis) {
        TestPage page = new TestPage("it-page-" + randomSuffix(), sessionHash, ipHmac, bookId, chapterId);
        String key = pageKey(page.pageVisitId());
        redisTemplate.opsForHash().putAll(key, Map.of(
            "sessionHash", sessionHash,
            "bookId", String.valueOf(bookId),
            "chapterId", String.valueOf(chapterId),
            "createdAtMillis", String.valueOf(createdAtMillis),
            "lastSequence", "0"));
        redisTemplate.expire(key, Duration.ofHours(2));
        return page;
    }

    private ReadingHeartbeatOutcome evaluateAt(TestPage page, long sequence, long nowMillis) {
        String fixedTime = "local redisTime = {'" + nowMillis / 1_000L + "', '"
            + nowMillis % 1_000L * 1_000L + "'}";
        String scriptText = ReadingHeartbeatGate.GATE_SCRIPT.getScriptAsString()
            .replace("local redisTime = redis.call('TIME')", fixedTime);
        assertThat(scriptText).doesNotContain("redis.call('TIME')");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(scriptText, Long.class);
        Long result = redisTemplate.execute(script, transitionKeys(page),
            page.sessionHash(), String.valueOf(page.bookId()), String.valueOf(page.chapterId()),
            String.valueOf(sequence), "10000", String.valueOf(properties.rateWindow().toMillis()),
            String.valueOf(properties.sessionLimit()), String.valueOf(properties.ipLimit()),
            String.valueOf(properties.dailyCapSeconds()), String.valueOf(properties.creditedSeconds()),
            String.valueOf(properties.rateKeyTtl().toSeconds()),
            String.valueOf(properties.creditKeyTtl().toSeconds()),
            String.valueOf(properties.pageTtl().toSeconds()), page.pageVisitId() + ":" + sequence);
        return outcome(result);
    }

    private List<String> transitionKeys(TestPage page) {
        List<String> keys = List.of(
            pageKey(page.pageVisitId()),
            sessionTimeKey(page.sessionHash()),
            sessionRateKey(page.sessionHash()),
            ipRateKey(page.ipHmac()),
            creditKey(page));
        exactCleanupKeys.addAll(keys);
        return keys;
    }

    private ReadingHeartbeatOutcome outcome(Long result) {
        return switch (result.intValue()) {
            case 1 -> ReadingHeartbeatOutcome.ACCEPTED;
            case 2 -> ReadingHeartbeatOutcome.DUPLICATE;
            case 3 -> ReadingHeartbeatOutcome.INVALID_PAGE;
            case 4 -> ReadingHeartbeatOutcome.SESSION_RATE_LIMITED;
            case 5 -> ReadingHeartbeatOutcome.IP_RATE_LIMITED;
            case 6 -> ReadingHeartbeatOutcome.DAILY_CAP_REACHED;
            case 7 -> ReadingHeartbeatOutcome.TOO_EARLY;
            default -> ReadingHeartbeatOutcome.REDIS_ERROR;
        };
    }

    private String pageKey(String pageVisitId) {
        String key = "reading:page:{" + pageVisitId + "}";
        exactCleanupKeys.add(key);
        return key;
    }

    private String sessionTimeKey(String sessionHash) {
        return "reading:time:session:" + sessionHash;
    }

    private String sessionRateKey(String sessionHash) {
        return "reading:rate:session:" + sessionHash;
    }

    private String ipRateKey(String ipHmac) {
        return "reading:rate:ip:" + ipHmac;
    }

    private String creditKey(TestPage page) {
        return "reading:credit:" + STAT_DATE + ":" + page.sessionHash() + ":" + page.bookId() + ":"
            + page.chapterId();
    }

    private String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record TestPage(String pageVisitId, String sessionHash, String ipHmac, Long bookId, Long chapterId) {
        private TestPage withBookId(Long value) {
            return new TestPage(pageVisitId, sessionHash, ipHmac, value, chapterId);
        }

        private TestPage withChapterId(Long value) {
            return new TestPage(pageVisitId, sessionHash, ipHmac, bookId, value);
        }

        private TestPage withSessionHash(String value) {
            return new TestPage(pageVisitId, value, ipHmac, bookId, chapterId);
        }
    }
}
