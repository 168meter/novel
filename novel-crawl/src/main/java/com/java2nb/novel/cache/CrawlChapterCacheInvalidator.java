package com.java2nb.novel.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
public final class CrawlChapterCacheInvalidator {

    private static final long FAILURE_LOG_INTERVAL = 1_000L;

    private final StringRedisTemplate redisTemplate;
    private final Counter successCounter;
    private final Counter failedCounter;
    private final AtomicLong failureCount = new AtomicLong();

    public CrawlChapterCacheInvalidator(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.successCounter = meterRegistry.counter("novel.chapter.cache.eviction",
            "source", "crawl", "result", "success");
        this.failedCounter = meterRegistry.counter("novel.chapter.cache.eviction",
            "source", "crawl", "result", "failed");
    }

    public void evictAfterCommit(Collection<ChapterCacheIdentity> identities) {
        Set<String> keys = new LinkedHashSet<>();
        for (ChapterCacheIdentity identity : identities) {
            if (identity != null) {
                keys.add(identity.cacheKey());
                keys.add(ChapterContentCacheKey.lockOf(identity.bookId(), identity.bookIndexId()));
            }
        }
        if (keys.isEmpty()) {
            return;
        }

        Set<String> immutableKeys = Set.copyOf(keys);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(immutableKeys);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(immutableKeys);
            }
        });
    }

    private void evict(Set<String> keys) {
        try {
            redisTemplate.delete(keys);
            successCounter.increment();
        } catch (RuntimeException exception) {
            failedCounter.increment();
            long failures = failureCount.incrementAndGet();
            if (failures == 1L || failures % FAILURE_LOG_INTERVAL == 0L) {
                log.warn("Chapter cache eviction failed source=crawl count={} failureCount={} exceptionClass={}",
                    keys.size(), failures, exception.getClass().getSimpleName());
            }
        }
    }
}
