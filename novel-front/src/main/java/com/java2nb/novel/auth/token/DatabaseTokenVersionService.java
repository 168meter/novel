package com.java2nb.novel.auth.token;

import com.java2nb.novel.mapper.FrontUserMapper;
import org.springframework.stereotype.Service;

@Service
public class DatabaseTokenVersionService implements TokenVersionService {
    private final FrontUserMapper users;

    public DatabaseTokenVersionService(FrontUserMapper users) { this.users = users; }

    @Override public boolean isCurrent(long userId, long tokenVersion) {
        if (userId <= 0 || tokenVersion < 0) return false;
        try {
            var actual = users.selectTokenVersion(userId);
            return actual.isPresent() && actual.getAsLong() == tokenVersion;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
