package com.java2nb.novel.auth.captcha;

public enum CaptchaPurpose {
    REGISTER("register"), RESET_PASSWORD("reset-password"), CHANGE_EMAIL("change-email");

    private final String keyPart;

    CaptchaPurpose(String keyPart) { this.keyPart = keyPart; }

    public String keyPart() { return keyPart; }
}
