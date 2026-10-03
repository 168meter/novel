package com.java2nb.novel.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CrawlChapterCacheInvalidatorTest {

    @Mock private StringRedisTemplate redisTemplate;

    private SimpleMeterRegistry meterRegistry;
    private CrawlChapterCacheInvalidator invalidator;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        invalidator = new CrawlChapterCacheInvalidator(redisTemplate, meterRegistry);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void immediatelyDeletesDistinctKeysWithoutTransactionSynchronization() {
        invalidator.evictAfterCommit(List.of(
            new ChapterCacheIdentity(101L, 201L),
            new ChapterCacheIdentity(101L, 201L),
            new ChapterCacheIdentity(101L, 202L)
        ));

        verify(redisTemplate).delete(Set.of(
            "novel:chapter:v1:101:201",
            "novel:chapter:v1:101:202"
        ));
        assertThat(meterRegistry.counter("novel.chapter.cache.eviction",
            "source", "crawl", "result", "success").count()).isEqualTo(1);
    }

    @Test
    void deletesOnceAfterCommitWhenSynchronizationExists() {
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAfterCommit(List.of(
            new ChapterCacheIdentity(101L, 201L),
            new ChapterCacheIdentity(101L, 202L)
        ));

        verify(redisTemplate, never()).delete(anyCollection());
        List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertThat(callbacks).hasSize(1);
        callbacks.getFirst().afterCommit();

        verify(redisTemplate).delete(Set.of(
            "novel:chapter:v1:101:201",
            "novel:chapter:v1:101:202"
        ));
    }

    @Test
    void rollbackDoesNotDelete() {
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAfterCommit(List.of(new ChapterCacheIdentity(101L, 201L)));
        TransactionSynchronizationManager.getSynchronizations().getFirst()
            .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    void redisFailureIsMeasuredAndDoesNotEscape() {
        doThrow(new RedisConnectionFailureException("offline"))
            .when(redisTemplate).delete(anyCollection());

        assertThatCode(() -> invalidator.evictAfterCommit(
            List.of(new ChapterCacheIdentity(101L, 201L))))
            .doesNotThrowAnyException();

        assertThat(meterRegistry.counter("novel.chapter.cache.eviction",
            "source", "crawl", "result", "failed").count()).isEqualTo(1);
    }
}
