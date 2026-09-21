package com.java2nb.novel.auth.token;

public interface TokenVersionService {
    boolean isCurrent(long userId, long tokenVersion);
}
