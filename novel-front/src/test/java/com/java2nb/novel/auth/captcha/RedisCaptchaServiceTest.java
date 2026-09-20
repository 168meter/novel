package com.java2nb.novel.auth.captcha;

import com.java2nb.novel.auth.config.AuthSecurityProperties;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisCaptchaServiceTest {
    private StringRedisTemplate redis;
    private RedisCaptchaService service;

    @BeforeEach void setUp() {
        redis = mock(StringRedisTemplate.class);
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextInt(1_000_000)).thenReturn(12345);
        AuthSecurityProperties properties = new AuthSecurityProperties();
        properties.setHmacSecret("unit-test-only-secret");
        service = new RedisCaptchaService(redis, new AuthIdentityHasher(properties), random, properties);
    }

    @Test void issueUsesHashedIdentifiersAndNeverStoresTheSixDigitCode() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        CaptchaIssue issued = service.issue(CaptchaPurpose.REGISTER, "reader@example.com", "203.0.113.9");
        assertThat(issued.outcome()).isEqualTo(CaptchaIssueOutcome.ISSUED);
        assertThat(issued.code()).isEqualTo("012345");
        assertThat(issued.toString()).doesNotContain("012345", "reader@example.com");
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(keys.getValue()).allSatisfy(key -> assertThat(key)
            .doesNotContain("reader@example.com", "203.0.113.9"));
        assertThat(List.of(args.getValue())).doesNotContain("012345", "reader@example.com", "203.0.113.9");
    }

    @Test void cooldownAndLimitsReturnNoCode() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(2L, 3L, 4L);
        CaptchaIssue cooldown = service.issue(CaptchaPurpose.REGISTER, "reader@example.com", "203.0.113.9");
        CaptchaIssue emailLimited = service.issue(CaptchaPurpose.REGISTER, "reader@example.com", "203.0.113.9");
        CaptchaIssue ipLimited = service.issue(CaptchaPurpose.REGISTER, "reader@example.com", "203.0.113.9");
        assertThat(cooldown.outcome()).isEqualTo(CaptchaIssueOutcome.COOLDOWN);
        assertThat(emailLimited.outcome()).isEqualTo(CaptchaIssueOutcome.EMAIL_LIMITED);
        assertThat(ipLimited.outcome()).isEqualTo(CaptchaIssueOutcome.IP_LIMITED);
        assertThat(List.of(cooldown, emailLimited, ipLimited)).allSatisfy(result -> assertThat(result.code()).isNull());
    }

    @Test void redisFailureDoesNotIssueCode() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenThrow(new IllegalStateException("Redis unavailable"));
        assertThatThrownBy(() -> service.issue(CaptchaPurpose.REGISTER, "reader@example.com", "203.0.113.9"))
            .isInstanceOf(IllegalStateException.class);
    }
}
