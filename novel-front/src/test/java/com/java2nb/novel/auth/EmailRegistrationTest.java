package com.java2nb.novel.auth;

import com.java2nb.novel.auth.captcha.*;
import com.java2nb.novel.auth.dto.RegisterRequest;
import com.java2nb.novel.auth.dto.EmailCodeRequest;
import com.java2nb.novel.auth.mail.AuthMailService;
import com.java2nb.novel.auth.password.PasswordHash;
import com.java2nb.novel.auth.password.PasswordService;
import com.java2nb.novel.entity.User;
import com.java2nb.novel.mapper.FrontUserMapper;
import io.github.xxyopen.web.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailRegistrationTest {
    private FrontUserMapper users;
    private PasswordService passwords;
    private CaptchaService captcha;
    private AuthMailService mail;
    private DefaultAuthenticationService service;

    @BeforeEach void setUp() {
        users = mock(FrontUserMapper.class);
        passwords = mock(PasswordService.class);
        captcha = mock(CaptchaService.class);
        mail = mock(AuthMailService.class);
        service = new DefaultAuthenticationService(users, passwords, "dummy-hash", captcha, mail);
    }

    @Test void validCodeRequestSendsToNormalizedEmail() {
        when(captcha.issue(CaptchaPurpose.REGISTER, "reader@example.com", "127.0.0.1"))
            .thenReturn(new CaptchaIssue(CaptchaIssueOutcome.ISSUED, "123456"));
        when(mail.submit(CaptchaPurpose.REGISTER, "reader@example.com", "123456", true)).thenReturn(true);

        assertThat(service.requestRegistrationCode(" Reader@Example.COM ", "127.0.0.1"))
            .isEqualTo(EmailCodeRequestOutcome.ACCEPTED);
        verify(mail).submit(CaptchaPurpose.REGISTER, "reader@example.com", "123456", true);
    }

    @Test void existingEmailUsesNoMailTaskAndSamePublicResult() {
        User existing = new User();
        when(users.selectAuthByEmail("reader@example.com")).thenReturn(Optional.of(existing));
        when(captcha.issue(CaptchaPurpose.REGISTER, "reader@example.com", "127.0.0.1"))
            .thenReturn(new CaptchaIssue(CaptchaIssueOutcome.ISSUED, "123456"));
        when(mail.submit(CaptchaPurpose.REGISTER, "reader@example.com", null, false)).thenReturn(true);

        assertThat(service.requestRegistrationCode("reader@example.com", "127.0.0.1"))
            .isEqualTo(EmailCodeRequestOutcome.ACCEPTED);
        verify(mail).submit(CaptchaPurpose.REGISTER, "reader@example.com", null, false);
    }

    @Test void cooldownIsLimitedWithoutSubmittingMail() {
        when(captcha.issue(CaptchaPurpose.REGISTER, "reader@example.com", "127.0.0.1"))
            .thenReturn(new CaptchaIssue(CaptchaIssueOutcome.COOLDOWN, null));
        assertThat(service.requestRegistrationCode("reader@example.com", "127.0.0.1"))
            .isEqualTo(EmailCodeRequestOutcome.EMAIL_LIMITED);
        verifyNoInteractions(mail);
    }

    @Test void ipQuotaIsDistinctFromEmailQuotaWithoutRevealingAccountState() {
        when(captcha.issue(CaptchaPurpose.REGISTER, "reader@example.com", "127.0.0.1"))
            .thenReturn(new CaptchaIssue(CaptchaIssueOutcome.IP_LIMITED, null));
        assertThat(service.requestRegistrationCode("reader@example.com", "127.0.0.1"))
            .isEqualTo(EmailCodeRequestOutcome.IP_LIMITED);
        verifyNoInteractions(mail);
    }

    @Test void rejectedQueueRevokesTheIssuedCode() {
        when(captcha.issue(CaptchaPurpose.REGISTER, "reader@example.com", "127.0.0.1"))
            .thenReturn(new CaptchaIssue(CaptchaIssueOutcome.ISSUED, "123456"));
        assertThat(service.requestRegistrationCode("reader@example.com", "127.0.0.1"))
            .isEqualTo(EmailCodeRequestOutcome.UNAVAILABLE);
        verify(captcha).revoke(CaptchaPurpose.REGISTER, "reader@example.com", "123456");
    }

    @Test void consumesRegisterCodeAndWritesOnlyArgon2Identity() {
        when(captcha.consume(CaptchaPurpose.REGISTER, "reader@example.com", "123456"))
            .thenReturn(CaptchaConsumeOutcome.CONSUMED);
        when(passwords.encode("password123")).thenReturn(new PasswordHash("$argon2id$encoded", "ARGON2ID"));
        when(users.insertSelective(any(User.class))).thenReturn(1);

        var result = service.register(new RegisterRequest(" Reader@Example.COM ", "123456", "password123", "password123"));
        ArgumentCaptor<User> inserted = ArgumentCaptor.forClass(User.class);
        verify(users).insertSelective(inserted.capture());
        User user = inserted.getValue();
        assertThat(user.getEmail()).isEqualTo("reader@example.com");
        assertThat(user.getUsername()).isNull();
        assertThat(user.getPassword()).isEqualTo("$argon2id$encoded");
        assertThat(user.getPasswordAlgorithm()).isEqualTo("ARGON2ID");
        assertThat(user.getTokenVersion()).isZero();
        assertThat(user.getEmailVerifiedAt()).isBeforeOrEqualTo(LocalDateTime.now());
        assertThat(user.getNickName()).doesNotContain("reader", "@", ".com");
        assertThat(result.userDetails().getUsername()).isNull();
        assertThat(result.userDetails().getNickName()).isEqualTo(user.getNickName());
    }

    @Test void wrongPurposeCodeCannotRegister() {
        when(captcha.consume(CaptchaPurpose.REGISTER, "reader@example.com", "123456"))
            .thenReturn(CaptchaConsumeOutcome.EXPIRED);
        assertThatThrownBy(() -> service.register(validRequest())).isInstanceOf(BusinessException.class);
        verify(users, never()).insertSelective(any(User.class));
    }

    @Test void invalidPasswordLengthsAndMismatchNeverConsumeCode() {
        for (String password : new String[]{"1234567", "x".repeat(65)}) {
            assertThatThrownBy(() -> service.register(new RegisterRequest("reader@example.com", "123456", password, password)))
                .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.register(new RegisterRequest("reader@example.com", "123456", "password123", "different123")))
            .isInstanceOf(BusinessException.class);
        verifyNoInteractions(captcha);
    }

    @Test void uniquenessRaceReturnsGenericFailureAfterCodeWasConsumed() {
        when(captcha.consume(CaptchaPurpose.REGISTER, "reader@example.com", "123456"))
            .thenReturn(CaptchaConsumeOutcome.CONSUMED);
        when(passwords.encode("password123")).thenReturn(new PasswordHash("$argon2id$encoded", "ARGON2ID"));
        when(users.insertSelective(any(User.class))).thenThrow(new DuplicateKeyException("private email reader@example.com"));
        assertThatThrownBy(() -> service.register(validRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining("reader@example.com");
        verify(captcha, never()).revoke(any(), anyString(), anyString());
    }

    @Test void registrationBoundaryIsTransactional() throws Exception {
        assertThat(DefaultAuthenticationService.class.getMethod("register", RegisterRequest.class)
            .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test void registrationServiceCanBeClassProxiedForTransactions() {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
        assertThat(factory.getProxy()).isInstanceOf(AuthenticationService.class);
    }

    @Test void oldPhoneOnlyRegistrationCannotCreateAnAccount() {
        assertThatThrownBy(() -> service.register(new RegisterRequest(null, null, "password123", "password123")))
            .isInstanceOf(BusinessException.class);
        verifyNoInteractions(captcha, passwords);
        verify(users, never()).insertSelective(any(User.class));
    }

    @Test void requestRecordsDoNotPrintEmailPasswordOrCode() {
        assertThat(new RegisterRequest("reader@example.com", "123456", "password123", "password123").toString())
            .doesNotContain("reader@example.com", "123456", "password123");
        assertThat(new EmailCodeRequest("reader@example.com").toString())
            .doesNotContain("reader@example.com");
    }

    private static RegisterRequest validRequest() {
        return new RegisterRequest("reader@example.com", "123456", "password123", "password123");
    }
}
