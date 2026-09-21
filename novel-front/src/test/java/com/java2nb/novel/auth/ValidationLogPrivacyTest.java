package com.java2nb.novel.auth;

import com.java2nb.novel.core.advice.CommonExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ValidationLogPrivacyTest {
    @Test void rejectedPasswordAndEmailAreNotLogged(CapturedOutput output) {
        BindException failure = new BindException(new Object(), "registerRequest");
        failure.addError(new FieldError("registerRequest", "password", "sensitive-password", false,
            null, null, "invalid password"));
        failure.addError(new FieldError("registerRequest", "email", "private@example.com", false,
            null, null, "invalid email"));
        new CommonExceptionHandler().handlerBindException(failure);
        assertThat(output.toString()).doesNotContain("sensitive-password", "private@example.com");
    }
}
