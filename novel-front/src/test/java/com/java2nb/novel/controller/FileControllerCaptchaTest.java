package com.java2nb.novel.controller;

import com.java2nb.novel.auth.security.ClientAddressResolver;
import com.java2nb.novel.auth.security.LoginSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileControllerCaptchaTest {
    @Test void captchaUsesTrustedResolvedClientAndStoresFourDigits() {
        LoginSecurityService security = mock(LoginSecurityService.class);
        ClientAddressResolver addresses = mock(ClientAddressResolver.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        when(addresses.resolve(request)).thenReturn("198.51.100.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new FileController(security, addresses).getVerify(request, response);
        verify(security).storeImageCaptcha(eq("198.51.100.7"), matches("\\d{4}"));
        assertThat(response.getContentType()).isEqualTo("image/jpeg");
        assertThat(response.getContentAsByteArray()).isNotEmpty();
    }

    @Test void redisFailureDoesNotWriteAnUnusableImage() {
        LoginSecurityService security = mock(LoginSecurityService.class);
        ClientAddressResolver addresses = mock(ClientAddressResolver.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(addresses.resolve(request)).thenReturn("198.51.100.7");
        doThrow(new IllegalStateException("redis down")).when(security)
            .storeImageCaptcha(eq("198.51.100.7"), anyString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new FileController(security, addresses).getVerify(request, response))
            .isInstanceOf(IllegalStateException.class);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }
}
