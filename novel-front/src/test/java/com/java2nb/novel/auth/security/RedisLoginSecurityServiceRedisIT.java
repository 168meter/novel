package com.java2nb.novel.auth.security;

import com.java2nb.novel.auth.captcha.AuthIdentityHasher;
import com.java2nb.novel.auth.config.AuthSecurityProperties;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "novel.redis.it.enabled", matches = "true")
class RedisLoginSecurityServiceRedisIT {
    Set<String> cleanup = ConcurrentHashMap.newKeySet();
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    AuthIdentityHasher hasher;
    RedisLoginSecurityService service;

    @BeforeEach void setUp() {
        String password = System.getenv("NOVEL_REDIS_TEST_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("NOVEL_REDIS_TEST_PASSWORD is required");
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1", 6380);
        config.setPassword(RedisPassword.of(password));
        factory = new LettuceConnectionFactory(config); factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
        AuthSecurityProperties properties = new AuthSecurityProperties();
        properties.setHmacSecret("login-security-integration-test");
        properties.setLoginWindow(Duration.ofSeconds(30));
        properties.setLoginRateWindow(Duration.ofSeconds(20));
        properties.setImageCaptchaTtl(Duration.ofSeconds(15));
        hasher = new AuthIdentityHasher(properties);
        service = new RedisLoginSecurityService(redis, hasher, properties);
    }

    @AfterEach void cleanup() {
        try { if (redis != null && !cleanup.isEmpty()) redis.delete(cleanup); }
        finally { if (factory != null) factory.destroy(); }
    }

    @Test void accountIpAndRateWindowsAreAtomicAndCaptchaIsOneUse() {
        String account = identity() + "@example.com", accountIp = ip(); track(account, accountIp);
        runConcurrently(5, () -> service.recordFailure(account, accountIp));
        assertThat(redis.opsForValue().get(hasher.loginAccountKey(account))).isEqualTo("5");
        assertThat(redis.opsForValue().get(hasher.loginIpKey(accountIp))).isEqualTo("5");
        assertThat(redis.getExpire(hasher.loginAccountKey(account))).isBetween(1L, 30L);
        assertThat(redis.getExpire(hasher.loginIpKey(accountIp))).isBetween(1L, 30L);
        assertThat(service.check(account, accountIp)).isEqualTo(LoginSecurityDecision.ACCOUNT_LIMITED);
        assertThat(redis.getExpire(hasher.loginRateKey(accountIp))).isBetween(1L, 20L);
        service.clearAccountFailures(account);
        assertThat(service.check(account, accountIp)).isEqualTo(LoginSecurityDecision.ALLOWED);

        String riskyIp = ip();
        for (int i = 0; i < 10; i++) {
            String distinct = identity() + "@example.com"; track(distinct, riskyIp);
            service.recordFailure(distinct, riskyIp);
        }
        String candidate = identity() + "@example.com"; track(candidate, riskyIp);
        assertThat(service.check(candidate, riskyIp)).isEqualTo(LoginSecurityDecision.CAPTCHA_REQUIRED);
        service.storeImageCaptcha(riskyIp, "1234");
        assertThat(redis.getExpire(hasher.imageCaptchaKey(riskyIp))).isBetween(1L, 15L);
        List<Boolean> captchaResults = runConcurrently(8,
            () -> service.consumeImageCaptcha(riskyIp, "1234"));
        assertThat(captchaResults).containsOnlyOnce(true);
        assertThat(captchaResults).contains(false);
        assertThat(service.consumeImageCaptcha(riskyIp, "1234")).isFalse();

        String busyIp = ip(), fresh = identity() + "@example.com"; track(fresh, busyIp);
        for (int i = 0; i < 60; i++) assertThat(service.check(fresh, busyIp)).isEqualTo(LoginSecurityDecision.ALLOWED);
        assertThat(service.check(fresh, busyIp)).isEqualTo(LoginSecurityDecision.RATE_LIMITED);
    }

    private void track(String account, String ip) {
        cleanup.add(hasher.loginAccountKey(account)); cleanup.add(hasher.loginIpKey(ip));
        cleanup.add(hasher.loginRateKey(ip)); cleanup.add(hasher.imageCaptchaKey(ip));
    }
    private static <T> List<T> runConcurrently(int count, Callable<T> action) {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(executor.submit(() -> { start.await(); return action.call(); }));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(10, TimeUnit.SECONDS));
            return results;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            executor.shutdownNow();
        }
    }
    private static void runConcurrently(int count, Runnable action) {
        runConcurrently(count, () -> { action.run(); return Boolean.TRUE; });
    }
    private static String identity() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String ip() { return "198.51.100." + (1 + Math.abs(UUID.randomUUID().hashCode() % 200)); }
}
