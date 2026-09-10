package com.java2nb.novel.core.utils;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CookieUtilTest {

    @Test
    void writesSevenDayHardenedCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        CookieUtil.setCookie(response, "userClientMarkKey", "abc", 604800,
            true, true, "Lax");

        Cookie cookie = response.getCookie("userClientMarkKey");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("abc");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(604800);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }
}
