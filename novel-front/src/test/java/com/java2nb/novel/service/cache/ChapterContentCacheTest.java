package com.java2nb.novel.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.entity.BookContent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChapterContentCacheTest {

    private static final long BOOK_ID = 101L;
    private static final long CHAPTER_ID = 202L;
    private static final String CONTENT_KEY = "novel:chapter:v1:101:202";
    private static final String LOCK_KEY = "novel:chapter:lock:v1:101:202";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private Supplier<BookContent> loader;
    @Mock private LongConsumer sleeper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IntSupplier jitterSource = () -> 7;
    private SimpleMeterRegistry meterRegistry;
    private ChapterContentCache cache;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        meterRegistry = new SimpleMeterRegistry();
        cache = new ChapterContentCache(redisTemplate, objectMapper, 1800, 300, 60, 10, 10, 50,
            sleeper, jitterSource, meterRegistry);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void springConstructorInjectsMeterRegistry() {
        assertThat(ChapterContentCache.class.getConstructors())
            .filteredOn(constructor -> constructor.isAnnotationPresent(Autowired.class))
            .singleElement()
            .satisfies(constructor -> assertThat(constructor.getParameterTypes()).containsExactly(
                StringRedisTemplate.class,
                ObjectMapper.class,
                MeterRegistry.class
            ));
    }

    @Test
    void cacheHitReturnsBookContentWithoutCallingLoader() throws Exception {
        when(valueOperations.get(CONTENT_KEY)).thenReturn(objectMapper.writeValueAsString(content("cached")));

        BookContent result = cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(result.getContent()).isEqualTo("cached");
        verifyNoInteractions(loader);
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS));
    }

    @Test
    void cacheHitRecordsLookupHit() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChapterContentCache instrumentedCache = new ChapterContentCache(redisTemplate, objectMapper, registry);
        when(valueOperations.get(CONTENT_KEY)).thenReturn(objectMapper.writeValueAsString(content("cached")));

        instrumentedCache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(registry.counter("novel.chapter.cache.lookup", "result", "hit").count())
            .isEqualTo(1);
    }

    @Test
    void cacheMissRecordsLookupMiss() {
        arrangeCacheMissWithLockWinner(content("database"));

        cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(meterRegistry.counter("novel.chapter.cache.lookup", "result", "miss").count())
            .isEqualTo(1);
    }

    @Test
    void redisReadFailureRecordsLookupError() {
        when(valueOperations.get(CONTENT_KEY)).thenThrow(new RedisConnectionFailureException("offline"));
        when(loader.get()).thenReturn(content("database"));

        cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(meterRegistry.counter("novel.chapter.cache.lookup", "result", "error").count())
            .isEqualTo(1);
        assertThat(meterRegistry.timer("novel.chapter.cache.load").count()).isEqualTo(1);
    }

    @Test
    void lockWinnerRecordsAcquired() {
        arrangeCacheMissWithLockWinner(content("database"));

        cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(meterRegistry.counter("novel.chapter.cache.lock", "result", "acquired").count())
            .isEqualTo(1);
    }

    @Test
    void lockLoserRecordsContended() throws Exception {
        when(valueOperations.get(CONTENT_KEY)).thenReturn(
            null,
            objectMapper.writeValueAsString(content("winner value"))
        );
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
            .thenReturn(false);

        cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(meterRegistry.counter("novel.chapter.cache.lock", "result", "contended").count())
            .isEqualTo(1);
    }

    @Test
    void redisLockFailureRecordsLockError() {
        when(valueOperations.get(CONTENT_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
            .thenThrow(new RedisConnectionFailureException("offline"));
        when(loader.get()).thenReturn(content("database"));

        cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(meterRegistry.counter("novel.chapter.cache.lock", "result", "error").count())
            .isEqualTo(1);
        assertThat(meterRegistry.timer("novel.chapter.cache.load").count()).isEqualTo(1);
    }

    @Test
    void successfulCacheFillRecordsWriteSuccess() {
        arrangeCacheMissWithLockWinner(content("database"));

        cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(meterRegistry.counter("novel.chapter.cache.write", "result", "success").count())
            .isEqualTo(1);
    }

    @Test
    void redisWriteOomRecordsWriteError() {
        arrangeCacheMissWithLockWinner(content("database"));
        doThrow(new RuntimeException("OOM command not allowed when used memory > 'maxmemory'"))
            .when(valueOperations).set(eq(CONTENT_KEY), anyString(), eq(1807L), eq(TimeUnit.SECONDS));

        cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(meterRegistry.counter("novel.chapter.cache.write", "result", "error").count())
            .isEqualTo(1);
    }

    @Test
    void cacheMissRecordsDatabaseLoad() {
        arrangeCacheMissWithLockWinner(content("database"));

        cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(meterRegistry.timer("novel.chapter.cache.load").count()).isEqualTo(1);
    }

    @Test
    void lockContentionRetryExhaustionRecordsDatabaseLoad() {
        when(valueOperations.get(CONTENT_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
            .thenReturn(false);
        when(loader.get()).thenReturn(content("database"));

        cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(meterRegistry.timer("novel.chapter.cache.load").count()).isEqualTo(1);
    }

    @Test
    void loaderFailurePropagatesUnchangedAndRecordsDatabaseLoad() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(valueOperations.get(CONTENT_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
            .thenReturn(true);
        when(loader.get()).thenThrow(failure);

        Throwable thrown = catchThrowable(() -> cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader));

        assertThat(thrown).isSameAs(failure);
        assertThat(meterRegistry.timer("novel.chapter.cache.load").count()).isEqualTo(1);
    }

    @Test
    void cacheMissLoadsDatabaseAndStoresSerializedContentWithTtl() throws Exception {
        BookContent loaded = content("database");
        when(valueOperations.get(CONTENT_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(loader.get()).thenReturn(loaded);

        BookContent result = cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(result).isSameAs(loaded);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(CONTENT_KEY), json.capture(), eq(1807L), eq(TimeUnit.SECONDS));
        assertThat(objectMapper.readValue(json.getValue(), BookContent.class).getContent()).isEqualTo("database");
        verify(loader).get();
    }

    @Test
    void missingContentStoresShortLivedNullMarker() {
        when(valueOperations.get(CONTENT_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(loader.get()).thenReturn(null);

        assertThat(cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader)).isNull();

        verify(valueOperations).set(CONTENT_KEY, "__NULL__", 60L, TimeUnit.SECONDS);
        verify(loader).get();
    }

    @Test
    void nullMarkerReturnsNullWithoutCallingLoader() {
        when(valueOperations.get(CONTENT_KEY)).thenReturn("__NULL__");

        assertThat(cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader)).isNull();

        verifyNoInteractions(loader);
    }

    @Test
    void lockWinnerDoubleCheckDoesNotAddAnotherRequestLookupResult() throws Exception {
        String cachedAfterLock = objectMapper.writeValueAsString(content("won elsewhere"));
        when(valueOperations.get(CONTENT_KEY)).thenReturn(null, cachedAfterLock);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);

        BookContent result = cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(result.getContent()).isEqualTo("won elsewhere");
        verifyNoInteractions(loader);
        verify(valueOperations, times(2)).get(CONTENT_KEY);
        assertThat(meterRegistry.counter("novel.chapter.cache.lookup", "result", "miss").count())
            .isEqualTo(1);
        assertThat(meterRegistry.counter("novel.chapter.cache.lookup", "result", "hit").count())
            .isZero();
    }

    @Test
    void lockLoserReadsValueAfterBoundedWaitWithoutCallingLoader() throws Exception {
        String cachedAfterWait = objectMapper.writeValueAsString(content("winner value"));
        when(valueOperations.get(CONTENT_KEY)).thenReturn(null, null, cachedAfterWait);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(false);

        BookContent result = cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(result.getContent()).isEqualTo("winner value");
        verify(sleeper, times(2)).accept(50L);
        verifyNoInteractions(loader);
    }

    @Test
    void unlockUsesTokenCheckingLuaScript() {
        when(valueOperations.get(CONTENT_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(loader.get()).thenReturn(content("loaded"));

        cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        verify(redisTemplate).execute(eq(ChapterContentCache.UNLOCK_SCRIPT), eq(List.of(LOCK_KEY)), anyString());
    }

    @Test
    void redisFailureFallsBackToLoaderWithoutInfiniteRetry() {
        BookContent loaded = content("fallback");
        when(valueOperations.get(CONTENT_KEY)).thenThrow(new RedisConnectionFailureException("offline"));
        when(loader.get()).thenReturn(loaded);

        assertThat(cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader)).isSameAs(loaded);

        verify(loader).get();
        verify(valueOperations).get(CONTENT_KEY);
        verifyNoInteractions(sleeper);
    }

    @Test
    void redisWriteOomFallsBackToLoadedContent() {
        BookContent loaded = content("loaded from database");
        when(valueOperations.get(CONTENT_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(loader.get()).thenReturn(loaded);
        doThrow(new RuntimeException("OOM command not allowed when used memory > 'maxmemory'"))
            .when(valueOperations).set(eq(CONTENT_KEY), anyString(), eq(1807L), eq(TimeUnit.SECONDS));

        BookContent result = cache.getOrLoad(BOOK_ID, CHAPTER_ID, loader);

        assertThat(result).isSameAs(loaded);
        verify(redisTemplate).execute(eq(ChapterContentCache.UNLOCK_SCRIPT), eq(List.of(LOCK_KEY)), anyString());
    }

    @Test
    void evictDeletesOnlyRequestedChapterKey() {
        cache.evict(BOOK_ID, CHAPTER_ID);

        verify(redisTemplate).delete(CONTENT_KEY);
        verify(redisTemplate, never()).delete(LOCK_KEY);
    }

    @Test
    void evictAfterCommitDoesNotDeleteBeforeCommitAndDeletesAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        cache.evictAfterCommit(BOOK_ID, CHAPTER_ID);

        verify(redisTemplate, never()).delete(anyString());
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.getFirst().afterCommit();
        verify(redisTemplate).delete(CONTENT_KEY);
    }

    @Test
    void evictAfterCommitDoesNotDeleteAfterRollback() {
        TransactionSynchronizationManager.initSynchronization();

        cache.evictAfterCommit(BOOK_ID, CHAPTER_ID);

        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(redisTemplate, never()).delete(anyString());
    }

    private BookContent content(String value) {
        BookContent content = new BookContent();
        content.setId(303L);
        content.setIndexId(CHAPTER_ID);
        content.setContent(value);
        return content;
    }

    private void arrangeCacheMissWithLockWinner(BookContent loaded) {
        when(valueOperations.get(CONTENT_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
            .thenReturn(true);
        when(loader.get()).thenReturn(loaded);
    }
}
