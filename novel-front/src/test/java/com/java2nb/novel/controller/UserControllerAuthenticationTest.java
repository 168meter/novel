package com.java2nb.novel.controller;

import com.java2nb.novel.auth.AuthenticationService;
import com.java2nb.novel.auth.EmailCodeRequestOutcome;
import com.java2nb.novel.auth.dto.RegisterRequest;
import com.java2nb.novel.auth.dto.PasswordResetRequest;
import com.java2nb.novel.auth.dto.PasswordChangeRequest;
import com.java2nb.novel.auth.token.TokenVersionService;
import com.java2nb.novel.auth.model.AuthenticationResult;
import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.advice.CommonExceptionHandler;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.core.utils.JwtTokenUtil;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerAuthenticationTest {
    @Test void refreshingMinimalJwtRetainsLegacyUsernameFromDatabase() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        TokenVersionService versions = mock(TokenVersionService.class);
        when(versions.isCurrent(7L, 0L)).thenReturn(true);
        when(auth.legacyUsername(7L)).thenReturn("13800138000");
        JwtTokenUtil tokens = new JwtTokenUtil();
        ReflectionTestUtils.setField(tokens, "secret", "test-secret-at-least-thirty-two-characters-long");
        ReflectionTestUtils.setField(tokens, "expiration", 3600L);
        tokens.setTokenVersionService(versions);
        UserDetails identity = new UserDetails();
        identity.setId(7L);
        identity.setNickName("Reader");
        identity.setTokenVersion(0L);
        String oldToken = tokens.generateToken(identity);
        UserController controller = new UserController(mock(CacheService.class), mock(UserService.class),
            auth, mock(BookService.class));
        controller.setJwtTokenUtil(tokens);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String response = mvc.perform(post("/user/refreshToken").header("Authorization", oldToken))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("13800138000", "Reader", "token");
        verify(auth).legacyUsername(7L);
    }
    @Test void passwordResetCodeAndResetDoNotRevealAccountOrReturnToken() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        when(auth.requestPasswordResetCode("reader@example.com", "127.0.0.1"))
            .thenReturn(EmailCodeRequestOutcome.ACCEPTED);
        UserController controller = new UserController(mock(CacheService.class), mock(UserService.class),
            auth, mock(BookService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String codeResponse = mvc.perform(post("/user/password-reset/email-code")
            .param("email", "reader@example.com")
            .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; }))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String resetResponse = mvc.perform(post("/user/password-reset")
            .param("email", "reader@example.com").param("code", "123456")
            .param("password", "new-password-123").param("confirmPassword", "new-password-123"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(codeResponse).doesNotContain("reader@example.com", "123456");
        assertThat(resetResponse).doesNotContain("token", "new-password-123");
        verify(auth).resetPassword(any(PasswordResetRequest.class));
    }

    @Test void passwordChangeReturnsFreshTokenAndRejectsMismatchedConfirmation() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        UserDetails current = new UserDetails(); current.setId(7L); current.setTokenVersion(0L);
        UserDetails updated = new UserDetails(); updated.setId(7L); updated.setTokenVersion(1L);
        when(auth.changePassword(eq(7L), any(PasswordChangeRequest.class)))
            .thenReturn(new AuthenticationResult(updated));
        JwtTokenUtil tokens = mock(JwtTokenUtil.class);
        when(tokens.getAuthenticatedUserDetails("old-token")).thenReturn(current);
        when(tokens.generateToken(updated)).thenReturn("new-token");
        UserController controller = new UserController(mock(CacheService.class), mock(UserService.class),
            auth, mock(BookService.class));
        controller.setJwtTokenUtil(tokens);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String changed = mvc.perform(post("/user/updatePassword").header("Authorization", "old-token")
            .param("oldPassword", "old-password")
            .param("newPassword1", "new-password-123").param("newPassword2", "new-password-123"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(changed).contains("new-token");
        String mismatch = mvc.perform(post("/user/updatePassword").header("Authorization", "old-token")
            .param("oldPassword", "old-password")
            .param("newPassword1", "new-password-123").param("newPassword2", "different-password"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mismatch).doesNotContain("new-token");
        verify(auth, times(1)).changePassword(eq(7L), any(PasswordChangeRequest.class));
    }

    @Test void revokedTokenCannotRefresh() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        JwtTokenUtil tokens = mock(JwtTokenUtil.class);
        UserController controller = new UserController(mock(CacheService.class), mock(UserService.class),
            auth, mock(BookService.class));
        controller.setJwtTokenUtil(tokens);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String response = mvc.perform(post("/user/refreshToken").header("Authorization", "revoked"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("token");
        verify(tokens, never()).generateToken(any(UserDetails.class));
    }
    @Test void registrationCodeResponseDoesNotRevealAccountStateOrCode() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        when(auth.requestRegistrationCode("reader@example.com", "127.0.0.1"))
            .thenReturn(EmailCodeRequestOutcome.ACCEPTED);
        UserController controller = new UserController(mock(CacheService.class), mock(UserService.class),
            auth, mock(BookService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        String first = mvc.perform(post("/user/register/email-code").param("email", "reader@example.com")
                .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; }))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/user/register/email-code").param("email", "reader@example.com")
                .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; }))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(first).isEqualTo(second).doesNotContain("reader@example.com", "123456");
        verify(auth, times(2)).requestRegistrationCode("reader@example.com", "127.0.0.1");
    }

    @Test void verifiedRegistrationReturnsTokenWithoutEmail() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        UserDetails details = new UserDetails();
        details.setId(9L);
        details.setNickName("读者9");
        when(auth.register(any(RegisterRequest.class))).thenReturn(new AuthenticationResult(details));
        JwtTokenUtil tokens = mock(JwtTokenUtil.class);
        when(tokens.generateToken(details)).thenReturn("signed-token");
        UserController controller = new UserController(mock(CacheService.class), mock(UserService.class),
            auth, mock(BookService.class));
        controller.setJwtTokenUtil(tokens);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        String body = mvc.perform(post("/user/register")
                .param("email", "reader@example.com").param("code", "123456")
                .param("password", "password123").param("confirmPassword", "password123"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("signed-token").doesNotContain("reader@example.com");
        verify(auth).register(any(RegisterRequest.class));
    }

    @Test void oldPhoneFormAndShortPasswordCannotReachRegistrationService() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        UserController controller = new UserController(mock(CacheService.class), mock(UserService.class),
            auth, mock(BookService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new CommonExceptionHandler()).build();
        String oldForm = mvc.perform(post("/user/register")
                .param("username", "13800138000").param("password", "password123")
                .param("velCode", "1234"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String shortPassword = mvc.perform(post("/user/register")
                .param("email", "reader@example.com").param("code", "123456")
                .param("password", "1234567").param("confirmPassword", "1234567"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(oldForm).doesNotContain("token");
        assertThat(shortPassword).doesNotContain("token");
        verify(auth, never()).register(any(RegisterRequest.class));
    }

    @Test void emailWhitespaceIsNormalizedBeforeRequestValidation() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        when(auth.requestRegistrationCode("reader@example.com", "127.0.0.1"))
            .thenReturn(EmailCodeRequestOutcome.ACCEPTED);
        UserController controller = new UserController(mock(CacheService.class), mock(UserService.class),
            auth, mock(BookService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new CommonExceptionHandler()).build();
        mvc.perform(post("/user/register/email-code")
                .param("email", " Reader@Example.COM ")
                .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; }))
            .andExpect(status().isOk());
        verify(auth).requestRegistrationCode("reader@example.com", "127.0.0.1");
    }

    @Test void existingPhoneFormKeepsTokenResponse() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        UserDetails details = new UserDetails();
        details.setId(7L);
        when(auth.login("13800138000", "123")).thenReturn(new AuthenticationResult(details));
        JwtTokenUtil tokens = mock(JwtTokenUtil.class);
        when(tokens.generateToken(details)).thenReturn("signed-token");
        UserController controller = new UserController(mock(CacheService.class), mock(UserService.class),
            auth, mock(BookService.class));
        controller.setJwtTokenUtil(tokens);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        String body = mvc.perform(post("/user/login")
                .param("username", "13800138000").param("password", "123"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("signed-token");
    }
}
