package com.java2nb.novel.controller;

import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.service.*;
import com.java2nb.novel.messaging.BookVisitEventPublisher;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class BookControllerChapterOwnershipTest {
    @Test
    void mismatchedBookAndChapterNeverReachContentCache() {
        BookService books = mock(BookService.class, CALLS_REAL_METHODS);
        BookContentService contents = mock(BookContentService.class);
        BookIndex foreign = new BookIndex(); foreign.setId(100L); foreign.setBookId(2L); foreign.setStorageType("db");
        when(books.queryBookIndex(100L)).thenReturn(foreign);
        when(books.findBookIndex(1L, 100L)).thenReturn(Optional.empty());
        BookController controller = new BookController(books, Map.of("db", contents),
            mock(IpLocationService.class), mock(LikeService.class), mock(BookVisitEventPublisher.class));
        assertThat(controller.queryBookIndexAbout(1L, 100L).isOk()).isFalse();
        verifyNoInteractions(contents);
        verify(books).findBookIndex(1L, 100L);
    }

    @Test
    void matchingSummaryReusesOwnershipQueryAndPreservesContentIdentity() {
        BookService books=mock(BookService.class,CALLS_REAL_METHODS);
        var indexes=mock(com.java2nb.novel.mapper.BookIndexMapper.class);
        var index=new BookIndex();index.setId(100L);index.setBookId(1L);index.setStorageType("db");
        when(indexes.selectByPrimaryKey(100L)).thenReturn(Optional.of(index));
        when(books.findBookIndex(1L,100L)).thenAnswer(invocation -> indexes.selectByPrimaryKey(100L));
        var cache=mock(com.java2nb.novel.service.cache.ChapterContentCache.class);
        var body=new com.java2nb.novel.entity.BookContent();body.setContent("real content");
        when(cache.getOrLoad(eq(1L),eq(100L),any())).thenReturn(body);
        var contents=new com.java2nb.novel.service.impl.DbBookContentServiceImpl(
            mock(com.java2nb.novel.mapper.BookContentMapper.class),cache,indexes);
        var controller=new BookController(books,Map.of("db",contents),
            mock(IpLocationService.class),mock(LikeService.class),mock(BookVisitEventPublisher.class));
        assertThat(controller.queryBookIndexAbout(1L,100L).isOk()).isTrue();
        verify(indexes,times(1)).selectByPrimaryKey(100L);
        verify(cache).getOrLoad(eq(1L),eq(100L),any());
        verify(books).queryIndexCount(1L);
    }
}
