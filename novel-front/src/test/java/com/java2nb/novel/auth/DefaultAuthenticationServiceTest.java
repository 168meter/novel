package com.java2nb.novel.auth;

import com.java2nb.novel.auth.password.PasswordHash;
import com.java2nb.novel.auth.password.PasswordService;
import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.entity.User;
import com.java2nb.novel.mapper.FrontUserMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.xxyopen.web.exception.BusinessException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultAuthenticationServiceTest {
    private FrontUserMapper users;
    private PasswordService passwords;
    private DefaultAuthenticationService service;

    @BeforeEach void setUp() {
        users = mock(FrontUserMapper.class);
        passwords = mock(PasswordService.class);
        service = new DefaultAuthenticationService(users, passwords, "dummy-argon-hash");
    }

    @Test void legacyPhoneLoginUpgradesOnlyAfterSuccessfulVerification() {
        User user = user("13800138000", null, "old-hash", "MD5");
        when(users.selectAuthByLegacyUsername("13800138000")).thenReturn(Optional.of(user));
        when(passwords.matches("old-pass", "old-hash", "MD5")).thenReturn(true);
        when(passwords.needsUpgrade("old-hash", "MD5")).thenReturn(true);
        when(passwords.encode("old-pass")).thenReturn(new PasswordHash("new-hash", "ARGON2ID"));
        when(users.upgradePasswordIfCurrent(eq(7L), eq("old-hash"), eq("MD5"), eq("new-hash"), any(LocalDateTime.class))).thenReturn(1);

        UserDetails result = service.login("13800138000", "old-pass", null, "127.0.0.1").userDetails();
        assertThat(result.getId()).isEqualTo(7L);
        assertThat(result.getNickName()).isEqualTo("reader");
        verify(users).upgradePasswordIfCurrent(eq(7L), eq("old-hash"), eq("MD5"), eq("new-hash"), any(LocalDateTime.class));
    }

    @Test void migrationFailureDoesNotBlockVerifiedLogin() {
        User user = user("13800138000", null, "old-hash", "MD5");
        when(users.selectAuthByLegacyUsername("13800138000")).thenReturn(Optional.of(user));
        when(passwords.matches("old-pass", "old-hash", "MD5")).thenReturn(true);
        when(passwords.needsUpgrade("old-hash", "MD5")).thenReturn(true);
        when(passwords.encode("old-pass")).thenThrow(new IllegalStateException("db-or-hash-failure"));

        assertThat(service.login("13800138000", "old-pass", null, "127.0.0.1").userDetails().getId()).isEqualTo(7L);
    }

    @Test void emailLoginUsesArgon2WithoutRehashWhenCurrent() {
        User user = user(null, "reader@example.com", "argon-hash", "ARGON2ID");
        when(users.selectAuthByEmail("reader@example.com")).thenReturn(Optional.of(user));
        when(passwords.matches("secret", "argon-hash", "ARGON2ID")).thenReturn(true);
        UserDetails result = service.login("Reader@Example.Com", "secret", null, "127.0.0.1").userDetails();
        assertThat(result.getId()).isEqualTo(7L);
        verify(users, never()).upgradePasswordIfCurrent(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test void unknownAccountPerformsDummyVerificationAndRejects() {
        assertThatThrownBy(() -> service.login("missing@example.com", "secret", null, "127.0.0.1"))
            .isInstanceOf(BusinessException.class);
        verify(passwords).matches("secret", "dummy-argon-hash", "ARGON2ID");
    }

    @Test void dummyVerifierFailureStillReturnsGenericCredentialError() {
        when(passwords.matches("secret", "dummy-argon-hash", "ARGON2ID"))
            .thenThrow(new IllegalArgumentException("malformed dummy hash"));
        assertThatThrownBy(() -> service.login("missing@example.com", "secret", null, "127.0.0.1"))
            .isInstanceOf(BusinessException.class);
    }

    @Test void incorrectPasswordNeverUpgrades() {
        when(users.selectAuthByLegacyUsername("13800138000"))
            .thenReturn(Optional.of(user("13800138000", null, "old-hash", "MD5")));
        assertThatThrownBy(() -> service.login("13800138000", "wrong", null, "127.0.0.1"))
            .isInstanceOf(BusinessException.class);
        verify(passwords, never()).encode(anyString());
    }

    @Test void competingUpgradeDoesNotRejectVerifiedLogin() {
        when(users.selectAuthByLegacyUsername("13800138000"))
            .thenReturn(Optional.of(user("13800138000", null, "old-hash", "MD5")));
        when(passwords.matches("old-pass", "old-hash", "MD5")).thenReturn(true);
        when(passwords.needsUpgrade("old-hash", "MD5")).thenReturn(true);
        when(passwords.encode("old-pass")).thenReturn(new PasswordHash("new-hash", "ARGON2ID"));
        assertThat(service.login("13800138000", "old-pass", null, "127.0.0.1").userDetails().getId()).isEqualTo(7L);
    }

    @Test void secondLoginVerifiesTheUpgradedAlgorithm() {
        User old = user("13800138000", null, "old-hash", "MD5");
        User upgraded = user("13800138000", null, "new-hash", "ARGON2ID");
        when(users.selectAuthByLegacyUsername("13800138000"))
            .thenReturn(Optional.of(old), Optional.of(upgraded));
        when(passwords.matches("old-pass", "old-hash", "MD5")).thenReturn(true);
        when(passwords.matches("old-pass", "new-hash", "ARGON2ID")).thenReturn(true);
        when(passwords.needsUpgrade("old-hash", "MD5")).thenReturn(true);
        when(passwords.encode("old-pass")).thenReturn(new PasswordHash("new-hash", "ARGON2ID"));
        assertThat(service.login("13800138000", "old-pass", null, "127.0.0.1").userDetails().getId()).isEqualTo(7L);
        assertThat(service.login("13800138000", "old-pass", null, "127.0.0.1").userDetails().getId()).isEqualTo(7L);
        verify(passwords).matches("old-pass", "new-hash", "ARGON2ID");
    }

    @Test void unknownAlgorithmRejectsWithoutUpgrade() {
        when(users.selectAuthByLegacyUsername("13800138000"))
            .thenReturn(Optional.of(user("13800138000", null, "hash", "UNKNOWN")));
        when(passwords.matches("pass", "hash", "UNKNOWN"))
            .thenThrow(new IllegalArgumentException("unsupported"));
        assertThatThrownBy(() -> service.login("13800138000", "pass", null, "127.0.0.1"))
            .isInstanceOf(BusinessException.class);
        verify(passwords, never()).encode(anyString());
    }

    private static User user(String username, String email, String hash, String algorithm) {
        User user = new User();
        user.setId(7L);
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(hash);
        user.setPasswordAlgorithm(algorithm);
        user.setNickName("reader");
        return user;
    }
}
