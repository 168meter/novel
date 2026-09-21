package com.java2nb.novel.auth.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;

public record PasswordResetRequest(
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Pattern(regexp = "\\d{6}") String code,
    @NotBlank @Size(min = 8, max = 64) String password,
    @NotBlank String confirmPassword
) {
    public PasswordResetRequest {
        if (email != null) email = email.trim().toLowerCase(Locale.ROOT);
    }
    @Override public String toString() { return "PasswordResetRequest[redacted]"; }
}
