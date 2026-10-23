package com.java2nb.novel.service.impl;

import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.entity.News;
import com.java2nb.novel.mapper.FrontNewsMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mybatis.dynamic.sql.select.render.SelectStatementProvider;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsServiceImplTest {

    @Test
    void redisReadFailureFallsBackToDatabase() {
        FrontNewsMapper mapper = mock(FrontNewsMapper.class);
        CacheService cache = mock(CacheService.class);
        List<News> databaseNews = List.of(new News());
        when(cache.getList(any(), any())).thenThrow(new RedisConnectionFailureException("offline"));
        when(mapper.selectMany(any(SelectStatementProvider.class))).thenReturn(databaseNews);

        assertThat(new NewsServiceImpl(mapper, cache).listIndexNews()).isSameAs(databaseNews);
    }

    @Test
    void redisWriteFailureDoesNotDiscardDatabaseNews() {
        FrontNewsMapper mapper = mock(FrontNewsMapper.class);
        CacheService cache = mock(CacheService.class);
        List<News> databaseNews = List.of(new News());
        when(mapper.selectMany(any(SelectStatementProvider.class))).thenReturn(databaseNews);
        doThrow(new RedisConnectionFailureException("offline"))
            .when(cache).setObject(any(), any(), org.mockito.ArgumentMatchers.anyLong());

        assertThat(new NewsServiceImpl(mapper, cache).listIndexNews()).isSameAs(databaseNews);
        verify(mapper).selectMany(any(SelectStatementProvider.class));
    }
}
