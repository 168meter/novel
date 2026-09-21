package com.java2nb.novel.auth.security;

public interface LoginSecurityService {
    LoginSecurityDecision check(String normalizedAccount, String clientAddress);
    void recordFailure(String normalizedAccount, String clientAddress);
    void clearAccountFailures(String normalizedAccount);
    void storeImageCaptcha(String clientAddress, String code);
    boolean consumeImageCaptcha(String clientAddress, String code);
    long retryAfterSeconds();
}
