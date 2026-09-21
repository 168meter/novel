package com.java2nb.novel.auth.dto;

import jakarta.validation.constraints.*;

public record PasswordChangeRequest(
    @NotBlank @Size(max = 1024) String oldPassword,
    @NotBlank @Size(min = 8, max = 64) String newPassword1,
    @NotBlank String newPassword2
) {
    @Override public String toString() { return "PasswordChangeRequest[redacted]"; }
}
