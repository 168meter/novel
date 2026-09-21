package com.java2nb.novel.auth;

import com.java2nb.novel.auth.model.AuthenticationResult;
import com.java2nb.novel.auth.captcha.CaptchaConsumeOutcome;
import com.java2nb.novel.auth.captcha.CaptchaIssue;
import com.java2nb.novel.auth.captcha.CaptchaIssueOutcome;
import com.java2nb.novel.auth.captcha.CaptchaPurpose;
import com.java2nb.novel.auth.captcha.CaptchaService;
import com.java2nb.novel.auth.dto.RegisterRequest;
import com.java2nb.novel.auth.dto.PasswordResetRequest;
import com.java2nb.novel.auth.dto.PasswordChangeRequest;
import com.java2nb.novel.auth.mail.AuthMailService;
import com.java2nb.novel.auth.password.PasswordHash;
import com.java2nb.novel.auth.password.PasswordService;
import com.java2nb.novel.auth.security.LoginSecurityDecision;
import com.java2nb.novel.auth.security.LoginSecurityException;
import com.java2nb.novel.auth.security.LoginSecurityService;
import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.enums.ResponseStatus;
import com.java2nb.novel.entity.User;
import com.java2nb.novel.mapper.FrontUserMapper;
import io.github.xxyopen.web.exception.BusinessException;
import io.github.xxyopen.util.IdWorker;
import java.util.Date;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultAuthenticationService implements AuthenticationService {
    private final FrontUserMapper users;
    private final PasswordService passwords;
    private final String dummyHash;
    private final CaptchaService captcha;
    private final AuthMailService mail;
    private final LoginSecurityService loginSecurity;
    private final IdWorker idWorker = IdWorker.INSTANCE;

    @Autowired
    public DefaultAuthenticationService(FrontUserMapper users, PasswordService passwords,
                                        CaptchaService captcha, AuthMailService mail,
                                        LoginSecurityService loginSecurity) {
        this(users, passwords, passwords.encode("anonymous-account-dummy-password").encoded(), captcha, mail,
            loginSecurity);
    }

    DefaultAuthenticationService(FrontUserMapper users, PasswordService passwords, String dummyHash) {
        this(users, passwords, dummyHash, null, null, permissiveSecurity());
    }

    DefaultAuthenticationService(FrontUserMapper users, PasswordService passwords, String dummyHash,
                                 CaptchaService captcha, AuthMailService mail) {
        this(users, passwords, dummyHash, captcha, mail, permissiveSecurity());
    }

    DefaultAuthenticationService(FrontUserMapper users, PasswordService passwords, String dummyHash,
                                 CaptchaService captcha, AuthMailService mail,
                                 LoginSecurityService loginSecurity) {
        this.users = users;
        this.passwords = passwords;
        this.dummyHash = dummyHash;
        this.captcha = captcha;
        this.mail = mail;
        this.loginSecurity = loginSecurity;
    }

    @Override
    public EmailCodeRequestOutcome requestRegistrationCode(String email, String clientAddress) {
        return requestCode(CaptchaPurpose.REGISTER, email, clientAddress);
    }

    @Override
    public EmailCodeRequestOutcome requestPasswordResetCode(String email, String clientAddress) {
        return requestCode(CaptchaPurpose.RESET_PASSWORD, email, clientAddress);
    }

    private EmailCodeRequestOutcome requestCode(CaptchaPurpose purpose, String email, String clientAddress) {
        final String normalized;
        try { normalized = EmailNormalizer.normalize(email); }
        catch (IllegalArgumentException invalid) { throw new BusinessException(ResponseStatus.AUTH_INVALID_REQUEST); }
        if (clientAddress == null || clientAddress.isBlank()) {
            return EmailCodeRequestOutcome.UNAVAILABLE;
        }
        CaptchaIssue issued;
        try {
            issued = captcha.issue(purpose, normalized, clientAddress);
        } catch (RuntimeException dependencyFailure) {
            return EmailCodeRequestOutcome.UNAVAILABLE;
        }
        if (issued.outcome() == CaptchaIssueOutcome.IP_LIMITED) return EmailCodeRequestOutcome.IP_LIMITED;
        if (issued.outcome() != CaptchaIssueOutcome.ISSUED) return EmailCodeRequestOutcome.EMAIL_LIMITED;
        try {
            boolean exists = users.selectAuthByEmail(normalized).isPresent();
            boolean deliver = purpose == CaptchaPurpose.REGISTER ? !exists : exists;
            boolean accepted = mail.submit(purpose, normalized,
                deliver ? issued.code() : null, deliver);
            if (accepted) return EmailCodeRequestOutcome.ACCEPTED;
        } catch (RuntimeException dependencyFailure) {
            // No account-state or dependency details enter the public response.
        }
        try { captcha.revoke(purpose, normalized, issued.code()); }
        catch (RuntimeException ignored) { /* Redis is already unavailable. */ }
        return EmailCodeRequestOutcome.UNAVAILABLE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthenticationResult register(RegisterRequest request) {
        if (request == null || request.password() == null || request.password().length() < 8
            || request.password().length() > 64 || request.confirmPassword() == null
            || !request.password().equals(request.confirmPassword()) || request.code() == null
            || !request.code().matches("[0-9]{6}")) {
            throw new BusinessException(ResponseStatus.AUTH_INVALID_REQUEST);
        }
        final String normalized;
        try { normalized = EmailNormalizer.normalize(request.email()); }
        catch (IllegalArgumentException invalid) { throw new BusinessException(ResponseStatus.AUTH_INVALID_REQUEST); }
        final CaptchaConsumeOutcome consumed;
        try { consumed = captcha.consume(CaptchaPurpose.REGISTER, normalized, request.code()); }
        catch (RuntimeException unavailable) { throw new BusinessException(ResponseStatus.AUTH_UNAVAILABLE); }
        if (consumed != CaptchaConsumeOutcome.CONSUMED) {
            throw new BusinessException(ResponseStatus.AUTH_INVALID_CODE);
        }
        try {
            if (users.selectAuthByEmail(normalized).isPresent()) {
                throw new BusinessException(ResponseStatus.AUTH_UNAVAILABLE);
            }
            PasswordHash hash = passwords.encode(request.password());
            if (!"ARGON2ID".equals(hash.algorithm())) throw new IllegalStateException("Unexpected password algorithm");
            long id = idWorker.nextId();
            User user = new User();
            user.setId(id);
            user.setUsername(null);
            user.setEmail(normalized);
            user.setPassword(hash.encoded());
            user.setPasswordAlgorithm(hash.algorithm());
            user.setTokenVersion(0L);
            user.setEmailVerifiedAt(LocalDateTime.now());
            user.setNickName("读者" + id);
            Date now = new Date();
            user.setCreateTime(now);
            user.setUpdateTime(now);
            if (users.insertSelective(user) != 1) throw new IllegalStateException("User insert failed");
            UserDetails details = new UserDetails();
            details.setId(id);
            details.setNickName(user.getNickName());
            details.setTokenVersion(0L);
            return new AuthenticationResult(details);
        } catch (RuntimeException persistenceFailure) {
            throw new BusinessException(ResponseStatus.AUTH_UNAVAILABLE);
        }
    }

    @Override
    public AuthenticationResult login(String account, String rawPassword, String imageCaptcha,
                                      String clientAddress) {
        String normalized = account == null ? "" : account.trim();
        if (normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        if (clientAddress == null || clientAddress.isBlank()) {
            throw new LoginSecurityException(LoginSecurityDecision.DEPENDENCY_ERROR);
        }
        String securityAccount = normalized.isBlank() || normalized.length() > 254
            ? "invalid-account" : normalized;
        LoginSecurityDecision decision = loginSecurity.check(securityAccount, clientAddress);
        if (decision == LoginSecurityDecision.DEPENDENCY_ERROR
            || decision == LoginSecurityDecision.RATE_LIMITED) {
            throw decision == LoginSecurityDecision.RATE_LIMITED
                ? new LoginSecurityException(decision, loginSecurity.retryAfterSeconds())
                : new LoginSecurityException(decision);
        }
        if (decision == LoginSecurityDecision.ACCOUNT_LIMITED) throw badCredentials();
        if (decision == LoginSecurityDecision.CAPTCHA_REQUIRED
            && (imageCaptcha == null || !loginSecurity.consumeImageCaptcha(clientAddress, imageCaptcha))) {
            throw new LoginSecurityException(LoginSecurityDecision.CAPTCHA_REQUIRED);
        }
        if (normalized.isBlank() || normalized.length() > 254 || rawPassword == null
            || rawPassword.isBlank() || rawPassword.length() > 1024) {
            try { passwords.matches(rawPassword == null ? "" : rawPassword, dummyHash, "ARGON2ID"); }
            catch (RuntimeException ignored) { }
            recordFailure(securityAccount, clientAddress);
            throw badCredentials();
        }
        Optional<User> found;
        if (normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            found = users.selectAuthByEmail(normalized);
        } else if (normalized.matches("^1\\d{10}$")) {
            found = users.selectAuthByLegacyUsername(normalized);
        } else {
            found = Optional.empty();
        }
        if (found.isEmpty()) {
            try {
                passwords.matches(rawPassword, dummyHash, "ARGON2ID");
            } catch (RuntimeException ignored) {
                // The same public error is returned even when the verifier itself fails.
            }
            recordFailure(normalized, clientAddress);
            throw badCredentials();
        }
        User user = found.orElseThrow();
        // Unknown algorithms and malformed hashes must fail closed.
        try {
            if (!passwords.matches(rawPassword, user.getPassword(), user.getPasswordAlgorithm())) {
                recordFailure(normalized, clientAddress);
                throw badCredentials();
            }
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException || ex instanceof LoginSecurityException) throw ex;
            recordFailure(normalized, clientAddress);
            throw badCredentials();
        }
        try { loginSecurity.clearAccountFailures(normalized); }
        catch (RuntimeException unavailable) {
            throw new LoginSecurityException(LoginSecurityDecision.DEPENDENCY_ERROR);
        }
        try {
            if (passwords.needsUpgrade(user.getPassword(), user.getPasswordAlgorithm())) {
                PasswordHash upgraded = passwords.encode(rawPassword);
                users.upgradePasswordIfCurrent(user.getId(), user.getPassword(), user.getPasswordAlgorithm(),
                    upgraded.encoded(), LocalDateTime.now());
            }
        } catch (RuntimeException ex) {
            // A verified legacy user can still log in when best-effort rehashing fails.
        }
        UserDetails details = new UserDetails();
        details.setId(user.getId());
        details.setUsername(user.getUsername());
        details.setNickName(user.getNickName());
        details.setTokenVersion(user.getTokenVersion());
        return new AuthenticationResult(details);
    }

    private void recordFailure(String normalizedAccount, String clientAddress) {
        try { loginSecurity.recordFailure(normalizedAccount, clientAddress); }
        catch (RuntimeException unavailable) {
            throw new LoginSecurityException(LoginSecurityDecision.DEPENDENCY_ERROR);
        }
    }

    private static LoginSecurityService permissiveSecurity() {
        return new LoginSecurityService() {
            public LoginSecurityDecision check(String account, String client) { return LoginSecurityDecision.ALLOWED; }
            public void recordFailure(String account, String client) { }
            public void clearAccountFailures(String account) { }
            public void storeImageCaptcha(String client, String code) { }
            public boolean consumeImageCaptcha(String client, String code) { return true; }
            public long retryAfterSeconds() { return 60; }
        };
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(PasswordResetRequest request) {
        if (request == null || !validNewPassword(request.password(), request.confirmPassword())
            || request.code() == null || !request.code().matches("[0-9]{6}")) {
            throw new BusinessException(ResponseStatus.AUTH_INVALID_REQUEST);
        }
        final String email;
        try { email = EmailNormalizer.normalize(request.email()); }
        catch (IllegalArgumentException invalid) { throw new BusinessException(ResponseStatus.AUTH_INVALID_REQUEST); }
        final CaptchaConsumeOutcome outcome;
        try { outcome = captcha.consume(CaptchaPurpose.RESET_PASSWORD, email, request.code()); }
        catch (RuntimeException unavailable) { throw new BusinessException(ResponseStatus.AUTH_UNAVAILABLE); }
        if (outcome != CaptchaConsumeOutcome.CONSUMED) {
            throw new BusinessException(ResponseStatus.AUTH_INVALID_CODE);
        }
        try {
            User user = users.selectAuthByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Reset account unavailable"));
            replacePassword(user, request.password());
            loginSecurity.clearAccountFailures(email);
        } catch (RuntimeException persistenceFailure) {
            throw new BusinessException(ResponseStatus.AUTH_UNAVAILABLE);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthenticationResult changePassword(long userId, PasswordChangeRequest request) {
        if (request == null || request.oldPassword() == null || request.oldPassword().isBlank()
            || request.oldPassword().length() > 1024
            || !validNewPassword(request.newPassword1(), request.newPassword2())) {
            throw new BusinessException(ResponseStatus.AUTH_INVALID_REQUEST);
        }
        final User user;
        try { user = users.selectAuthById(userId).orElseThrow(); }
        catch (RuntimeException unavailable) { throw new BusinessException(ResponseStatus.AUTH_UNAVAILABLE); }
        boolean matched;
        try { matched = passwords.matches(request.oldPassword(), user.getPassword(), user.getPasswordAlgorithm()); }
        catch (RuntimeException invalid) { matched = false; }
        if (!matched) throw new BusinessException(ResponseStatus.OLD_PASSWORD_ERROR);
        try {
            replacePassword(user, request.newPassword1());
        } catch (RuntimeException persistenceFailure) {
            throw new BusinessException(ResponseStatus.AUTH_UNAVAILABLE);
        }
        UserDetails details = new UserDetails();
        details.setId(userId);
        details.setNickName(user.getNickName());
        details.setTokenVersion(user.getTokenVersion() + 1);
        return new AuthenticationResult(details);
    }

    @Override
    public String legacyUsername(long userId) {
        return users.selectLegacyUsernameById(userId);
    }

    private void replacePassword(User user, String rawPassword) {
        PasswordHash hash = passwords.encode(rawPassword);
        if (!"ARGON2ID".equals(hash.algorithm())) throw new IllegalStateException("Unexpected password algorithm");
        if (users.replacePasswordAndIncrementVersion(user.getId(), user.getPassword(), user.getTokenVersion(),
            hash.encoded(), LocalDateTime.now()) != 1) {
            throw new IllegalStateException("Concurrent password update");
        }
    }

    private static boolean validNewPassword(String password, String confirmation) {
        return password != null && password.length() >= 8 && password.length() <= 64
            && password.equals(confirmation);
    }

    private static BusinessException badCredentials() {
        return new BusinessException(ResponseStatus.USERNAME_PASS_ERROR);
    }
}
