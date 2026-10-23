package com.java2nb.novel.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.cache.ChapterContentCacheKey;
import com.java2nb.novel.entity.BookContent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

@Slf4j
@Component
public class ChapterContentCache {

    static final String NULL_MARKER = "__NULL__";
    static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "return redis.call('del', KEYS[1]) else return 0 end",
        Long.class
    );
    static final DefaultRedisScript<Long> FILL_IF_OWNER_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[2]) == ARGV[1] then "
            + "redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3]); return 1 else return 0 end",
        Long.class
    );

    private static final int DEFAULT_CONTENT_TTL_SECONDS = 1800;
    private static final int DEFAULT_JITTER_BOUND_SECONDS = 300;
    private static final int DEFAULT_NULL_TTL_SECONDS = 60;
    private static final int DEFAULT_LOCK_TTL_SECONDS = 10;
    private static final int DEFAULT_RETRY_COUNT = 10;
    private static final int DEFAULT_RETRY_DELAY_MILLIS = 50;

    private final StringRedisTemplate redisTemplate;
    private final FrontChapterCacheInvalidator cacheInvalidator;
    private final ObjectMapper objectMapper;
    private final int contentTtlSeconds;
    private final int jitterBoundSeconds;
    private final int nullTtlSeconds;
    private final int lockTtlSeconds;
    private final int retryCount;
    private final int retryDelayMillis;
    private final LongConsumer sleeper;
    private final IntSupplier jitterSource;
    private final Counter initialLookupHitCounter;
    private final Counter initialLookupMissCounter;
    private final Counter initialLookupErrorCounter;
    private final Counter lockAcquiredCounter;
    private final Counter lockContendedCounter;
    private final Counter lockErrorCounter;
    private final Counter writeSuccessCounter;
    private final Counter writeErrorCounter;
    private final Counter writeRejectedCounter;
    private final Timer loadTimer;

    @Autowired
    public ChapterContentCache(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry
    ) {
        this(
            redisTemplate,
            objectMapper,
            DEFAULT_CONTENT_TTL_SECONDS,
            DEFAULT_JITTER_BOUND_SECONDS,
            DEFAULT_NULL_TTL_SECONDS,
            DEFAULT_LOCK_TTL_SECONDS,
            DEFAULT_RETRY_COUNT,
            DEFAULT_RETRY_DELAY_MILLIS,
            ChapterContentCache::sleep,
            () -> ThreadLocalRandom.current().nextInt(DEFAULT_JITTER_BOUND_SECONDS + 1),
            meterRegistry
        );
    }

    ChapterContentCache(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        int contentTtlSeconds,
        int jitterBoundSeconds,
        int nullTtlSeconds,
        int lockTtlSeconds,
        int retryCount,
        int retryDelayMillis,
        LongConsumer sleeper,
        IntSupplier jitterSource,
        MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.cacheInvalidator = new FrontChapterCacheInvalidator(redisTemplate, meterRegistry);
        this.objectMapper = objectMapper;
        this.contentTtlSeconds = contentTtlSeconds;
        this.jitterBoundSeconds = jitterBoundSeconds;
        this.nullTtlSeconds = nullTtlSeconds;
        this.lockTtlSeconds = lockTtlSeconds;
        this.retryCount = retryCount;
        this.retryDelayMillis = retryDelayMillis;
        this.sleeper = sleeper;
        this.jitterSource = jitterSource;
        this.initialLookupHitCounter = meterRegistry.counter("novel.chapter.cache.lookup", "result", "hit");
        this.initialLookupMissCounter = meterRegistry.counter("novel.chapter.cache.lookup", "result", "miss");
        this.initialLookupErrorCounter = meterRegistry.counter("novel.chapter.cache.lookup", "result", "error");
        this.lockAcquiredCounter = meterRegistry.counter("novel.chapter.cache.lock", "result", "acquired");
        this.lockContendedCounter = meterRegistry.counter("novel.chapter.cache.lock", "result", "contended");
        this.lockErrorCounter = meterRegistry.counter("novel.chapter.cache.lock", "result", "error");
        this.writeSuccessCounter = meterRegistry.counter("novel.chapter.cache.write", "result", "success");
        this.writeErrorCounter = meterRegistry.counter("novel.chapter.cache.write", "result", "error");
        this.writeRejectedCounter = meterRegistry.counter("novel.chapter.cache.write", "result", "rejected");
        this.loadTimer = meterRegistry.timer("novel.chapter.cache.load");
    }

    public BookContent getOrLoad(Long bookId, Long bookIndexId, Supplier<BookContent> loader) {
        String contentKey = contentKey(bookId, bookIndexId);
        CacheValue firstRead;
        try {
            firstRead = read(contentKey);
        } catch (RuntimeException exception) {
            initialLookupErrorCounter.increment();
            return loadAfterCacheFailure(contentKey, loader, exception);
        }
        if (firstRead.present()) {
            initialLookupHitCounter.increment();
            return firstRead.content();
        }
        initialLookupMissCounter.increment();

        String lockKey = lockKey(bookId, bookIndexId);
        String token = UUID.randomUUID().toString();
        boolean lockAcquired;
        try {
            lockAcquired = Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, lockTtlSeconds, TimeUnit.SECONDS));
        } catch (RuntimeException exception) {
            lockErrorCounter.increment();
            return loadAfterCacheFailure(contentKey, loader, exception);
        }
        if (lockAcquired) {
            lockAcquiredCounter.increment();
        } else {
            lockContendedCounter.increment();
        }

        if (!lockAcquired) {
            return waitForWinner(contentKey, loader);
        }

        try {
            CacheValue secondRead;
            try {
                secondRead = read(contentKey);
            } catch (RuntimeException exception) {
                return loadAfterCacheFailure(contentKey, loader, exception);
            }
            if (secondRead.present()) {
                return secondRead.content();
            }

            BookContent loaded = load(loader);
            try {
                if (store(contentKey, lockKey, token, loaded)) writeSuccessCounter.increment();
                else writeRejectedCounter.increment();
            } catch (RuntimeException exception) {
                writeErrorCounter.increment();
                log.warn("Failed to store chapter cache key={}", contentKey, exception);
            }
            return loaded;
        } finally {
            unlock(lockKey, token);
        }
    }

    public void evict(Long bookId, Long bookIndexId) {
        cacheInvalidator.evictNow(bookId, bookIndexId);
    }

    public void evictAfterCommit(Long bookId, Long bookIndexId) {
        cacheInvalidator.evictAfterCommit(bookId, bookIndexId);
    }

    private BookContent waitForWinner(String contentKey, Supplier<BookContent> loader) {
        for (int attempt = 0; attempt < retryCount; attempt++) {
            sleeper.accept((long) retryDelayMillis);
            try {
                CacheValue value = read(contentKey);
                if (value.present()) {
                    return value.content();
                }
            } catch (RuntimeException exception) {
                return loadAfterCacheFailure(contentKey, loader, exception);
            }
        }
        return load(loader);
    }

    private CacheValue read(String key) {
        String cached = redisTemplate.opsForValue().get(key);
        if (cached == null) {
            return CacheValue.miss();
        }
        if (NULL_MARKER.equals(cached)) {
            return CacheValue.hit(null);
        }
        try {
            return CacheValue.hit(objectMapper.readValue(cached, BookContent.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid chapter cache value for key=" + key, exception);
        }
    }

    private boolean store(String key, String lockKey, String token, BookContent content) {
        try {
            String json = content == null ? NULL_MARKER : objectMapper.writeValueAsString(content);
            int jitter = jitterBoundSeconds == 0
                ? 0
                : Math.floorMod(jitterSource.getAsInt(), jitterBoundSeconds + 1);
            long ttl = content == null ? nullTtlSeconds : (long) contentTtlSeconds + jitter;
            Long filled = redisTemplate.execute(FILL_IF_OWNER_SCRIPT, List.of(key, lockKey), token, json, Long.toString(ttl));
            if (filled == null) throw new IllegalStateException("Chapter fill returned no Redis result");
            return filled == 1L;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize chapter cache value for key=" + key, exception);
        }
    }

    private void unlock(String lockKey, String token) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        } catch (RuntimeException exception) {
            log.warn("Failed to release chapter cache lock key={}", lockKey, exception);
        }
    }

    private BookContent loadAfterCacheFailure(
        String contentKey,
        Supplier<BookContent> loader,
        RuntimeException exception
    ) {
        log.warn("Chapter cache unavailable, falling back to loader key={}", contentKey, exception);
        return load(loader);
    }

    private BookContent load(Supplier<BookContent> loader) {
        return loadTimer.record(loader);
    }

    private static String contentKey(Long bookId, Long bookIndexId) {
        return ChapterContentCacheKey.of(bookId, bookIndexId);
    }

    private static String lockKey(Long bookId, Long bookIndexId) {
        return ChapterContentCacheKey.lockOf(bookId, bookIndexId);
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for chapter cache", exception);
        }
    }

    private record CacheValue(boolean present, BookContent content) {
        private static CacheValue miss() {
            return new CacheValue(false, null);
        }

        private static CacheValue hit(BookContent content) {
            return new CacheValue(true, content);
        }
    }
}
