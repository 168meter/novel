package com.java2nb.novel.auth.token;

import com.java2nb.novel.mapper.FrontUserMapper;
import com.java2nb.novel.auth.metrics.AuthenticationMetrics;
import com.java2nb.novel.auth.metrics.AuthenticationMetrics.JwtVersionOutcome;
import org.springframework.stereotype.Service;

@Service
public class DatabaseTokenVersionService implements TokenVersionService {
    private final FrontUserMapper users;
    private final AuthenticationMetrics metrics;

    public DatabaseTokenVersionService(FrontUserMapper users, AuthenticationMetrics metrics) {
        this.users = users;
        this.metrics = metrics;
    }

    @Override public boolean isCurrent(long userId, long tokenVersion) {
        if (userId <= 0 || tokenVersion < 0) {
            metrics.jwtVersion(JwtVersionOutcome.REVOKED);
            return false;
        }
        try {
            var actual = users.selectTokenVersion(userId);
            boolean current = actual.isPresent() && actual.getAsLong() == tokenVersion;
            metrics.jwtVersion(current ? JwtVersionOutcome.VALID : JwtVersionOutcome.REVOKED);
            return current;
        } catch (RuntimeException unavailable) {
            metrics.jwtVersion(JwtVersionOutcome.DEPENDENCY_ERROR);
            return false;
        }
    }
}
