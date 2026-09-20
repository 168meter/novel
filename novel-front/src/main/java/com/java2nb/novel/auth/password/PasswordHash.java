package com.java2nb.novel.auth.password;

public record PasswordHash(String encoded, String algorithm) {
    public PasswordHash {
        if (encoded == null || encoded.isBlank() || algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("Password hash fields are required.");
        }
    }
}
