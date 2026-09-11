package com.java2nb.novel.core.utils;

import com.java2nb.novel.core.cache.CacheKey;
import com.java2nb.novel.core.cache.CacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ThreadLocalUtilTest {

    @AfterEach
    void clearThreadLocals() {
        ThreadLocalUtil.setTemplateDir(null);
        ThreadLocalUtil.setClientId(null);
    }

    @Test
    void returnsTheClientIdSetForTheCurrentThread() {
        ThreadLocalUtil.setClientId("reader-mark");

        assertThat(ThreadLocalUtil.getClientId()).isEqualTo("reader-mark");
    }

    @Test
    void returnsLocalTemplateDirectoryWhenRedisReadFails() {
        ThreadLocalUtil.setTemplateDir("mobile/");
        ThreadLocalUtil.setClientId("reader-mark");
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.get(CacheKey.TEMPLATE_DIR_KEY + "reader-mark"))
            .thenThrow(new RedisConnectionFailureException("offline"));

        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil.when(() -> SpringUtil.getBean(CacheService.class)).thenReturn(cacheService);

            assertThatCode(() -> assertThat(ThreadLocalUtil.getTemplateDir()).isEqualTo("mobile/"))
                .doesNotThrowAnyException();
        }
    }

    @Test
    void returnsLocalTemplateDirectoryWhenCacheBeanLookupFails() {
        ThreadLocalUtil.setTemplateDir("mobile/");
        ThreadLocalUtil.setClientId("reader-mark");

        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil.when(() -> SpringUtil.getBean(CacheService.class))
                .thenThrow(new IllegalStateException("cache unavailable"));

            assertThatCode(() -> assertThat(ThreadLocalUtil.getTemplateDir()).isEqualTo("mobile/"))
                .doesNotThrowAnyException();
        }
    }

    @Test
    void usesCachedDesktopPreferenceEvenWhenLocalTemplateIsMobile() {
        ThreadLocalUtil.setTemplateDir("mobile/");
        ThreadLocalUtil.setClientId("reader-mark");
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.get(CacheKey.TEMPLATE_DIR_KEY + "reader-mark")).thenReturn("");

        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil.when(() -> SpringUtil.getBean(CacheService.class)).thenReturn(cacheService);

            assertThat(ThreadLocalUtil.getTemplateDir()).isEmpty();
        }
    }

    @Test
    void returnsLocalTemplateDirectoryWhenNoCachedPreferenceExists() {
        ThreadLocalUtil.setTemplateDir("mobile/");
        ThreadLocalUtil.setClientId("reader-mark");
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.get(CacheKey.TEMPLATE_DIR_KEY + "reader-mark")).thenReturn(null);

        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil.when(() -> SpringUtil.getBean(CacheService.class)).thenReturn(cacheService);

            assertThat(ThreadLocalUtil.getTemplateDir()).isEqualTo("mobile/");
        }
    }
}
