package com.java2nb.novel.auth;

import com.java2nb.novel.auth.model.AuthenticationResult;

public interface AuthenticationService {
    AuthenticationResult login(String account, String rawPassword);
}
