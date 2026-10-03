package com.java2nb.novel.service.cache;

import com.java2nb.novel.cache.ChapterCacheIdentity;
import com.java2nb.novel.cache.ChapterContentCacheKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "novel.redis.it.enabled", matches = "true")
class ChapterCacheInvalidatorRedisIT {

    private static final long BOOK_ID = 8_100_001L;
    private static final long NULL_CHAPTER_ID = 8_200_001L;
    private static final long UPDATE_CHAPTER_ID = 8_200_002L;
    private static final long DELETE_CHAPTER_ID = 8_200_003L;
    private static final long ROLLBACK_CHAPTER_ID = 8_200_004L;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private FrontChapterCacheInvalidator invalidator;

    @BeforeEach
    void setUp() {
        String password = System.getenv("NOVEL_REDIS_TEST_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("NOVEL_REDIS_TEST_PASSWORD is required for Redis integration tests.");
        }
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration("127.0.0.1", 6380);
        server.setPassword(RedisPassword.of(password));
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))
            .shutdownTimeout(Duration.ZERO)
            .build();
        connectionFactory = new LettuceConnectionFactory(server, client);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        invalidator = new FrontChapterCacheInvalidator(redis, new SimpleMeterRegistry());
        cleanupKeys();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        cleanupKeys();
        connectionFactory.destroy();
    }

    @Test
    void committedInsertRemovesNullMarker() {
        assertCommitDeletes(NULL_CHAPTER_ID, "__NULL__");
    }

    @Test
    void committedUpdateRemovesStaleValue() {
        assertCommitDeletes(UPDATE_CHAPTER_ID, "V1");
    }

    @Test
    void committedDeleteRemovesCachedValue() {
        assertCommitDeletes(DELETE_CHAPTER_ID, "V1");
    }

    @Test
    void rollbackKeepsCachedValue() {
        String key = key(ROLLBACK_CHAPTER_ID);
        redis.opsForValue().set(key, "V1");
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAfterCommit(List.of(new ChapterCacheIdentity(BOOK_ID, ROLLBACK_CHAPTER_ID)));
        TransactionSynchronization callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
        callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(redis.opsForValue().get(key)).isEqualTo("V1");
    }

    private void assertCommitDeletes(long chapterId, String cachedValue) {
        String key = key(chapterId);
        redis.opsForValue().set(key, cachedValue);
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAfterCommit(List.of(new ChapterCacheIdentity(BOOK_ID, chapterId)));
        assertThat(redis.hasKey(key)).isTrue();
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();

        assertThat(redis.hasKey(key)).isFalse();
    }

    private void cleanupKeys() {
        redis.delete(List.of(
            key(NULL_CHAPTER_ID),
            key(UPDATE_CHAPTER_ID),
            key(DELETE_CHAPTER_ID),
            key(ROLLBACK_CHAPTER_ID)
        ));
    }

    private String key(long chapterId) {
        return ChapterContentCacheKey.of(BOOK_ID, chapterId);
    }
}
