package com.java2nb.novel.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** username is retained only for the existing PC/mobile form payloads. */
public record LoginRequest(String loginAccount, String username,
                           @NotBlank @Size(max = 1024) String password, String imageCaptcha) {
    public String account() {
        return loginAccount == null || loginAccount.isBlank() ? username : loginAccount;
    }
}
