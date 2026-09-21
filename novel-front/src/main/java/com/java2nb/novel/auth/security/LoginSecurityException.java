package com.java2nb.novel.auth.security;

public final class LoginSecurityException extends RuntimeException {
    private final LoginSecurityDecision decision;
    private final long retryAfterSeconds;
    public LoginSecurityException(LoginSecurityDecision decision) {
        this(decision, 60);
    }
    public LoginSecurityException(LoginSecurityDecision decision, long retryAfterSeconds) {
        super("Login security decision: " + decision);
        this.decision = decision;
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public LoginSecurityDecision decision() { return decision; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
