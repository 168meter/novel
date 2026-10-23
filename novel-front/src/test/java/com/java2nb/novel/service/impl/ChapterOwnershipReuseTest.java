package com.java2nb.novel.service.impl;

import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.mapper.BookContentMapper;
import com.java2nb.novel.mapper.BookIndexMapper;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.cache.ChapterContentCache;
import java.lang.reflect.Modifier;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChapterOwnershipReuseTest {
    BookIndex index(long book,long chapter) {
        var row=new BookIndex();row.setId(chapter);row.setBookId(book);row.setIsVip((byte)1);row.setStorageType("db");
        return row;
    }
    @Test void foreignOrWrongChapterRowCannotMintProof() {
        var books=mock(BookService.class,CALLS_REAL_METHODS);
        when(books.findBookIndex(1L,2L)).thenReturn(Optional.of(index(9,2)),Optional.of(index(1,8)));
        assertThat(books.findOwnedChapter(1L,2L)).isEmpty();
        assertThat(books.findOwnedChapter(1L,2L)).isEmpty();
        assertThat(books.findOwnedChapter(null,2L)).isEmpty();
        assertThat(books.findOwnedChapter(1L,-1L)).isEmpty();
    }
    @Test void mutableMetadataCannotChangeCacheIdentityOrVipSnapshot() {
        var books=mock(BookService.class,CALLS_REAL_METHODS);var row=index(1,2);
        when(books.findBookIndex(1L,2L)).thenReturn(Optional.of(row));
        var proof=books.findOwnedChapter(1L,2L).orElseThrow();
        row.setBookId(9L);row.setIsVip((byte)0);
        var copy=proof.bookIndex();copy.setId(99L);copy.setBookId(9L);copy.setIsVip((byte)0);
        assertThat(proof.bookId()).isEqualTo(1L);assertThat(proof.chapterId()).isEqualTo(2L);
        assertThat(proof.bookIndex().getIsVip()).isEqualTo((byte)1);
        assertThat(Modifier.isFinal(BookService.OwnedChapter.class.getModifiers())).isTrue();
        assertThat(BookService.OwnedChapter.class.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers()));
        var mapper=mock(BookIndexMapper.class);var cache=mock(ChapterContentCache.class);var contentMapper=mock(BookContentMapper.class);
        BookContent body=new BookContent();body.setContent("correct");
        when(cache.getOrLoad(eq(1L),eq(2L),any())).thenReturn(body);
        assertThat(new DbBookContentServiceImpl(contentMapper,cache,mapper).queryBookContent(proof)).isSameAs(body);
        verifyNoInteractions(mapper,contentMapper);verify(cache).getOrLoad(eq(1L),eq(2L),any());
    }
    @Test void directServiceStillRejectsForeignIdsBeforeAnyCacheOperation() {
        var mapper=mock(BookIndexMapper.class);when(mapper.selectByPrimaryKey(2L)).thenReturn(Optional.of(index(1,2)));
        var cache=mock(ChapterContentCache.class);var contentMapper=mock(BookContentMapper.class);
        var db=new DbBookContentServiceImpl(contentMapper,cache,mapper);
        assertThat(db.queryBookContent(9L,2L)).isNull();
        assertThat(db.queryBookContent((BookService.OwnedChapter)null)).isNull();
        assertThat(new FileBookContentServiceImpl(mapper).queryBookContent(9L,2L)).isNull();
        verifyNoInteractions(cache,contentMapper);
    }
}
