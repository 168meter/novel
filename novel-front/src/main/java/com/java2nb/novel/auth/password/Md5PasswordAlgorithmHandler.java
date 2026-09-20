package com.java2nb.novel.auth.password;

import io.github.xxyopen.util.MD5Util;

public final class Md5PasswordAlgorithmHandler implements PasswordAlgorithmHandler {
    private static final java.util.regex.Pattern MD5 = java.util.regex.Pattern.compile("^[0-9a-fA-F]{32}$");
    @Override public String algorithm() { return PasswordAlgorithm.MD5.name(); }

    @Override public PasswordHash encode(String rawPassword) {
        throw new IllegalStateException("MD5 is a legacy verification-only algorithm.");
    }

    @Override public boolean matches(String rawPassword, String encodedPassword) {
        requireRaw(rawPassword);
        if (encodedPassword == null || !MD5.matcher(encodedPassword).matches()) return false;
        try {
            return MD5Util.MD5Encode(rawPassword).equalsIgnoreCase(encodedPassword);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override public boolean needsUpgrade(String encodedPassword) {
        return encodedPassword != null && MD5.matcher(encodedPassword).matches();
    }

    private static void requireRaw(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) throw new IllegalArgumentException("Password is required.");
    }
}
