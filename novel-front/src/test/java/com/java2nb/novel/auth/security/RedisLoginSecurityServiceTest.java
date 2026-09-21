package com.java2nb.novel.auth.security;

import com.java2nb.novel.auth.captcha.AuthIdentityHasher;
import com.java2nb.novel.auth.config.AuthSecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisLoginSecurityServiceTest {
    StringRedisTemplate redis;
    RedisLoginSecurityService service;

    @BeforeEach void setUp() {
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        AuthSecurityProperties properties = new AuthSecurityProperties();
        properties.setHmacSecret("test-only-hmac-secret");
        service = new RedisLoginSecurityService(redis, new AuthIdentityHasher(properties), properties);
    }

    @Test void mapsAtomicCheckResultsAndFailsClosed() {
        for (int code = 1; code <= 4; code++) {
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn((long) code);
            assertThat(service.check("reader@example.com", "198.51.100.7"))
                .isEqualTo(LoginSecurityDecision.values()[code - 1]);
        }
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenThrow(new IllegalStateException("redis down"));
        assertThat(service.check("reader@example.com", "198.51.100.7"))
            .isEqualTo(LoginSecurityDecision.DEPENDENCY_ERROR);
    }

    @Test void keysAreHmacIdentifiersAndFailureUsesAtomicScript() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        service.recordFailure("reader@example.com", "198.51.100.7");
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).allSatisfy(key -> assertThat(key)
            .doesNotContain("reader@example.com", "198.51.100.7"));
    }

    @Test void successfulLoginClearsOnlyAccountCounter() {
        service.clearAccountFailures("reader@example.com");
        verify(redis).delete(org.mockito.ArgumentMatchers.<String>argThat(
            key -> key.startsWith("auth:login:account:")));
    }

    @Test void imageCaptchaIsHashedAndConsumedOnce() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        service.storeImageCaptcha("198.51.100.7", "1234");
        verify(redis.opsForValue()).set(anyString(), argThat(value -> !"1234".equals(value)), any(java.time.Duration.class));
        assertThat(service.consumeImageCaptcha("198.51.100.7", "1234")).isTrue();
    }

    @Test void subSecondWindowsAreRejectedInsteadOfBecomingZeroTtl() {
        AuthSecurityProperties properties = new AuthSecurityProperties();
        properties.setHmacSecret("test-only-hmac-secret");
        properties.setLoginRateWindow(Duration.ofMillis(500));
        assertThatThrownBy(() -> new RedisLoginSecurityService(redis,
            new AuthIdentityHasher(properties), properties)).isInstanceOf(IllegalArgumentException.class);
    }
}
