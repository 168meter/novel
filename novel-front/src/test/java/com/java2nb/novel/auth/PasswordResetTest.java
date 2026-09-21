package com.java2nb.novel.auth;

import com.java2nb.novel.auth.captcha.*;
import com.java2nb.novel.auth.dto.*;
import com.java2nb.novel.auth.mail.AuthMailService;
import com.java2nb.novel.auth.password.*;
import com.java2nb.novel.entity.User;
import com.java2nb.novel.mapper.FrontUserMapper;
import io.github.xxyopen.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetTest {
    FrontUserMapper users = mock(FrontUserMapper.class);
    PasswordService passwords = mock(PasswordService.class);
    CaptchaService captcha = mock(CaptchaService.class);
    AuthMailService mail = mock(AuthMailService.class);
    DefaultAuthenticationService service = new DefaultAuthenticationService(users, passwords, "dummy", captcha, mail);

    @Test void absentEmailUsesSamePublicCodeResponseWithoutSendingMail() {
        when(captcha.issue(CaptchaPurpose.RESET_PASSWORD, "reader@example.com", "127.0.0.1"))
            .thenReturn(new CaptchaIssue(CaptchaIssueOutcome.ISSUED, "123456"));
        when(mail.submit(CaptchaPurpose.RESET_PASSWORD, "reader@example.com", null, false)).thenReturn(true);
        assertThat(service.requestPasswordResetCode("reader@example.com", "127.0.0.1"))
            .isEqualTo(EmailCodeRequestOutcome.ACCEPTED);
        verify(mail).submit(CaptchaPurpose.RESET_PASSWORD, "reader@example.com", null, false);
    }

    @Test void resetConsumesCodeAndAtomicallyIncrementsVersion() {
        User user = new User(); user.setId(7L); user.setPassword("old-md5");
        user.setPasswordAlgorithm("MD5"); user.setTokenVersion(2L);
        when(captcha.consume(CaptchaPurpose.RESET_PASSWORD, "reader@example.com", "123456"))
            .thenReturn(CaptchaConsumeOutcome.CONSUMED);
        when(users.selectAuthByEmail("reader@example.com")).thenReturn(Optional.of(user));
        when(passwords.encode("new-password-123")).thenReturn(new PasswordHash("$argon2id$new", "ARGON2ID"));
        when(users.replacePasswordAndIncrementVersion(eq(7L), eq("old-md5"), eq(2L), eq("$argon2id$new"), any()))
            .thenReturn(1);
        service.resetPassword(new PasswordResetRequest("reader@example.com", "123456", "new-password-123", "new-password-123"));
        verify(users).replacePasswordAndIncrementVersion(eq(7L), eq("old-md5"), eq(2L), eq("$argon2id$new"), any());
    }

    @Test void invalidCodeCannotWritePassword() {
        when(captcha.consume(CaptchaPurpose.RESET_PASSWORD, "reader@example.com", "123456"))
            .thenReturn(CaptchaConsumeOutcome.INVALID);
        assertThatThrownBy(() -> service.resetPassword(new PasswordResetRequest("reader@example.com", "123456", "new-password-123", "new-password-123")))
            .isInstanceOf(BusinessException.class);
        verify(users, never()).replacePasswordAndIncrementVersion(anyLong(), anyString(), anyLong(), anyString(), any());
    }

    @Test void changeRequiresOldPasswordAndReturnsNewVersion() {
        User user = new User(); user.setId(7L); user.setNickName("Reader"); user.setPassword("$argon2id$old");
        user.setPasswordAlgorithm("ARGON2ID"); user.setTokenVersion(2L);
        when(users.selectAuthById(7L)).thenReturn(Optional.of(user));
        when(passwords.matches("old-password", "$argon2id$old", "ARGON2ID")).thenReturn(true);
        when(passwords.encode("new-password-123")).thenReturn(new PasswordHash("$argon2id$new", "ARGON2ID"));
        when(users.replacePasswordAndIncrementVersion(eq(7L), eq("$argon2id$old"), eq(2L), eq("$argon2id$new"), any()))
            .thenReturn(1);
        var result = service.changePassword(7L, new PasswordChangeRequest("old-password", "new-password-123", "new-password-123"));
        assertThat(result.userDetails().getTokenVersion()).isEqualTo(3L);
    }
}
