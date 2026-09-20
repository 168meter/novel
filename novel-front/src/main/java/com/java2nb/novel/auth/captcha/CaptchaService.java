package com.java2nb.novel.auth.captcha;

public interface CaptchaService {
    CaptchaIssue issue(CaptchaPurpose purpose, String normalizedEmail, String clientAddress);
    CaptchaConsumeOutcome consume(CaptchaPurpose purpose, String normalizedEmail, String code);
    void revoke(CaptchaPurpose purpose, String normalizedEmail, String code);
}
