package com.java2nb.novel.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.entity.BookContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    static final String CONTENT_KEY_PREFIX = "novel:chapter:v1:";
    static final String LOCK_KEY_PREFIX = "novel:chapter:lock:v1:";
    static final String NULL_MARKER = "__NULL__";
    static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "return redis.call('del', KEYS[1]) else return 0 end",
        Long.class
    );

    private static final int DEFAULT_CONTENT_TTL_SECONDS = 1800;
    private static final int DEFAULT_JITTER_BOUND_SECONDS = 300;
    private static final int DEFAULT_NULL_TTL_SECONDS = 60;
    private static final int DEFAULT_LOCK_TTL_SECONDS = 10;
    private static final int DEFAULT_RETRY_COUNT = 10;
    private static final int DEFAULT_RETRY_DELAY_MILLIS = 50;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int contentTtlSeconds;
    private final int jitterBoundSeconds;
    private final int nullTtlSeconds;
    private final int lockTtlSeconds;
    private final int retryCount;
    private final int retryDelayMillis;
    private final LongConsumer sleeper;
    private final IntSupplier jitterSource;

    public ChapterContentCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
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
            () -> ThreadLocalRandom.current().nextInt(DEFAULT_JITTER_BOUND_SECONDS + 1)
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
        IntSupplier jitterSource
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.contentTtlSeconds = contentTtlSeconds;
        this.jitterBoundSeconds = jitterBoundSeconds;
        this.nullTtlSeconds = nullTtlSeconds;
        this.lockTtlSeconds = lockTtlSeconds;
        this.retryCount = retryCount;
        this.retryDelayMillis = retryDelayMillis;
        this.sleeper = sleeper;
        this.jitterSource = jitterSource;
    }

    public BookContent getOrLoad(Long bookId, Long bookIndexId, Supplier<BookContent> loader) {
        String contentKey = contentKey(bookId, bookIndexId);
        CacheValue firstRead;
        try {
            firstRead = read(contentKey);
        } catch (RuntimeException exception) {
            return loadAfterCacheFailure(contentKey, loader, exception);
        }
        if (firstRead.present()) {
            return firstRead.content();
        }

        String lockKey = lockKey(bookId, bookIndexId);
        String token = UUID.randomUUID().toString();
        boolean lockAcquired;
        try {
            lockAcquired = Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, lockTtlSeconds, TimeUnit.SECONDS));
        } catch (RuntimeException exception) {
            return loadAfterCacheFailure(contentKey, loader, exception);
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

            BookContent loaded = loader.get();
            try {
                store(contentKey, loaded);
            } catch (RuntimeException exception) {
                log.warn("Failed to store chapter cache key={}", contentKey, exception);
            }
            return loaded;
        } finally {
            unlock(lockKey, token);
        }
    }

    public void evict(Long bookId, Long bookIndexId) {
        String key = contentKey(bookId, bookIndexId);
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Failed to evict chapter cache key={}", key, exception);
        }
    }

    public void evictAfterCommit(Long bookId, Long bookIndexId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(bookId, bookIndexId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(bookId, bookIndexId);
            }
        });
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
        return loader.get();
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

    private void store(String key, BookContent content) {
        if (content == null) {
            redisTemplate.opsForValue().set(key, NULL_MARKER, nullTtlSeconds, TimeUnit.SECONDS);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(content);
            int jitter = jitterBoundSeconds == 0
                ? 0
                : Math.floorMod(jitterSource.getAsInt(), jitterBoundSeconds + 1);
            redisTemplate.opsForValue().set(
                key,
                json,
                (long) contentTtlSeconds + jitter,
                TimeUnit.SECONDS
            );
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
        return loader.get();
    }

    private static String contentKey(Long bookId, Long bookIndexId) {
        return CONTENT_KEY_PREFIX + bookId + ":" + bookIndexId;
    }

    private static String lockKey(Long bookId, Long bookIndexId) {
        return LOCK_KEY_PREFIX + bookId + ":" + bookIndexId;
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
