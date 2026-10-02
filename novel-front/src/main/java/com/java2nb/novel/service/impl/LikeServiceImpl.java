package com.java2nb.novel.service.impl;

import com.java2nb.novel.service.LikeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DataAccessException;
import org.springframework.scripting.support.StaticScriptSource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * @author xiongxiaoyang
 * @date 2025/7/12
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LikeServiceImpl implements LikeService {

    private static final long READ_FAILURE_LOG_INTERVAL = 1000L;

    private final StringRedisTemplate redisTemplate;

    private DefaultRedisScript<Long> toggleLikeScript;

    private final AtomicLong displayReadFailures = new AtomicLong();

    private static final String COMMENT_LIKE_KEY_PREFIX = "like:comment:";
    private static final String COMMENT_REPLY_LIKE_KEY_PREFIX = "like:comment:reply:";
    private static final String COMMENT_UN_LIKE_KEY_PREFIX = "unlike:comment:";
    private static final String COMMENT_REPLY_UN_LIKE_KEY_PREFIX = "unlike:comment:reply:";

    @PostConstruct
    public void init() {
        // Lua 脚本保证原子性操作
        String script = """
                local key = KEYS[1]
                local userId = ARGV[1]
            
                local isLiked = redis.call('SISMEMBER', key, userId)
            
                if isLiked == 1 then
                    redis.call('SREM', key, userId)
                else
                    redis.call('SADD', key, userId)
                end
            
                return redis.call('SCARD', key)
            """;

        toggleLikeScript = new DefaultRedisScript<>();
        toggleLikeScript.setScriptSource(new StaticScriptSource(script));
        toggleLikeScript.setResultType(Long.class);
    }

    public Long toggleCommentLike(Long commentId, Long userId) {
        return executeToggle(COMMENT_LIKE_KEY_PREFIX + commentId, userId);
    }

    @Override
    public Long toggleCommentUnLike(Long commentId, Long userId) {
        return executeToggle(COMMENT_UN_LIKE_KEY_PREFIX + commentId, userId);
    }

    public Long getCommentLikesCount(Long commentId) {
        return readDisplayCount(() -> redisTemplate.opsForSet().size(COMMENT_LIKE_KEY_PREFIX + commentId));
    }

    @Override
    public Long getCommentUnLikesCount(Long commentId) {
        return readDisplayCount(() -> redisTemplate.opsForSet().size(COMMENT_UN_LIKE_KEY_PREFIX + commentId));
    }

    public Long toggleReplyLike(Long replyId, Long userId) {
        return executeToggle(COMMENT_REPLY_LIKE_KEY_PREFIX + replyId, userId);
    }

    @Override
    public Long toggleReplyUnLike(Long replyId, Long userId) {
        return executeToggle(COMMENT_REPLY_UN_LIKE_KEY_PREFIX + replyId, userId);
    }

    public Long getReplyLikesCount(Long replyId) {
        return readDisplayCount(() -> redisTemplate.opsForSet().size(COMMENT_REPLY_LIKE_KEY_PREFIX + replyId));
    }

    @Override
    public Long getReplyUnLikesCount(Long replyId) {
        return readDisplayCount(() -> redisTemplate.opsForSet().size(COMMENT_REPLY_UN_LIKE_KEY_PREFIX + replyId));
    }

    private Long readDisplayCount(Supplier<Long> countLoader) {
        try {
            Long count = countLoader.get();
            return count == null ? 0L : count;
        } catch (DataAccessException unavailable) {
            long failures = displayReadFailures.incrementAndGet();
            if (failures == 1L || failures % READ_FAILURE_LOG_INTERVAL == 0L) {
                log.warn("Comment reaction display cache read failures={}; using zero counts", failures);
            }
            return 0L;
        }
    }

    private Long executeToggle(String key, Long userId) {
        return redisTemplate.execute(toggleLikeScript, Collections.singletonList(key), String.valueOf(userId));
    }
}
