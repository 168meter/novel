package com.java2nb.novel.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record EmailCodeRequest(@NotBlank @Email @Size(max = 254) String email) {
    public EmailCodeRequest {
        if (email != null) email = email.trim().toLowerCase(Locale.ROOT);
    }

    @Override public String toString() { return "EmailCodeRequest[redacted]"; }
}
