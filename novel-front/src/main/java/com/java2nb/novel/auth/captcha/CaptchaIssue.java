package com.java2nb.novel.auth.captcha;

/** Code is for the private mail boundary, never an HTTP response or a log. */
public record CaptchaIssue(CaptchaIssueOutcome outcome, String code) {
    public CaptchaIssue {
        if (outcome == null || (outcome == CaptchaIssueOutcome.ISSUED) != (code != null)) {
            throw new IllegalArgumentException("Invalid captcha issue state.");
        }
    }

    @Override public String toString() {
        return "CaptchaIssue[outcome=" + outcome + "]";
    }
}
