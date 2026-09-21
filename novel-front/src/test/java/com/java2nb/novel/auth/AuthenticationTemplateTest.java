package com.java2nb.novel.auth;

import com.java2nb.novel.controller.page.PageController;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationTemplateTest {

    private static final List<String> LOGIN_TEMPLATES = List.of(
        "templates/user/login.html",
        "templates/mobile/user/login.html"
    );
    private static final List<String> REGISTER_TEMPLATES = List.of(
        "templates/user/register.html",
        "templates/mobile/user/register.html"
    );
    private static final List<String> RESET_TEMPLATES = List.of(
        "templates/user/forgot_password.html",
        "templates/mobile/user/forgot_password.html"
    );

    @Test
    void pcAndMobileLoginAcceptEmailOrLegacyPhoneAndRevealCaptchaOnlyWhenRequired() throws Exception {
        for (String path : LOGIN_TEMPLATES) {
            String html = resource(path);
            assertThat(html)
                .contains("id=\"loginAccount\"", "邮箱或历史手机号", "id=\"loginPassword\"")
                .contains("id=\"imageCaptchaGroup\"", "id=\"imageCaptcha\"", "/file/getVerify")
                .contains("/user/forgot_password.html", "AuthPage.initLogin")
                .doesNotContain("localStorage.setItem(\"password\"")
                .doesNotContain("console.log(");
        }
    }

    @Test
    void pcAndMobileRegistrationUseEmailCodeAndPasswordConfirmation() throws Exception {
        for (String path : REGISTER_TEMPLATES) {
            String html = resource(path);
            assertEmailCodePasswordForm(html, "register");
            assertThat(html)
                .contains("AuthPage.initRegister")
                .doesNotContain("手机号", "name=\"username\"", "isPhone(");
        }
    }

    @Test
    void pcAndMobilePasswordResetUsePurposeSpecificCodeFlow() throws Exception {
        for (String path : RESET_TEMPLATES) {
            String html = resource(path);
            assertEmailCodePasswordForm(html, "reset");
            assertThat(html).contains("AuthPage.initPasswordReset", "/user/login.html");
        }
    }

    @Test
    void sharedJavascriptUsesFinalizedApisAndDoesNotPersistSecrets() throws Exception {
        String javascript = resource("static/javascript/user.js");
        assertThat(javascript)
            .contains("/user/register/email-code", "/user/register")
            .contains("/user/password-reset/email-code", "/user/password-reset")
            .contains("captchaRequired", "prop(\"disabled\", true)", "clearSecrets")
            .contains("60")
            .doesNotContain("localStorage.setItem(\"password\"")
            .doesNotContain("localStorage.setItem(\"code\"")
            .doesNotContain("console.log(");
    }

    @Test
    void forgotPasswordRouteSelectsPcOrMobileTemplateDirectory() throws Exception {
        RequestMapping mapping = PageController.class.getDeclaredMethod("forgotPassword")
            .getAnnotation(RequestMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.path()).containsExactly("user/forgot_password.html");
    }

    private void assertEmailCodePasswordForm(String html, String prefix) {
        assertThat(html)
            .contains("id=\"" + prefix + "Email\"")
            .contains("type=\"email\"")
            .contains("id=\"" + prefix + "Code\"")
            .contains("maxlength=\"6\"")
            .contains("id=\"" + prefix + "SendCode\"")
            .contains("id=\"" + prefix + "Password\"")
            .contains("id=\"" + prefix + "ConfirmPassword\"");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
