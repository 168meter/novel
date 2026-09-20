package com.java2nb.novel.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class PasswordServiceTest {
    private final Argon2idPasswordAlgorithmHandler argon = new Argon2idPasswordAlgorithmHandler(
        new Argon2PasswordEncoder(16, 32, 1, 19456, 2), 19456, 2, 1);
    private final PasswordService service = new DefaultPasswordService(List.of(
        argon, new Md5PasswordAlgorithmHandler()));

    @Test void encodesWithRandomSaltAndMatches() {
        PasswordHash first = service.encode("p@ssword");
        PasswordHash second = service.encode("p@ssword");
        assertThat(first.encoded()).isNotEqualTo(second.encoded());
        assertThat(first.algorithm()).isEqualTo(PasswordAlgorithm.ARGON2ID.name());
        assertThat(service.matches("p@ssword", first.encoded(), "ARGON2ID")).isTrue();
        assertThat(service.matches("wrong", first.encoded(), "ARGON2ID")).isFalse();
    }

    @Test void md5OnlyVerifiesAndCannotEncode() {
        assertThat(service.matches("secret", "5ebe2294ecd0e0f08eab7690d2a6ee69", "MD5")).isTrue();
        assertThatThrownBy(() -> new Md5PasswordAlgorithmHandler().encode("secret"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsUnknownBlankAndCorruptInputs() {
        assertThatThrownBy(() -> service.matches("x", "x", "UNKNOWN")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.matches("x", "x", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.matches("x", "not-an-argon-hash", "ARGON2ID")).isFalse();
        assertThatThrownBy(() -> service.needsUpgrade("not-an-argon-hash", "ARGON2ID"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nullAndBlankRawPasswordsAreRejectedWithoutTrimming() {
        assertThatThrownBy(() -> service.encode(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.encode("")).isInstanceOf(IllegalArgumentException.class);
        PasswordHash hash = service.encode(" secret ");
        assertThat(service.matches(" secret ", hash.encoded(), "ARGON2ID")).isTrue();
        assertThat(service.matches("secret", hash.encoded(), "ARGON2ID")).isFalse();
    }

    @Test void duplicateHandlersAreRejected() {
        assertThatThrownBy(() -> new DefaultPasswordService(List.of(argon,
            new Argon2idPasswordAlgorithmHandler(new Argon2PasswordEncoder(16, 32, 1, 19456, 2),
                19456, 2, 1))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
