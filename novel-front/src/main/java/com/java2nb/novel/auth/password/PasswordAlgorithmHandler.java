package com.java2nb.novel.auth.password;

public interface PasswordAlgorithmHandler {
    String algorithm();
    PasswordHash encode(String rawPassword);
    boolean matches(String rawPassword, String encodedPassword);
    boolean needsUpgrade(String encodedPassword);
}
