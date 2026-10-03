package com.java2nb.novel.service.impl;

import com.java2nb.novel.cache.AdminChapterCacheInvalidator;
import com.java2nb.novel.dao.BookContentDao;
import com.java2nb.novel.dao.BookDao;
import com.java2nb.novel.dao.BookIndexDao;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookServiceChapterCacheEvictionTest {

    @Test
    void wholeBookDeleteEvictsKnownChapterIdsAsOneBatchAfterMutation() {
        BookDao bookDao = mock(BookDao.class);
        BookIndexDao indexDao = mock(BookIndexDao.class);
        BookContentDao contentDao = mock(BookContentDao.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AdminChapterCacheInvalidator invalidator = new AdminChapterCacheInvalidator(redisTemplate,
            new SimpleMeterRegistry());
        BookServiceImpl service = new BookServiceImpl(bookDao, indexDao, contentDao, invalidator);
        when(indexDao.getIdsByBookId(101L)).thenReturn(List.of(201L, 202L));
        when(bookDao.remove(101L)).thenReturn(1);

        service.remove(101L);

        verify(redisTemplate).delete(Set.of(
            "novel:chapter:v1:101:201",
            "novel:chapter:v1:101:202"
        ));
    }
}
