package com.java2nb.novel.auth.mail;

import com.java2nb.novel.auth.captcha.CaptchaPurpose;

public interface AuthMailService {
    /** A false result means the bounded delivery queue rejected the task. */
    boolean submit(CaptchaPurpose purpose, String normalizedEmail, String code, boolean deliver);
}
