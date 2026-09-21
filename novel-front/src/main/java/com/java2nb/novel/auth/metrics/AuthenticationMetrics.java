package com.java2nb.novel.auth.metrics;

import com.java2nb.novel.auth.captcha.CaptchaPurpose;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Typed, bounded-cardinality metrics for authentication security flows.
 * User-controlled values must never become metric names or tags.
 */
public final class AuthenticationMetrics {
    private static final String CAPTCHA_REQUEST = "novel.auth.captcha.request";
    private static final String CAPTCHA_VERIFY = "novel.auth.captcha.verify";
    private static final String MAIL_DELIVERY = "novel.auth.mail.delivery";
    private static final String LOGIN = "novel.auth.login";
    private static final String PASSWORD_UPGRADE = "novel.auth.password.upgrade";
    private static final String JWT_VERSION = "novel.auth.jwt.version";
    private static final String ARGON2_DURATION = "novel.auth.argon2.duration";

    private final MeterRegistry registry;

    public AuthenticationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        preRegisterCounters();
        for (Argon2Operation operation : Argon2Operation.values()) {
            timer(operation);
        }
    }

    public static AuthenticationMetrics noop() {
        return new AuthenticationMetrics(new SimpleMeterRegistry());
    }

    public void captchaRequest(CaptchaPurpose purpose, CaptchaRequestOutcome outcome) {
        registry.counter(CAPTCHA_REQUEST, "purpose", value(purpose), "outcome", value(outcome)).increment();
    }

    public void captchaVerify(CaptchaPurpose purpose, CaptchaVerifyOutcome outcome) {
        registry.counter(CAPTCHA_VERIFY, "purpose", value(purpose), "outcome", value(outcome)).increment();
    }

    public void mail(CaptchaPurpose purpose, MailOutcome outcome) {
        registry.counter(MAIL_DELIVERY, "purpose", value(purpose), "outcome", value(outcome)).increment();
    }

    public void login(LoginOutcome outcome) {
        registry.counter(LOGIN, "outcome", value(outcome)).increment();
    }

    public void passwordUpgrade(PasswordUpgradeOutcome outcome) {
        registry.counter(PASSWORD_UPGRADE, "outcome", value(outcome)).increment();
    }

    public void jwtVersion(JwtVersionOutcome outcome) {
        registry.counter(JWT_VERSION, "outcome", value(outcome)).increment();
    }

    public <T> T argon2(Argon2Operation operation, Supplier<T> operationBody) {
        Objects.requireNonNull(operationBody, "operationBody");
        return timer(operation).record(operationBody);
    }

    private Timer timer(Argon2Operation operation) {
        return registry.timer(ARGON2_DURATION, "operation", value(operation));
    }

    private void preRegisterCounters() {
        for (CaptchaPurpose purpose : CaptchaPurpose.values()) {
            for (CaptchaRequestOutcome outcome : CaptchaRequestOutcome.values()) {
                registry.counter(CAPTCHA_REQUEST, "purpose", value(purpose), "outcome", value(outcome));
            }
            for (CaptchaVerifyOutcome outcome : CaptchaVerifyOutcome.values()) {
                registry.counter(CAPTCHA_VERIFY, "purpose", value(purpose), "outcome", value(outcome));
            }
            for (MailOutcome outcome : MailOutcome.values()) {
                registry.counter(MAIL_DELIVERY, "purpose", value(purpose), "outcome", value(outcome));
            }
        }
        for (LoginOutcome outcome : LoginOutcome.values()) {
            registry.counter(LOGIN, "outcome", value(outcome));
        }
        for (PasswordUpgradeOutcome outcome : PasswordUpgradeOutcome.values()) {
            registry.counter(PASSWORD_UPGRADE, "outcome", value(outcome));
        }
        for (JwtVersionOutcome outcome : JwtVersionOutcome.values()) {
            registry.counter(JWT_VERSION, "outcome", value(outcome));
        }
    }

    private static String value(Enum<?> value) {
        return Objects.requireNonNull(value, "metric dimension").name().toLowerCase(Locale.ROOT);
    }

    public enum CaptchaRequestOutcome {
        ACCEPTED, EMAIL_LIMITED, IP_LIMITED, DEPENDENCY_ERROR
    }

    public enum CaptchaVerifyOutcome {
        SUCCESS, INVALID, ATTEMPTS_EXHAUSTED, EXPIRED
    }

    public enum MailOutcome {
        SUCCESS, FAILED, REJECTED
    }

    public enum LoginOutcome {
        SUCCESS, BAD_CREDENTIALS, ACCOUNT_LIMITED, CAPTCHA_REQUIRED, DEPENDENCY_ERROR, RATE_LIMITED
    }

    public enum PasswordUpgradeOutcome {
        SUCCESS, RACE_LOST, FAILED
    }

    public enum JwtVersionOutcome {
        VALID, REVOKED, DEPENDENCY_ERROR
    }

    public enum Argon2Operation {
        ENCODE, VERIFY
    }
}
