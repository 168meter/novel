package com.java2nb.novel.auth;

import com.java2nb.novel.auth.captcha.CaptchaService;
import com.java2nb.novel.auth.dto.LoginRequest;
import com.java2nb.novel.auth.mail.AuthMailService;
import com.java2nb.novel.auth.password.PasswordService;
import com.java2nb.novel.auth.metrics.AuthenticationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.java2nb.novel.auth.security.*;
import com.java2nb.novel.entity.User;
import com.java2nb.novel.mapper.FrontUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginSecurityFlowTest {
    FrontUserMapper users = mock(FrontUserMapper.class);
    PasswordService passwords = mock(PasswordService.class);
    LoginSecurityService security = mock(LoginSecurityService.class);
    DefaultAuthenticationService service;
    SimpleMeterRegistry metrics;

    @BeforeEach void setUp() {
        metrics = new SimpleMeterRegistry();
        service = new DefaultAuthenticationService(users, passwords, "dummy",
            mock(CaptchaService.class), mock(AuthMailService.class), security,
            new AuthenticationMetrics(metrics));
    }

    @Test void captchaChallengeRunsBeforeAccountLookupAndPasswordVerification() {
        when(security.check("reader@example.com", "198.51.100.7"))
            .thenReturn(LoginSecurityDecision.CAPTCHA_REQUIRED);
        assertThatThrownBy(() -> service.login("reader@example.com", "password", null, "198.51.100.7"))
            .isInstanceOf(LoginSecurityException.class)
            .extracting(error -> ((LoginSecurityException) error).decision())
            .isEqualTo(LoginSecurityDecision.CAPTCHA_REQUIRED);
        verifyNoInteractions(users, passwords);
        assertThat(metrics.counter("novel.auth.login", "outcome", "captcha_required").count()).isEqualTo(1);
    }

    @Test void malformedCredentialsStillPassThroughIpRateCheckAndRecordFailure() {
        when(security.check("invalid-account", "198.51.100.7"))
            .thenReturn(LoginSecurityDecision.ALLOWED);
        assertThatThrownBy(() -> service.login(null, null, null, "198.51.100.7"));
        verify(security).check("invalid-account", "198.51.100.7");
        verify(security).recordFailure("invalid-account", "198.51.100.7");
        verify(passwords).matches("", "dummy", "ARGON2ID");
    }

    @Test void dependencyFailureAndRateLimitNeverVerifyPassword() {
        for (LoginSecurityDecision decision : new LoginSecurityDecision[]{
            LoginSecurityDecision.DEPENDENCY_ERROR, LoginSecurityDecision.RATE_LIMITED}) {
            reset(security, users, passwords);
            when(security.check("reader@example.com", "198.51.100.7")).thenReturn(decision);
            assertThatThrownBy(() -> service.login("reader@example.com", "password", null, "198.51.100.7"))
                .isInstanceOf(LoginSecurityException.class);
            verifyNoInteractions(users, passwords);
        }
        assertThat(metrics.counter("novel.auth.login", "outcome", "dependency_error").count()).isEqualTo(1);
        assertThat(metrics.counter("novel.auth.login", "outcome", "rate_limited").count()).isEqualTo(1);
    }

    @Test void failedPasswordRecordsAccountAndIpWhileSuccessClearsOnlyAccount() {
        User user = user();
        when(security.check(anyString(), anyString())).thenReturn(LoginSecurityDecision.ALLOWED);
        when(users.selectAuthByEmail("reader@example.com")).thenReturn(Optional.of(user));
        when(passwords.matches("bad", user.getPassword(), user.getPasswordAlgorithm())).thenReturn(false);
        assertThatThrownBy(() -> service.login("reader@example.com", "bad", null, "198.51.100.7"));
        verify(security).recordFailure("reader@example.com", "198.51.100.7");

        when(passwords.matches("good", user.getPassword(), user.getPasswordAlgorithm())).thenReturn(true);
        service.login("reader@example.com", "good", null, "198.51.100.7");
        verify(security).clearAccountFailures("reader@example.com");
        assertThat(metrics.counter("novel.auth.login", "outcome", "bad_credentials").count()).isEqualTo(1);
        assertThat(metrics.counter("novel.auth.login", "outcome", "success").count()).isEqualTo(1);
    }

    @Test void validHighRiskCaptchaPermitsPasswordVerification() {
        User user = user();
        when(security.check(anyString(), anyString())).thenReturn(LoginSecurityDecision.CAPTCHA_REQUIRED);
        when(security.consumeImageCaptcha("198.51.100.7", "1234")).thenReturn(true);
        when(users.selectAuthByEmail("reader@example.com")).thenReturn(Optional.of(user));
        when(passwords.matches("good", user.getPassword(), user.getPasswordAlgorithm())).thenReturn(true);
        assertThat(service.login("reader@example.com", "good", "1234", "198.51.100.7").userDetails().getId())
            .isEqualTo(7L);
    }

    @Test void absentWrongMalformedAndLimitedAccountsHaveIdenticalFailures() {
        when(security.check(anyString(), anyString())).thenReturn(LoginSecurityDecision.ALLOWED);
        when(users.selectAuthByEmail("reader@example.com")).thenReturn(Optional.empty());
        Throwable absent = catchThrowable(() ->
            service.login("reader@example.com", "password", null, "198.51.100.7"));

        reset(users, passwords, security);
        User wrongUser = user();
        when(security.check(anyString(), anyString())).thenReturn(LoginSecurityDecision.ALLOWED);
        when(users.selectAuthByEmail("reader@example.com")).thenReturn(Optional.of(wrongUser));
        when(passwords.matches(anyString(), anyString(), anyString())).thenReturn(false);
        Throwable wrong = catchThrowable(() ->
            service.login("reader@example.com", "password", null, "198.51.100.7"));

        reset(users, passwords, security);
        User malformedUser = user(); malformedUser.setPasswordAlgorithm("UNKNOWN");
        when(security.check(anyString(), anyString())).thenReturn(LoginSecurityDecision.ALLOWED);
        when(users.selectAuthByEmail("reader@example.com")).thenReturn(Optional.of(malformedUser));
        when(passwords.matches(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalArgumentException("unknown algorithm"));
        Throwable malformed = catchThrowable(() ->
            service.login("reader@example.com", "password", null, "198.51.100.7"));

        reset(users, passwords, security);
        when(security.check(anyString(), anyString())).thenReturn(LoginSecurityDecision.ACCOUNT_LIMITED);
        Throwable limited = catchThrowable(() ->
            service.login("reader@example.com", "password", null, "198.51.100.7"));

        assertThat(List.of(absent, wrong, malformed, limited))
            .allSatisfy(error -> assertThat(error).isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class));
        assertThat(List.of(absent.getMessage(), wrong.getMessage(), malformed.getMessage(), limited.getMessage()))
            .containsOnly(absent.getMessage());
    }

    private static User user() {
        User user = new User(); user.setId(7L); user.setEmail("reader@example.com");
        user.setPassword("$argon2id$hash"); user.setPasswordAlgorithm("ARGON2ID");
        user.setTokenVersion(0L); user.setNickName("Reader"); return user;
    }
}
