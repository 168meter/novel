package com.java2nb.novel.service.impl;

import com.java2nb.novel.core.config.BookPriceProperties;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.mapper.BookContentMapper;
import com.java2nb.novel.mapper.FrontBookMapper;
import com.java2nb.novel.service.cache.ChapterContentCache;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookServiceChapterCacheEvictionTest {

    @Test
    void newDatabaseChapterSchedulesNullMarkerEvictionAfterCommit() throws Exception {
        Constructor<?> constructor = BookServiceImpl.class.getDeclaredConstructors()[0];
        Object[] dependencies = new Object[constructor.getParameterCount()];
        FrontBookMapper bookMapper = null;
        BookContentMapper contentMapper = null;
        ChapterContentCache cache = null;
        BookPriceProperties price = null;
        for (int i = 0; i < constructor.getParameterTypes().length; i++) {
            Class<?> type = constructor.getParameterTypes()[i];
            dependencies[i] = mock(type);
            if (type == FrontBookMapper.class) bookMapper = (FrontBookMapper) dependencies[i];
            if (type == BookContentMapper.class) contentMapper = (BookContentMapper) dependencies[i];
            if (type == ChapterContentCache.class) cache = (ChapterContentCache) dependencies[i];
            if (type == BookPriceProperties.class) price = (BookPriceProperties) dependencies[i];
        }
        BookServiceImpl service = (BookServiceImpl) constructor.newInstance(dependencies);

        Book book = new Book();
        book.setId(101L);
        book.setAuthorId(301L);
        book.setWordCount(0);
        when(bookMapper.selectMany(any())).thenReturn(List.of(book));
        when(price.getWordCount()).thenReturn(BigDecimal.valueOf(1_000));
        when(price.getValue()).thenReturn(BigDecimal.ONE);

        service.addBookContent(101L, "new chapter", "content", (byte) 0, 301L);

        ArgumentCaptor<BookContent> inserted = ArgumentCaptor.forClass(BookContent.class);
        verify(contentMapper).insertSelective(inserted.capture());
        verify(cache).evictAfterCommit(101L, inserted.getValue().getIndexId());
    }
}
