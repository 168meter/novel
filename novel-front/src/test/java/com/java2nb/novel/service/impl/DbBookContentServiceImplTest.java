package com.java2nb.novel.service.impl;

import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.mapper.BookContentMapper;
import com.java2nb.novel.service.cache.ChapterContentCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mybatis.dynamic.sql.select.render.SelectStatementProvider;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DbBookContentServiceImplTest {

    private static final long BOOK_ID = 101L;
    private static final long CHAPTER_ID = 202L;

    @Mock private BookContentMapper bookContentMapper;
    @Mock private ChapterContentCache chapterContentCache;

    private DbBookContentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DbBookContentServiceImpl(bookContentMapper, chapterContentCache);
    }

    @Test
    void queryBookContentDelegatesToCacheWithBookAndIndexIds() {
        BookContent cached = content("cached");
        when(chapterContentCache.getOrLoad(eq(BOOK_ID), eq(CHAPTER_ID), any())).thenReturn(cached);

        assertThat(service.queryBookContent(BOOK_ID, CHAPTER_ID)).isSameAs(cached);

        verify(chapterContentCache).getOrLoad(eq(BOOK_ID), eq(CHAPTER_ID), any());
        verify(bookContentMapper, never()).selectMany(any(SelectStatementProvider.class));
    }

    @Test
    void cacheLoaderReturnsTheSingleMapperResult() {
        ArgumentCaptor<Supplier<BookContent>> loader = loaderCaptor();
        BookContent databaseValue = content("database");
        when(bookContentMapper.selectMany(any(SelectStatementProvider.class))).thenReturn(List.of(databaseValue));

        service.queryBookContent(BOOK_ID, CHAPTER_ID);
        BookContent loaded = loader.getValue().get();

        assertThat(loaded).isSameAs(databaseValue);
        verify(bookContentMapper).selectMany(any(SelectStatementProvider.class));
    }

    @Test
    void cacheLoaderReturnsNullWhenMapperFindsNoContent() {
        ArgumentCaptor<Supplier<BookContent>> loader = loaderCaptor();
        when(bookContentMapper.selectMany(any(SelectStatementProvider.class))).thenReturn(List.of());

        service.queryBookContent(BOOK_ID, CHAPTER_ID);

        assertThat(loader.getValue().get()).isNull();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Supplier<BookContent>> loaderCaptor() {
        ArgumentCaptor<Supplier<BookContent>> loader = ArgumentCaptor.forClass(Supplier.class);
        when(chapterContentCache.getOrLoad(eq(BOOK_ID), eq(CHAPTER_ID), loader.capture())).thenReturn(null);
        return loader;
    }

    private BookContent content(String value) {
        BookContent content = new BookContent();
        content.setId(303L);
        content.setIndexId(CHAPTER_ID);
        content.setContent(value);
        return content;
    }
}
