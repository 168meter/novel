package com.java2nb.novel.auth.metrics;

import com.java2nb.novel.auth.captcha.CaptchaPurpose;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static com.java2nb.novel.auth.metrics.AuthenticationMetrics.Argon2Operation.ENCODE;
import static com.java2nb.novel.auth.metrics.AuthenticationMetrics.Argon2Operation.VERIFY;
import static com.java2nb.novel.auth.metrics.AuthenticationMetrics.CaptchaRequestOutcome.ACCEPTED;
import static com.java2nb.novel.auth.metrics.AuthenticationMetrics.CaptchaVerifyOutcome.ATTEMPTS_EXHAUSTED;
import static com.java2nb.novel.auth.metrics.AuthenticationMetrics.JwtVersionOutcome.REVOKED;
import static com.java2nb.novel.auth.metrics.AuthenticationMetrics.LoginOutcome.BAD_CREDENTIALS;
import static com.java2nb.novel.auth.metrics.AuthenticationMetrics.MailOutcome.FAILED;
import static com.java2nb.novel.auth.metrics.AuthenticationMetrics.PasswordUpgradeOutcome.RACE_LOST;
import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationMetricsTest {

    @Test
    void exposesOnlyTypedLowCardinalityAuthenticationOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuthenticationMetrics metrics = new AuthenticationMetrics(registry);

        metrics.captchaRequest(CaptchaPurpose.REGISTER, ACCEPTED);
        metrics.captchaVerify(CaptchaPurpose.RESET_PASSWORD, ATTEMPTS_EXHAUSTED);
        metrics.mail(CaptchaPurpose.CHANGE_EMAIL, FAILED);
        metrics.login(BAD_CREDENTIALS);
        metrics.passwordUpgrade(RACE_LOST);
        metrics.jwtVersion(REVOKED);

        assertThat(registry.counter("novel.auth.captcha.request", "purpose", "register",
            "outcome", "accepted").count()).isEqualTo(1);
        assertThat(registry.counter("novel.auth.captcha.verify", "purpose", "reset_password",
            "outcome", "attempts_exhausted").count()).isEqualTo(1);
        assertThat(registry.counter("novel.auth.mail.delivery", "purpose", "change_email",
            "outcome", "failed").count()).isEqualTo(1);
        assertThat(registry.counter("novel.auth.login", "outcome", "bad_credentials").count()).isEqualTo(1);
        assertThat(registry.counter("novel.auth.password.upgrade", "outcome", "race_lost").count())
            .isEqualTo(1);
        assertThat(registry.counter("novel.auth.jwt.version", "outcome", "revoked").count()).isEqualTo(1);

        Set<String> permittedTags = Set.of("purpose", "outcome", "operation");
        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn(permittedTags))
                .noneSatisfy(tag -> assertThat(tag.getKey())
                    .isIn("email", "ip", "userId", "exception", "message"));
        }
    }

    @Test
    void recordsArgon2EncodeAndVerifyDurationsWithoutChangingResults() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuthenticationMetrics metrics = new AuthenticationMetrics(registry);

        assertThat(metrics.argon2(ENCODE, () -> "encoded-value")).isEqualTo("encoded-value");
        assertThat(metrics.argon2(VERIFY, () -> true)).isTrue();

        assertThat(registry.timer("novel.auth.argon2.duration", "operation", "encode").count()).isEqualTo(1);
        assertThat(registry.timer("novel.auth.argon2.duration", "operation", "verify").count()).isEqualTo(1);
    }

    @Test
    void preRegistersEveryFiniteOutcomeSoCleanStartupMetricsExist() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new AuthenticationMetrics(registry);

        assertThat(registry.find("novel.auth.captcha.request").counters()).hasSize(12);
        assertThat(registry.find("novel.auth.captcha.verify").counters()).hasSize(12);
        assertThat(registry.find("novel.auth.mail.delivery").counters()).hasSize(9);
        assertThat(registry.find("novel.auth.login").counters()).hasSize(6);
        assertThat(registry.find("novel.auth.password.upgrade").counters()).hasSize(3);
        assertThat(registry.find("novel.auth.jwt.version").counters()).hasSize(3);
        assertThat(registry.find("novel.auth.argon2.duration").timers()).hasSize(2);
    }
}
