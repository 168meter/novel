package com.java2nb.novel.service.impl;

import com.java2nb.novel.cache.ChapterCacheIdentity;
import com.java2nb.novel.cache.CrawlChapterCacheInvalidator;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.mapper.BookCategoryMapper;
import com.java2nb.novel.mapper.CrawlBookIndexMapper;
import com.java2nb.novel.mapper.CrawlBookMapper;
import com.java2nb.novel.service.BookContentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BookServiceChapterCacheEvictionTest {

    @Mock private CrawlBookMapper bookMapper;
    @Mock private BookCategoryMapper categoryMapper;
    @Mock private CrawlBookIndexMapper indexMapper;
    @Mock private CrawlChapterCacheInvalidator invalidator;

    private BookContentService dbContentService;
    private BookServiceImpl service;

    @BeforeEach
    void setUp() {
        dbContentService = mock(BookContentService.class);
        service = new BookServiceImpl(bookMapper, categoryMapper, indexMapper,
            Map.of("db", dbContentService), invalidator);
        ReflectionTestUtils.setField(service, "storageType", "db");
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void batchDatabaseInsertSchedulesOneDistinctEvictionBatch() {
        Book book = book(101L);
        BookIndex first = index(201L, 1);
        BookIndex duplicate = index(201L, 2);

        service.saveBookAndIndexAndContent(book, List.of(first, duplicate),
            List.of(content(201L), content(201L)));

        verify(invalidator, times(1)).evictAfterCommit(argThat(identities ->
            identities.equals(List.of(new ChapterCacheIdentity(101L, 201L)))));
    }

    @Test
    void newDatabaseChapterSchedulesEviction() {
        Book book = book(101L);
        BookIndex chapter = index(202L, 2);

        service.updateBookAndIndexAndContent(book, List.of(chapter), List.of(content(202L)), Map.of());

        verify(invalidator).evictAfterCommit(argThat(identities ->
            identities.equals(List.of(new ChapterCacheIdentity(101L, 202L)))));
    }

    @Test
    void existingDatabaseChapterUpdateSchedulesEviction() {
        Book book = book(101L);
        BookIndex incoming = index(202L, 2);
        BookIndex existing = index(202L, 2);
        existing.setStorageType("db");

        service.updateBookAndIndexAndContent(book, List.of(incoming), List.of(content(202L)),
            Map.of(2, existing));

        verify(invalidator).evictAfterCommit(argThat(identities ->
            identities.equals(List.of(new ChapterCacheIdentity(101L, 202L)))));
    }

    @Test
    void transactionRollbackDoesNotDeleteChapterCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        BookServiceImpl transactionalService = serviceWith(
            new CrawlChapterCacheInvalidator(redis, new SimpleMeterRegistry()));
        TransactionSynchronizationManager.initSynchronization();

        transactionalService.saveBookAndIndexAndContent(book(101L), List.of(index(201L, 1)),
            List.of(content(201L)));
        TransactionSynchronization callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
        callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(redis, never()).delete(anyCollection());
    }

    @Test
    void redisDeleteFailureDoesNotFailCommittedWriterPath() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("redis unavailable")).when(redis).delete(anyCollection());
        BookServiceImpl resilientService = serviceWith(
            new CrawlChapterCacheInvalidator(redis, new SimpleMeterRegistry()));

        assertThatCode(() -> resilientService.saveBookAndIndexAndContent(book(101L),
            List.of(index(201L, 1)), List.of(content(201L))))
            .doesNotThrowAnyException();
    }

    private BookServiceImpl serviceWith(CrawlChapterCacheInvalidator cacheInvalidator) {
        BookServiceImpl result = new BookServiceImpl(bookMapper, categoryMapper, indexMapper,
            Map.of("db", dbContentService), cacheInvalidator);
        ReflectionTestUtils.setField(result, "storageType", "db");
        return result;
    }

    private Book book(long id) {
        Book book = new Book();
        book.setId(id);
        return book;
    }

    private BookIndex index(long id, int indexNum) {
        BookIndex index = new BookIndex();
        index.setId(id);
        index.setIndexNum(indexNum);
        return index;
    }

    private BookContent content(long indexId) {
        BookContent content = new BookContent();
        content.setIndexId(indexId);
        content.setContent("content");
        return content;
    }
}
