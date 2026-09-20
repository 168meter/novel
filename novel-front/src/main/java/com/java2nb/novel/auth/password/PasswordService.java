package com.java2nb.novel.auth.password;

public interface PasswordService {
    PasswordHash encode(String rawPassword);
    boolean matches(String rawPassword, String encodedPassword, String algorithm);
    boolean needsUpgrade(String encodedPassword, String algorithm);
}
