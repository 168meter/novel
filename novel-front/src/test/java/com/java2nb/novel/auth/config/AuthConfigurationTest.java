package com.java2nb.novel.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.java2nb.novel.auth.metrics.AuthenticationMetrics;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class AuthConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(AuthConfiguration.class)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void createsValidatedAuthenticationPrimitivesAndMetricsEntryPoint() {
        contextRunner
            .withPropertyValues(
                "jwt.secret=unit-test-jwt-secret",
                "novel.auth.hmac-secret=unit-test-hmac-secret"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SecureRandom.class);
                assertThat(context).hasSingleBean(Argon2PasswordEncoder.class);

                Argon2PasswordEncoder encoder = context.getBean(Argon2PasswordEncoder.class);
                String encoded = encoder.encode("correct horse battery staple");
                assertThat(encoder.matches("correct horse battery staple", encoded)).isTrue();
                assertThat(context).hasSingleBean(AuthenticationMetrics.class);
            });
    }

    @Test
    void refusesToStartWithoutJwtSecret() {
        contextRunner
            .withPropertyValues("novel.auth.hmac-secret=unit-test-hmac-secret")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("JWT_SECRET");
            });
    }

    @Test
    void refusesToStartWithoutAuthenticationHmacSecret() {
        contextRunner
            .withPropertyValues("jwt.secret=unit-test-jwt-secret")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("NOVEL_AUTH_HMAC_SECRET");
            });
    }
}
