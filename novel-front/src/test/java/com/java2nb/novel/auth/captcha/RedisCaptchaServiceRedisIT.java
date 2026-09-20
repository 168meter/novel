package com.java2nb.novel.auth.captcha;

import com.java2nb.novel.auth.config.AuthSecurityProperties;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "novel.redis.it.enabled", matches = "true")
class RedisCaptchaServiceRedisIT {
    private final Set<String> cleanup = ConcurrentHashMap.newKeySet();
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private AuthIdentityHasher hasher;
    private RedisCaptchaService service;

    @BeforeEach void setUp() {
        String password = System.getenv("NOVEL_REDIS_TEST_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("NOVEL_REDIS_TEST_PASSWORD is required for Redis integration tests.");
        }
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1", 6380);
        config.setPassword(RedisPassword.of(password));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        AuthSecurityProperties properties = new AuthSecurityProperties();
        properties.setHmacSecret("integration-test-only-hmac-key");
        hasher = new AuthIdentityHasher(properties);
        service = new RedisCaptchaService(redis, hasher, new SecureRandom(), properties);
    }

    @AfterEach void cleanOnlyThisTestKeys() {
        try {
            if (redis != null && !cleanup.isEmpty()) {
                redis.delete(cleanup);
                assertThat(cleanup).noneMatch(redis::hasKey);
            }
        } finally {
            if (factory != null) factory.destroy();
        }
    }

    @Test void codeIsHashedExpiresAndIsConsumedOnceWithinItsPurpose() {
        String email = email();
        String ip = ip();
        track(CaptchaPurpose.REGISTER, email, ip);
        track(CaptchaPurpose.RESET_PASSWORD, email, ip);
        CaptchaIssue issued = service.issue(CaptchaPurpose.REGISTER, email, ip);
        assertThat(issued.outcome()).isEqualTo(CaptchaIssueOutcome.ISSUED);
        assertThat(issued.code()).matches("\\d{6}");
        String key = hasher.emailKey(CaptchaPurpose.REGISTER, email);
        assertThat(redis.opsForValue().get(key))
            .isEqualTo(hasher.codeDigest(CaptchaPurpose.REGISTER, email, issued.code()))
            .isNotEqualTo(issued.code());
        assertThat(redis.getExpire(key)).isBetween(1L, 600L);
        assertThat(service.consume(CaptchaPurpose.RESET_PASSWORD, email, issued.code()))
            .isEqualTo(CaptchaConsumeOutcome.EXPIRED);
        assertThat(service.consume(CaptchaPurpose.REGISTER, email, issued.code()))
            .isEqualTo(CaptchaConsumeOutcome.CONSUMED);
        assertThat(service.consume(CaptchaPurpose.REGISTER, email, issued.code()))
            .isEqualTo(CaptchaConsumeOutcome.EXPIRED);
    }

    @Test void cooldownEmailAndIpBudgetsAreEnforced() {
        String email = email();
        String ip = ip();
        track(CaptchaPurpose.REGISTER, email, ip);
        assertThat(service.issue(CaptchaPurpose.REGISTER, email, ip).outcome())
            .isEqualTo(CaptchaIssueOutcome.ISSUED);
        assertThat(service.issue(CaptchaPurpose.REGISTER, email, ip).outcome())
            .isEqualTo(CaptchaIssueOutcome.COOLDOWN);
        String emailKey = hasher.emailKey(CaptchaPurpose.REGISTER, email);
        for (int i = 1; i < 5; i++) {
            redis.delete("auth:captcha:cooldown:" + suffix(emailKey));
            assertThat(service.issue(CaptchaPurpose.REGISTER, email, ip).outcome())
                .isEqualTo(CaptchaIssueOutcome.ISSUED);
        }
        redis.delete("auth:captcha:cooldown:" + suffix(emailKey));
        assertThat(service.issue(CaptchaPurpose.REGISTER, email, ip).outcome())
            .isEqualTo(CaptchaIssueOutcome.EMAIL_LIMITED);

        String sharedIp = ip();
        for (int i = 0; i < 30; i++) {
            String distinctEmail = email();
            track(CaptchaPurpose.RESET_PASSWORD, distinctEmail, sharedIp);
            assertThat(service.issue(CaptchaPurpose.RESET_PASSWORD, distinctEmail, sharedIp).outcome())
                .isEqualTo(CaptchaIssueOutcome.ISSUED);
        }
        String blockedEmail = email();
        track(CaptchaPurpose.RESET_PASSWORD, blockedEmail, sharedIp);
        assertThat(service.issue(CaptchaPurpose.RESET_PASSWORD, blockedEmail, sharedIp).outcome())
            .isEqualTo(CaptchaIssueOutcome.IP_LIMITED);
    }

    @Test void fifthWrongAttemptInvalidatesTheCorrectCode() {
        String email = email();
        String ip = ip();
        track(CaptchaPurpose.REGISTER, email, ip);
        CaptchaIssue issued = service.issue(CaptchaPurpose.REGISTER, email, ip);
        String wrong = issued.code().equals("999999") ? "000000" : "999999";
        for (int i = 0; i < 4; i++) {
            assertThat(service.consume(CaptchaPurpose.REGISTER, email, wrong))
                .isEqualTo(CaptchaConsumeOutcome.INVALID);
        }
        assertThat(service.consume(CaptchaPurpose.REGISTER, email, wrong))
            .isEqualTo(CaptchaConsumeOutcome.TOO_MANY_ATTEMPTS);
        assertThat(service.consume(CaptchaPurpose.REGISTER, email, issued.code()))
            .isEqualTo(CaptchaConsumeOutcome.EXPIRED);
    }

    @Test void delayedMailFailureCannotRevokeAReplacementCode() {
        String email = email();
        String ip = ip();
        track(CaptchaPurpose.REGISTER, email, ip);
        SecureRandom controlled = mock(SecureRandom.class);
        when(controlled.nextInt(1_000_000)).thenReturn(111111, 222222);
        AuthSecurityProperties properties = new AuthSecurityProperties();
        properties.setHmacSecret("integration-test-only-hmac-key");
        service = new RedisCaptchaService(redis, hasher, controlled, properties);
        CaptchaIssue first = service.issue(CaptchaPurpose.REGISTER, email, ip);
        String emailKey = hasher.emailKey(CaptchaPurpose.REGISTER, email);
        redis.delete("auth:captcha:cooldown:" + suffix(emailKey));
        CaptchaIssue second = service.issue(CaptchaPurpose.REGISTER, email, ip);
        service.revoke(CaptchaPurpose.REGISTER, email, first.code());
        assertThat(service.consume(CaptchaPurpose.REGISTER, email, second.code()))
            .isEqualTo(CaptchaConsumeOutcome.CONSUMED);
    }

    @Test void oneHundredConcurrentConsumersCreditExactlyOnce() throws Exception {
        String email = email();
        String ip = ip();
        track(CaptchaPurpose.REGISTER, email, ip);
        String code = service.issue(CaptchaPurpose.REGISTER, email, ip).code();
        var executor = Executors.newFixedThreadPool(100);
        CountDownLatch ready = new CountDownLatch(100);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<CaptchaConsumeOutcome>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.consume(CaptchaPurpose.REGISTER, email, code);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int consumed = 0;
            for (var result : results) {
                if (result.get(20, TimeUnit.SECONDS) == CaptchaConsumeOutcome.CONSUMED) consumed++;
            }
            assertThat(consumed).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void track(CaptchaPurpose purpose, String email, String ip) {
        String key = hasher.emailKey(purpose, email);
        String suffix = suffix(key);
        cleanup.add(key);
        cleanup.add("auth:captcha:cooldown:" + suffix);
        cleanup.add("auth:captcha:hour:" + purpose.keyPart() + ":" + suffix);
        cleanup.add("auth:captcha:attempts:" + suffix);
        cleanup.add(hasher.ipKey(ip));
    }

    private static String suffix(String key) { return key.substring(key.lastIndexOf(':') + 1); }
    private static String email() { return "it-" + UUID.randomUUID() + "@example.invalid"; }
    private static String ip() {
        String id = UUID.randomUUID().toString();
        return "2001:db8::" + id.substring(0, 4) + ":" + id.substring(4, 8);
    }
}
