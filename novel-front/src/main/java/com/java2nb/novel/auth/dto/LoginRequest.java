package com.java2nb.novel.auth.dto;

/** username is retained only for the existing PC/mobile form payloads. */
public record LoginRequest(String loginAccount, String username,
                           String password, String imageCaptcha) {
    public String account() {
        return loginAccount == null || loginAccount.isBlank() ? username : loginAccount;
    }
}
