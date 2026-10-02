package com.java2nb.novel.core.filter;

import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.core.utils.BrowserUtil;
import com.java2nb.novel.core.utils.Constants;
import com.java2nb.novel.core.utils.SpringUtil;
import com.java2nb.novel.core.utils.JwtTokenUtil;
import com.java2nb.novel.core.utils.ThreadLocalUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class NovelFilterCookieTest {

    @Test
    void clearsRequestThreadLocalsAfterNormalAndExceptionalRequests() throws Exception {
        CacheService cache = mock(CacheService.class);
        MockHttpServletRequest first = request("/book/1.html");
        first.setCookies(new Cookie(Constants.USER_CLIENT_MARK_KEY, "request-a"));
        MockHttpServletRequest second = request("/book/2.html");
        second.setCookies(new Cookie(Constants.USER_CLIENT_MARK_KEY, "request-b"));

        try (MockedStatic<SpringUtil> spring = mockStatic(SpringUtil.class);
             MockedStatic<BrowserUtil> browser = mockStatic(BrowserUtil.class)) {
            spring.when(() -> SpringUtil.getBean(CacheService.class)).thenReturn(cache);
            browser.when(() -> BrowserUtil.isMobile(first)).thenReturn(true);
            browser.when(() -> BrowserUtil.isMobile(second)).thenReturn(false);

            filter.doFilter(first, new MockHttpServletResponse(), (request, response) -> {
                assertThat(ThreadLocalUtil.getClientId()).isEqualTo("request-a");
                assertThat(ThreadLocalUtil.getTemplateDir()).isEqualTo("mobile/");
            });
            assertThat(ThreadLocalUtil.getClientId()).isNull();
            assertThat(ThreadLocalUtil.getTemplateDir()).isNull();

            assertThatThrownBy(() -> filter.doFilter(second, new MockHttpServletResponse(),
                (request, response) -> { throw new ServletException("downstream failed"); }))
                .isInstanceOf(ServletException.class);
            assertThat(ThreadLocalUtil.getClientId()).isNull();
            assertThat(ThreadLocalUtil.getTemplateDir()).isNull();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"pc, ''", "mobile, mobile/"})
    void explicitTemplateSelectionAppliesImmediatelyWhenRedisWriteFails(
        String selection, String expectedTemplate
    ) throws Exception {
        CacheService cache = mock(CacheService.class);
        MockHttpServletRequest request = request("/book/1.html");
        request.setCookies(new Cookie(Constants.USER_CLIENT_MARK_KEY, "reader"));
        request.setParameter("to", selection);
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"))
            .when(cache).set(com.java2nb.novel.core.cache.CacheKey.TEMPLATE_DIR_KEY + "reader", expectedTemplate, 86400);
        when(cache.get(com.java2nb.novel.core.cache.CacheKey.TEMPLATE_DIR_KEY + "reader"))
            .thenReturn("pc".equals(selection) ? "mobile/" : "");

        try (MockedStatic<SpringUtil> spring = mockStatic(SpringUtil.class)) {
            spring.when(() -> SpringUtil.getBean(CacheService.class)).thenReturn(cache);
            filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                assertThat(ThreadLocalUtil.getTemplateDir()).isEqualTo(expectedTemplate));
        }
        assertThat(ThreadLocalUtil.getClientId()).isNull();
        assertThat(ThreadLocalUtil.getTemplateDir()).isNull();
    }

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
