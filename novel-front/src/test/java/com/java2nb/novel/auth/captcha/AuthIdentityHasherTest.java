package com.java2nb.novel.auth.captcha;

import com.java2nb.novel.auth.config.AuthSecurityProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuthIdentityHasherTest {
    @Test void emailKeysSeparatePurposesAndHideTheAddress() {
        AuthSecurityProperties properties = new AuthSecurityProperties();
        properties.setHmacSecret("unit-test-only-secret");
        AuthIdentityHasher hasher = new AuthIdentityHasher(properties);

        String register = hasher.emailKey(CaptchaPurpose.REGISTER, "reader@example.com");
        String reset = hasher.emailKey(CaptchaPurpose.RESET_PASSWORD, "reader@example.com");
        assertThat(register).startsWith("auth:captcha:register:")
            .doesNotContain("reader", "example.com").isNotEqualTo(reset);
        assertThat(hasher.codeDigest(CaptchaPurpose.REGISTER, "reader@example.com", "000123"))
            .isNotEqualTo(hasher.codeDigest(CaptchaPurpose.RESET_PASSWORD, "reader@example.com", "000123"));
        assertThat(hasher.ipKey("203.0.113.9")).doesNotContain("203.0.113.9");
    }
}
