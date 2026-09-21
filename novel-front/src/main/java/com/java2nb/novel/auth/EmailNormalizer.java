package com.java2nb.novel.auth;

import java.util.Locale;

public final class EmailNormalizer {
    private EmailNormalizer() { }

    public static String normalize(String email) {
        if (email == null) throw new IllegalArgumentException("Invalid email");
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Invalid email");
        }
        return normalized;
    }
}
