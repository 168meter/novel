package com.java2nb.novel.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record RegisterRequest(
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Pattern(regexp = "\\d{6}") String code,
    @NotBlank @Size(min = 8, max = 64) String password,
    @NotBlank String confirmPassword
) {
    public RegisterRequest {
        if (email != null) email = email.trim().toLowerCase(Locale.ROOT);
    }

    @Override public String toString() { return "RegisterRequest[redacted]"; }
}
