package com.java2nb.novel.auth.security;

public enum LoginSecurityDecision {
    ALLOWED, ACCOUNT_LIMITED, CAPTCHA_REQUIRED, RATE_LIMITED, DEPENDENCY_ERROR
}
