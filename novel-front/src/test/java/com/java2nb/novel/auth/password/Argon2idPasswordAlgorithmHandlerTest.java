package com.java2nb.novel.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class Argon2idPasswordAlgorithmHandlerTest {
    @Test void lowCostHashNeedsUpgradeButCurrentOrStrongerDoesNot() {
        Argon2PasswordEncoder current = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        Argon2idPasswordAlgorithmHandler handler = new Argon2idPasswordAlgorithmHandler(
            current, 19456, 2, 1);
        String low = new Argon2PasswordEncoder(16, 32, 1, 1024, 1).encode("secret");
        String same = current.encode("secret");
        String stronger = new Argon2PasswordEncoder(16, 32, 1, 32768, 3).encode("secret");
        assertThat(handler.needsUpgrade(low)).isTrue();
        assertThat(handler.needsUpgrade(same)).isFalse();
        assertThat(handler.needsUpgrade(stronger)).isFalse();
    }
}
