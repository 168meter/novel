package com.java2nb.novel.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import com.java2nb.novel.auth.metrics.AuthenticationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class Argon2idPasswordAlgorithmHandlerTest {
    @Test void lowCostHashNeedsUpgradeButCurrentOrStrongerDoesNot() {
        Argon2PasswordEncoder current = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Argon2idPasswordAlgorithmHandler handler = new Argon2idPasswordAlgorithmHandler(
            current, 19456, 2, 1, new AuthenticationMetrics(registry));
        String low = new Argon2PasswordEncoder(16, 32, 1, 1024, 1).encode("secret");
        String same = current.encode("secret");
        String stronger = new Argon2PasswordEncoder(16, 32, 1, 32768, 3).encode("secret");
        assertThat(handler.needsUpgrade(low)).isTrue();
        assertThat(handler.needsUpgrade(same)).isFalse();
        assertThat(handler.needsUpgrade(stronger)).isFalse();
        String encoded = handler.encode("secret").encoded();
        assertThat(handler.matches("secret", encoded)).isTrue();
        assertThat(registry.timer("novel.auth.argon2.duration", "operation", "encode").count()).isEqualTo(1);
        assertThat(registry.timer("novel.auth.argon2.duration", "operation", "verify").count()).isEqualTo(1);
    }
}
