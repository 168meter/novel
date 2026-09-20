package com.java2nb.novel.auth;

import com.java2nb.novel.auth.model.AuthenticationResult;
import com.java2nb.novel.auth.password.PasswordHash;
import com.java2nb.novel.auth.password.PasswordService;
import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.enums.ResponseStatus;
import com.java2nb.novel.entity.User;
import com.java2nb.novel.mapper.FrontUserMapper;
import io.github.xxyopen.web.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class DefaultAuthenticationService implements AuthenticationService {
    private final FrontUserMapper users;
    private final PasswordService passwords;
    private final String dummyHash;

    @Autowired
    public DefaultAuthenticationService(FrontUserMapper users, PasswordService passwords) {
        this(users, passwords, passwords.encode("anonymous-account-dummy-password").encoded());
    }

    DefaultAuthenticationService(FrontUserMapper users, PasswordService passwords, String dummyHash) {
        this.users = users;
        this.passwords = passwords;
        this.dummyHash = dummyHash;
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
