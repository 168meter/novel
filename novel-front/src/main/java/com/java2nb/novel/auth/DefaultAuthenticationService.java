package com.java2nb.novel.auth;

import com.java2nb.novel.auth.model.AuthenticationResult;
import com.java2nb.novel.auth.captcha.CaptchaConsumeOutcome;
import com.java2nb.novel.auth.captcha.CaptchaIssue;
import com.java2nb.novel.auth.captcha.CaptchaIssueOutcome;
import com.java2nb.novel.auth.captcha.CaptchaPurpose;
import com.java2nb.novel.auth.captcha.CaptchaService;
import com.java2nb.novel.auth.dto.RegisterRequest;
import com.java2nb.novel.auth.mail.AuthMailService;
import com.java2nb.novel.auth.password.PasswordHash;
import com.java2nb.novel.auth.password.PasswordService;
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
    private final IdWorker idWorker = IdWorker.INSTANCE;

    @Autowired
    public DefaultAuthenticationService(FrontUserMapper users, PasswordService passwords,
                                        CaptchaService captcha, AuthMailService mail) {
        this(users, passwords, passwords.encode("anonymous-account-dummy-password").encoded(), captcha, mail);
    }

    DefaultAuthenticationService(FrontUserMapper users, PasswordService passwords, String dummyHash) {
        this(users, passwords, dummyHash, null, null);
    }

    DefaultAuthenticationService(FrontUserMapper users, PasswordService passwords, String dummyHash,
                                 CaptchaService captcha, AuthMailService mail) {
        this.users = users;
        this.passwords = passwords;
        this.dummyHash = dummyHash;
        this.captcha = captcha;
        this.mail = mail;
    }

    @Override
    public EmailCodeRequestOutcome requestRegistrationCode(String email, String clientAddress) {
        final String normalized;
        try { normalized = EmailNormalizer.normalize(email); }
        catch (IllegalArgumentException invalid) { throw new BusinessException(ResponseStatus.AUTH_INVALID_REQUEST); }
        if (clientAddress == null || clientAddress.isBlank()) {
            return EmailCodeRequestOutcome.UNAVAILABLE;
        }
        CaptchaIssue issued;
        try {
            issued = captcha.issue(CaptchaPurpose.REGISTER, normalized, clientAddress);
        } catch (RuntimeException dependencyFailure) {
            return EmailCodeRequestOutcome.UNAVAILABLE;
        }
        if (issued.outcome() == CaptchaIssueOutcome.IP_LIMITED) return EmailCodeRequestOutcome.IP_LIMITED;
        if (issued.outcome() != CaptchaIssueOutcome.ISSUED) return EmailCodeRequestOutcome.EMAIL_LIMITED;
        try {
            boolean deliver = users.selectAuthByEmail(normalized).isEmpty();
            boolean accepted = mail.submit(CaptchaPurpose.REGISTER, normalized,
                deliver ? issued.code() : null, deliver);
            if (accepted) return EmailCodeRequestOutcome.ACCEPTED;
        } catch (RuntimeException dependencyFailure) {
            // No account-state or dependency details enter the public response.
        }
        try { captcha.revoke(CaptchaPurpose.REGISTER, normalized, issued.code()); }
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
            return new AuthenticationResult(details);
        } catch (RuntimeException persistenceFailure) {
            throw new BusinessException(ResponseStatus.AUTH_UNAVAILABLE);
        }
    }

    @Override
    public AuthenticationResult login(String account, String rawPassword) {
        if (account == null || account.isBlank() || account.length() > 254
            || rawPassword == null || rawPassword.isBlank() || rawPassword.length() > 1024) {
            throw badCredentials();
        }
        String normalized = account.trim();
        Optional<User> found;
        if (normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            found = users.selectAuthByEmail(normalized.toLowerCase(Locale.ROOT));
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
            throw badCredentials();
        }
        User user = found.orElseThrow();
        // Unknown algorithms and malformed hashes must fail closed.
        try {
            if (!passwords.matches(rawPassword, user.getPassword(), user.getPasswordAlgorithm())) {
                throw badCredentials();
            }
        } catch (RuntimeException ex) {
            throw badCredentials();
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
        return new AuthenticationResult(details);
    }

    private static BusinessException badCredentials() {
        return new BusinessException(ResponseStatus.USERNAME_PASS_ERROR);
    }
}
