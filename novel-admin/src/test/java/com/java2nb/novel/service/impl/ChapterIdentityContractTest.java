package com.java2nb.novel.service.impl;

import com.java2nb.common.exception.BusinessException;
import com.java2nb.novel.cache.AdminChapterCacheInvalidator;
import com.java2nb.novel.dao.*;
import com.java2nb.novel.domain.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChapterIdentityContractTest {
    BookIndexDao indexes;
    BookContentDao contents;
    BookDao books;
    StringRedisTemplate redis;
    BookIndexServiceImpl directories;
    BookContentServiceImpl bodies;
    @BeforeEach void setup() {
        indexes=mock(BookIndexDao.class);contents=mock(BookContentDao.class);books=mock(BookDao.class);
        redis=mock(StringRedisTemplate.class);
        var invalidator=new AdminChapterCacheInvalidator(redis,new SimpleMeterRegistry());
        directories=new BookIndexServiceImpl(indexes,invalidator,books);
        bodies=new BookContentServiceImpl(contents,indexes,invalidator);
    }
    @Test void differentBookIsRejectedBeforeUpdate() {
        when(indexes.get(201L)).thenReturn(index());
        var patch=index();patch.setBookId(102L);patch.setIndexName("edited");
        assertThatThrownBy(()->directories.update(patch)).isInstanceOf(BusinessException.class).hasMessageContaining("book_id");
        verify(indexes,never()).update(any());verifyNoInteractions(redis);
    }
    @Test void differentStorageIsRejectedBeforeUpdate() {
        when(indexes.get(201L)).thenReturn(index());
        var patch=index();patch.setStorageType("txt");
        assertThatThrownBy(()->directories.update(patch)).isInstanceOf(BusinessException.class).hasMessageContaining("storage_type");
        verify(indexes,never()).update(any());verifyNoInteractions(redis);
    }
    @Test void sameDirectoryIdentityAllowsTitleEdit() {
        when(indexes.get(201L)).thenReturn(index());var patch=index();patch.setIndexName("edited");
        when(indexes.update(patch)).thenReturn(1);
        assertThat(directories.update(patch)).isEqualTo(1);verifyNoInteractions(redis);
    }
    @Test void omittedDirectoryIdentityAllowsTitleEdit() {
        when(indexes.get(201L)).thenReturn(index());var patch=new BookIndexDO();patch.setId(201L);patch.setIndexName("edited");
        when(indexes.update(patch)).thenReturn(1);
        assertThat(directories.update(patch)).isEqualTo(1);verifyNoInteractions(redis);
    }
    @Test void differentBodyDirectoryIsRejectedBeforeUpdate() {
        when(contents.get(301L)).thenReturn(body(201L));
        var patch=body(202L);
        assertThatThrownBy(()->bodies.update(patch)).isInstanceOf(BusinessException.class).hasMessageContaining("index_id");
        verify(contents,never()).update(any());verifyNoInteractions(redis);
    }
    @Test void nonexistentDirectoryCannotReceiveNewBody() {
        assertThatThrownBy(()->bodies.save(body(999L))).isInstanceOf(BusinessException.class);
        verify(contents,never()).save(any());verifyNoInteractions(redis);
    }
    @Test void newBodyRequiresDirectoryId() {
        assertThatThrownBy(()->bodies.save(body(null))).isInstanceOf(BusinessException.class);
        verify(contents,never()).save(any());verifyNoInteractions(redis);
    }
    @Test void sameBodyIdentityAllowsContentEdit() {
        var patch=body(201L);when(contents.get(301L)).thenReturn(body(201L));when(indexes.get(201L)).thenReturn(index());
        when(contents.update(patch)).thenReturn(1);
        assertThat(bodies.update(patch)).isEqualTo(1);
        verify(redis).delete(java.util.Set.of("novel:chapter:v1:101:201","novel:chapter:lock:v1:101:201"));
    }
    @Test void omittedBodyIdentityAllowsContentEdit() {
        var patch=body(null);when(contents.get(301L)).thenReturn(body(201L));when(indexes.get(201L)).thenReturn(index());
        when(contents.update(patch)).thenReturn(1);
        assertThat(bodies.update(patch)).isEqualTo(1);
        verify(redis).delete(java.util.Set.of("novel:chapter:v1:101:201","novel:chapter:lock:v1:101:201"));
    }
    @Test void sameIdentityOnlyIsAcceptedAsNoOp() {
        when(indexes.get(201L)).thenReturn(index());assertThat(directories.update(index())).isZero();
        var patch=body(201L);patch.setContent(null);
        when(contents.get(301L)).thenReturn(body(201L));
        assertThat(bodies.update(patch)).isZero();
        verify(indexes,never()).update(any());verify(contents,never()).update(any());verifyNoInteractions(redis);
    }
    @Test void nonexistentBodyIsExplicitlyRejected() {
        assertThatThrownBy(()->bodies.update(body(201L))).isInstanceOf(BusinessException.class);
        verify(contents,never()).update(any());verifyNoInteractions(redis);
    }
    @Test void nonexistentChapterIsExplicitlyRejected() {
        assertThatThrownBy(()->directories.update(index())).isInstanceOf(BusinessException.class);
        verify(indexes,never()).update(any());verifyNoInteractions(redis);
    }
    @Test void newChapterCanChooseEitherSupportedStorage() {
        when(books.get(101L)).thenReturn(new BookDO());
        for(String storage:java.util.List.of("db","txt")) {
            var row=index();row.setStorageType(storage);when(indexes.save(row)).thenReturn(1);
            assertThat(directories.save(row)).isEqualTo(1);assertThat(row.getStorageType()).isEqualTo(storage);
        }
    }
    @Test void newChapterDefaultsToDatabaseStorageWhenOmitted() {
        when(books.get(101L)).thenReturn(new BookDO());var row=index();row.setStorageType(null);
        directories.save(row);assertThat(row.getStorageType()).isEqualTo("db");verify(indexes).save(row);
    }
    @Test void newChapterRejectsNonexistentBook() {
        assertThatThrownBy(()->directories.save(index())).isInstanceOf(BusinessException.class).hasMessageContaining("book_id");
        verify(indexes,never()).save(any());verifyNoInteractions(redis);
    }
    @Test void newChapterRejectsUnsupportedStorage() {
        when(books.get(101L)).thenReturn(new BookDO());var row=index();row.setStorageType("file");
        assertThatThrownBy(()->directories.save(row)).isInstanceOf(BusinessException.class).hasMessageContaining("storage_type");
        verify(indexes,never()).save(any());verifyNoInteractions(redis);
    }
    static BookIndexDO index(){var r=new BookIndexDO();r.setId(201L);r.setBookId(101L);r.setStorageType("db");return r;}
    static BookContentDO body(Long index){var r=new BookContentDO();r.setId(301L);r.setIndexId(index);r.setContent("edited");return r;}
}

