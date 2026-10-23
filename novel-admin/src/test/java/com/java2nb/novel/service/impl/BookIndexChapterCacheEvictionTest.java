package com.java2nb.novel.service.impl;

import com.java2nb.novel.cache.*;
import com.java2nb.novel.dao.BookIndexDao;
import com.java2nb.novel.dao.BookDao;
import com.java2nb.novel.domain.BookDO;
import com.java2nb.novel.domain.BookIndexDO;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class BookIndexChapterCacheEvictionTest {
    @Mock BookIndexDao dao;
    @Mock BookDao books;
    @Mock org.springframework.data.redis.core.StringRedisTemplate redis;
    BookIndexServiceImpl service;
    @BeforeEach void setup(){service=new BookIndexServiceImpl(dao,new AdminChapterCacheInvalidator(redis,
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),books);}
    @Test void bookMoveIsRejectedWithoutChangingCache() {
        var patch=index(201,102);when(dao.get(201L)).thenReturn(index(201,101));
        assertThatThrownBy(()->service.update(patch)).isInstanceOf(com.java2nb.common.exception.BusinessException.class);
        verify(dao,never()).update(any());verifyNoInteractions(redis);
    }
    @Test void storageChangeIsRejectedWithoutChangingCache() {
        var patch=new BookIndexDO();patch.setId(201L);patch.setStorageType("file");
        var before=index(201,101);before.setStorageType("db");when(dao.get(201L)).thenReturn(before);
        assertThatThrownBy(()->service.update(patch)).isInstanceOf(com.java2nb.common.exception.BusinessException.class);
        verify(dao,never()).update(any());verifyNoInteractions(redis);
    }
    @Test void nameOnlyEditDoesNotInvalidateBody() {
        var patch=new BookIndexDO();patch.setId(201L);patch.setIndexName("renamed");
        when(dao.get(201L)).thenReturn(index(201,101));when(dao.update(patch)).thenReturn(1);
        service.update(patch);verifyNoInteractions(redis);
    }
    @Test void deleteInvalidatesExistingIdentityWithoutCascadingBodyDeletion() {
        when(dao.get(201L)).thenReturn(index(201,101));when(dao.remove(201L)).thenReturn(1);
        service.remove(201L);verify(redis).delete(keys(new ChapterCacheIdentity(101L,201L)));
    }
    @Test void batchDeleteCapturesIdentitiesBeforeMutation() {
        Long[] ids={201L,202L};when(dao.get(201L)).thenReturn(index(201,101));when(dao.get(202L)).thenReturn(index(202,102));when(dao.batchRemove(ids)).thenReturn(2);
        service.batchRemove(ids);
        verify(redis).delete(keys(new ChapterCacheIdentity(101L,201L),new ChapterCacheIdentity(102L,202L)));
        var order=inOrder(dao,redis);order.verify(dao).get(201L);order.verify(dao).get(202L);order.verify(dao).batchRemove(ids);
    }
    @Test void zeroAffectedRowsDoNotInvalidate() {
        when(dao.get(201L)).thenReturn(index(201,101));service.remove(201L);verifyNoInteractions(redis);
    }
    @Test void newIndexClearsPreviousNegativeCacheAfterSuccessfulSave() {
        var row=index(201,101);when(books.get(101L)).thenReturn(new BookDO());when(dao.save(row)).thenReturn(1);service.save(row);
        verify(redis).delete(keys(new ChapterCacheIdentity(101L,201L)));
    }
    static Set<String> keys(ChapterCacheIdentity... identities){
        var keys=new LinkedHashSet<String>();for(var i:identities){keys.add(i.cacheKey());keys.add(ChapterContentCacheKey.lockOf(i.bookId(),i.bookIndexId()));}return keys;
    }
    static BookIndexDO index(long id,long book){var i=new BookIndexDO();i.setId(id);i.setBookId(book);return i;}
}
