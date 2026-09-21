package com.java2nb.novel.auth;

import com.java2nb.novel.auth.model.AuthenticationResult;
import com.java2nb.novel.auth.dto.RegisterRequest;
import com.java2nb.novel.auth.dto.PasswordResetRequest;
import com.java2nb.novel.auth.dto.PasswordChangeRequest;

public interface AuthenticationService {
    AuthenticationResult login(String account, String rawPassword);
    EmailCodeRequestOutcome requestRegistrationCode(String email, String clientAddress);
    AuthenticationResult register(RegisterRequest request);
    EmailCodeRequestOutcome requestPasswordResetCode(String email, String clientAddress);
    void resetPassword(PasswordResetRequest request);
    AuthenticationResult changePassword(long userId, PasswordChangeRequest request);
    String legacyUsername(long userId);
}
