package com.java2nb.novel.service.impl;

import com.java2nb.novel.cache.AdminChapterCacheInvalidator;
import com.java2nb.novel.dao.BookContentDao;
import com.java2nb.novel.dao.BookIndexDao;
import com.java2nb.novel.domain.BookContentDO;
import com.java2nb.novel.domain.BookIndexDO;
import com.java2nb.novel.domain.ChapterCacheIdentityDO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookContentServiceChapterCacheEvictionTest {

    @Mock private BookContentDao contentDao;
    @Mock private BookIndexDao indexDao;
    @Mock private StringRedisTemplate redisTemplate;

    private BookContentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BookContentServiceImpl(contentDao, indexDao,
            new AdminChapterCacheInvalidator(redisTemplate, new SimpleMeterRegistry()));
    }

    @Test
    void saveEvictsResolvedChapterOnlyAfterSuccessfulInsert() {
        BookContentDO content = content(301L, 201L);
        when(indexDao.get(201L)).thenReturn(index(201L, 101L));
        when(contentDao.save(content)).thenReturn(1);

        service.save(content);

        verify(redisTemplate).delete(Set.of("novel:chapter:v1:101:201", "novel:chapter:lock:v1:101:201"));
    }

    @Test
    void failedSaveDoesNotEvict() {
        BookContentDO content = content(301L, 201L);
        when(indexDao.get(201L)).thenReturn(index(201L, 101L));
        when(contentDao.save(content)).thenReturn(0);

        service.save(content);

        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    void updateResolvesIdentityBeforeMutationAndEvictsAfterSuccess() {
        BookContentDO content = content(301L, null);
        when(contentDao.get(301L)).thenReturn(content(301L,201L));
        when(indexDao.get(201L)).thenReturn(index(201L,101L));
        content.setContent("edited");
        when(contentDao.update(content)).thenReturn(1);

        service.update(content);

        verify(redisTemplate).delete(Set.of("novel:chapter:v1:101:201", "novel:chapter:lock:v1:101:201"));
    }

    @Test
    void removeResolvesIdentityBeforeDelete() {
        when(contentDao.listCacheIdentitiesByIds(List.of(301L)))
            .thenReturn(List.of(identity(101L, 201L)));
        when(contentDao.remove(301L)).thenReturn(1);

        service.remove(301L);

        verify(redisTemplate).delete(Set.of("novel:chapter:v1:101:201", "novel:chapter:lock:v1:101:201"));
    }

    @Test
    void movingBodyBetweenChaptersIsRejectedWithoutInvalidation() {
        BookContentDO patch=content(301L,202L);
        when(contentDao.get(301L)).thenReturn(content(301L,201L));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.update(patch))
            .isInstanceOf(com.java2nb.common.exception.BusinessException.class);
        verify(contentDao,never()).update(org.mockito.ArgumentMatchers.any());
        verify(redisTemplate,never()).delete(anyCollection());
    }

    @Test
    void batchRemoveUsesOneIdentityQueryAndOneEvictionBatch() {
        Long[] rowIds = {301L, 302L};
        when(contentDao.listCacheIdentitiesByIds(List.of(rowIds)))
            .thenReturn(List.of(identity(101L, 201L), identity(101L, 202L)));
        when(contentDao.batchRemove(rowIds)).thenReturn(2);

        service.batchRemove(rowIds);

        verify(contentDao).listCacheIdentitiesByIds(List.of(rowIds));
        verify(redisTemplate).delete(Set.of("novel:chapter:v1:101:201", "novel:chapter:lock:v1:101:201", "novel:chapter:v1:101:202", "novel:chapter:lock:v1:101:202"));
    }

    private BookContentDO content(long id, Long indexId) {
        BookContentDO content = new BookContentDO();
        content.setId(id);
        content.setIndexId(indexId);
        return content;
    }

    private BookIndexDO index(long id, long bookId) {
        BookIndexDO index = new BookIndexDO();
        index.setId(id);
        index.setBookId(bookId);
        return index;
    }

    private ChapterCacheIdentityDO identity(long bookId, long indexId) {
        ChapterCacheIdentityDO identity = new ChapterCacheIdentityDO();
        identity.setBookId(bookId);
        identity.setBookIndexId(indexId);
        return identity;
    }
}
