package com.java2nb.novel.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AuthPasswordPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsPasswordPolicyFromNovelAuthPasswordPrefix() {
        contextRunner
            .withPropertyValues(
                "novel.auth.password.salt-length=24",
                "novel.auth.password.hash-length=48",
                "novel.auth.password.memory-kib=32768",
                "novel.auth.password.iterations=3",
                "novel.auth.password.parallelism=2"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                AuthPasswordProperties properties = context.getBean(AuthPasswordProperties.class);
                assertThat(properties.getSaltLength()).isEqualTo(24);
                assertThat(properties.getHashLength()).isEqualTo(48);
                assertThat(properties.getMemoryKiB()).isEqualTo(32768);
                assertThat(properties.getIterations()).isEqualTo(3);
                assertThat(properties.getParallelism()).isEqualTo(2);
            });
    }

    @Test
    void usesCurrentArgon2idSecurityMinimumsByDefault() {
        AuthPasswordProperties properties = new AuthPasswordProperties();

        assertThat(properties.getSaltLength()).isEqualTo(16);
        assertThat(properties.getHashLength()).isEqualTo(32);
        assertThat(properties.getMemoryKiB()).isEqualTo(19456);
        assertThat(properties.getIterations()).isEqualTo(2);
        assertThat(properties.getParallelism()).isEqualTo(1);
    }

    @Test
    void rejectsEveryPasswordPolicyValueBelowItsSecurityMinimum() {
        List<Consumer<AuthPasswordProperties>> unsafeMutations = List.of(
            properties -> properties.setSaltLength(15),
            properties -> properties.setHashLength(31),
            properties -> properties.setMemoryKiB(19455),
            properties -> properties.setIterations(1),
            properties -> properties.setParallelism(0)
        );

        for (Consumer<AuthPasswordProperties> mutation : unsafeMutations) {
            AuthPasswordProperties properties = new AuthPasswordProperties();
            mutation.accept(properties);
            assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsArgon2WorkFactorsAboveResourceCaps() {
        List<Consumer<AuthPasswordProperties>> excessiveMutations = List.of(
            properties -> properties.setMemoryKiB(262145),
            properties -> properties.setIterations(11),
            properties -> properties.setParallelism(9)
        );

        for (Consumer<AuthPasswordProperties> mutation : excessiveMutations) {
            AuthPasswordProperties properties = new AuthPasswordProperties();
            mutation.accept(properties);
            assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsUnsafeBoundPasswordPolicyDuringContextStartup() {
        contextRunner
            .withPropertyValues("novel.auth.password.memory-kib=19455")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthPasswordProperties.class)
    static class TestConfiguration {
    }
}
