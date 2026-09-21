package com.java2nb.novel.auth;

import com.java2nb.novel.auth.model.AuthenticationResult;
import com.java2nb.novel.auth.dto.RegisterRequest;

public interface AuthenticationService {
    AuthenticationResult login(String account, String rawPassword);
    EmailCodeRequestOutcome requestRegistrationCode(String email, String clientAddress);
    AuthenticationResult register(RegisterRequest request);
}
