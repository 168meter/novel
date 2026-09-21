package com.java2nb.novel.core.filter;

import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.core.utils.BrowserUtil;
import com.java2nb.novel.core.utils.Constants;
import com.java2nb.novel.core.utils.SpringUtil;
import com.java2nb.novel.core.utils.JwtTokenUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class NovelFilterCookieTest {

    @Test void tokenVersionDependencyFailureLeavesRequestUnauthenticated() throws Exception {
        MockHttpServletRequest request = request("/user/updatePassword");
        request.addHeader("Authorization", "signed-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CacheService cache = mock(CacheService.class);
        JwtTokenUtil tokens = mock(JwtTokenUtil.class);
        when(tokens.getAuthenticatedUserDetails("signed-token")).thenThrow(new IllegalStateException("db down"));
        try (MockedStatic<SpringUtil> spring = mockStatic(SpringUtil.class);
             MockedStatic<BrowserUtil> browser = mockStatic(BrowserUtil.class)) {
            spring.when(() -> SpringUtil.getBean(CacheService.class)).thenReturn(cache);
            spring.when(() -> SpringUtil.getBean(JwtTokenUtil.class)).thenReturn(tokens);
            browser.when(() -> BrowserUtil.isMobile(request)).thenReturn(false);
            filter.doFilter(request, response, noOpChain);
        }
        assertThat(request.getAttribute("novel.auth.checked")).isEqualTo(Boolean.TRUE);
        assertThat(request.getAttribute("novel.auth.user")).isNull();
    }

    private final NovelFilter filter = new NovelFilter();
    private final FilterChain noOpChain = (request, response) -> { };

    @Test
    void issuesHardenedNonSecureCookieForNewHttpIdentity() throws Exception {
        MockHttpServletRequest request = request("/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(request, response);

        assertHardenedCookie(response.getCookie(Constants.USER_CLIENT_MARK_KEY), false);
    }

    @Test
    void issuesHardenedSecureCookieForNewHttpsIdentity() throws Exception {
        MockHttpServletRequest request = request("/");
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(request, response);

        assertHardenedCookie(response.getCookie(Constants.USER_CLIENT_MARK_KEY), true);
    }

    @Test
    void reissuesExistingIdentityOnDocumentRouteWithHardenedAttributes() throws Exception {
        MockHttpServletRequest request = request("/chapter/1.html");
        request.setCookies(new Cookie(Constants.USER_CLIENT_MARK_KEY, "existing-identity"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(request, response);

        Cookie cookie = response.getCookie(Constants.USER_CLIENT_MARK_KEY);
        assertHardenedCookie(cookie, false);
        assertThat(cookie.getValue()).isEqualTo("existing-identity");
    }

    @Test
    void reissuesExistingIdentityOnNamedDocumentRoutes() throws Exception {
        for (String requestUri : new String[]{"/", "/index", "/index.html"}) {
            MockHttpServletRequest request = request(requestUri);
            request.setCookies(new Cookie(Constants.USER_CLIENT_MARK_KEY, "existing-identity"));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter(request, response);

            assertThat(response.getCookie(Constants.USER_CLIENT_MARK_KEY))
                .as("document route %s", requestUri)
                .extracting(Cookie::getValue)
                .isEqualTo("existing-identity");
        }
    }

    @Test
    void doesNotReissueExistingIdentityOnApiRoute() throws Exception {
        MockHttpServletRequest request = request("/api/books/1");
        request.setCookies(new Cookie(Constants.USER_CLIENT_MARK_KEY, "existing-identity"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(request, response);

        assertThat(response.getCookie(Constants.USER_CLIENT_MARK_KEY)).isNull();
    }

    private MockHttpServletRequest request(String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(requestUri);
        return request;
    }

    private void filter(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        CacheService cacheService = mock(CacheService.class);
        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class);
             MockedStatic<BrowserUtil> browserUtil = mockStatic(BrowserUtil.class)) {
            springUtil.when(() -> SpringUtil.getBean(CacheService.class)).thenReturn(cacheService);
            browserUtil.when(() -> BrowserUtil.isMobile(request)).thenReturn(false);

            filter.doFilter(request, response, noOpChain);
        }
    }

    private void assertHardenedCookie(Cookie cookie, boolean secure) {
        assertThat(cookie).isNotNull();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(604800);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isEqualTo(secure);
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }
}
