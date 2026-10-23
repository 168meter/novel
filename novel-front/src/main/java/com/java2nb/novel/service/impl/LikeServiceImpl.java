package com.java2nb.novel.service.impl;

import com.java2nb.novel.service.LikeService;
import com.java2nb.novel.mapper.BookCommentMapper;
import com.java2nb.novel.mapper.BookCommentReplyMapper;
import com.java2nb.novel.core.enums.ResponseStatus;
import io.github.xxyopen.web.exception.BusinessException;
import io.github.xxyopen.model.resp.SysResultCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DataAccessException;
import org.springframework.scripting.support.StaticScriptSource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scripting.support.ResourceScriptSource;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * @author xiongxiaoyang
 * @date 2025/7/12
 */
@Service
@Slf4j
public class LikeServiceImpl implements LikeService {

    private static final long READ_FAILURE_LOG_INTERVAL = 1000L;

    private final StringRedisTemplate redisTemplate;
    private final BookCommentMapper comments;
    private final BookCommentReplyMapper replies;

    public LikeServiceImpl(StringRedisTemplate redisTemplate,
                           @Qualifier("bookCommentMapper") BookCommentMapper comments,
                           @Qualifier("bookCommentReplyMapper") BookCommentReplyMapper replies) {
        this.redisTemplate = redisTemplate;
        this.comments = comments;
        this.replies = replies;
    }

    private DefaultRedisScript<Long> toggleLikeScript;
    private DefaultRedisScript<List> setStateScript;
    private DefaultRedisScript<List> readStateScript;

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
        setStateScript=new DefaultRedisScript<>();
        setStateScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/reaction-set.lua")));
        setStateScript.setResultType(List.class);
        readStateScript=new DefaultRedisScript<>();
        readStateScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/reaction-read.lua")));
        readStateScript.setResultType(List.class);
    }

    @Deprecated(since="2026-10-19",forRemoval=false)
    public Long toggleCommentLike(Long commentId, Long userId) {
        validateTarget(commentId, userId, false);
        return executeToggle(COMMENT_LIKE_KEY_PREFIX + commentId, userId);
    }

    @Override
    @Deprecated(since="2026-10-19",forRemoval=false)
    public Long toggleCommentUnLike(Long commentId, Long userId) {
        validateTarget(commentId, userId, false);
        return executeToggle(COMMENT_UN_LIKE_KEY_PREFIX + commentId, userId);
    }

    public Long getCommentLikesCount(Long commentId) {
        return readDisplayCount(() -> redisTemplate.opsForSet().size(COMMENT_LIKE_KEY_PREFIX + commentId));
    }

    @Override
    public Long getCommentUnLikesCount(Long commentId) {
        return readDisplayCount(() -> redisTemplate.opsForSet().size(COMMENT_UN_LIKE_KEY_PREFIX + commentId));
    }

    @Deprecated(since="2026-10-19",forRemoval=false)
    public Long toggleReplyLike(Long replyId, Long userId) {
        validateTarget(replyId, userId, true);
        return executeToggle(COMMENT_REPLY_LIKE_KEY_PREFIX + replyId, userId);
    }

    @Override
    @Deprecated(since="2026-10-19",forRemoval=false)
    public Long toggleReplyUnLike(Long replyId, Long userId) {
        validateTarget(replyId, userId, true);
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
        Long count = redisTemplate.execute(toggleLikeScript, Collections.singletonList(key), String.valueOf(userId));
        if (count == null) {
            throw new BusinessException(ResponseStatus.AUTH_UNAVAILABLE);
        }
        return count;
    }

    @Override
    public ReactionState setCommentReaction(Long id,Long userId,boolean like,boolean active) {
        validateTarget(id,userId,false);return state(id,userId,false,true,like,active);
    }
    @Override
    public ReactionState setReplyReaction(Long id,Long userId,boolean like,boolean active) {
        validateTarget(id,userId,true);return state(id,userId,true,true,like,active);
    }
    @Override
    public ReactionState getCommentReaction(Long id,Long userId) {
        validateTarget(id,userId,false);return state(id,userId,false,false,false,false);
    }
    @Override
    public ReactionState getReplyReaction(Long id,Long userId) {
        validateTarget(id,userId,true);return state(id,userId,true,false,false,false);
    }

    private ReactionState state(Long id,Long userId,boolean reply,boolean write,boolean like,boolean active) {
        var keys=List.of((reply?COMMENT_REPLY_LIKE_KEY_PREFIX:COMMENT_LIKE_KEY_PREFIX)+id,
            (reply?COMMENT_REPLY_UN_LIKE_KEY_PREFIX:COMMENT_UN_LIKE_KEY_PREFIX)+id);
        try {
            List<?> values=write
                ?redisTemplate.execute(setStateScript,keys,String.valueOf(userId),like?"1":"2",active?"1":"0")
                :redisTemplate.execute(readStateScript,keys,String.valueOf(userId));
            if(values==null || values.size()!=4 || values.stream().anyMatch(v->!(v instanceof Long))) throw unavailable();
            long likes=(Long)values.get(0),dislikes=(Long)values.get(1),liked=(Long)values.get(2),disliked=(Long)values.get(3);
            if(likes<0 || dislikes<0 || liked<0 || liked>1 || disliked<0 || disliked>1 || liked>likes || disliked>dislikes) throw unavailable();
            return new ReactionState(likes,dislikes,liked==1,disliked==1);
        } catch(DataAccessException failure) {throw unavailable();}
    }
    private static BusinessException unavailable() {return new BusinessException(ResponseStatus.AUTH_UNAVAILABLE);}

    /** A read-before-Redis guard, deliberately not presented as a cross-store transaction. */
    private void validateTarget(Long targetId, Long userId, boolean reply) {
        if(userId==null || userId<=0) throw new BusinessException(ResponseStatus.NO_LOGIN);
        if(targetId==null || targetId<=0) throw new BusinessException(SysResultCode.PARAM_ERROR);
        if(reply) {
            var target=replies.selectByPrimaryKey(targetId).orElse(null);
            if(target==null || !Byte.valueOf((byte)1).equals(target.getAuditStatus()) || target.getCommentId()==null) throw new BusinessException(ResponseStatus.COMMENT_NOT_AVAILABLE);
            var parent=comments.selectByPrimaryKey(target.getCommentId()).orElse(null);
            if(parent==null || !Byte.valueOf((byte)1).equals(parent.getAuditStatus())) throw new BusinessException(ResponseStatus.COMMENT_NOT_AVAILABLE);
        } else {
            var target=comments.selectByPrimaryKey(targetId).orElse(null);
            if(target==null || !Byte.valueOf((byte)1).equals(target.getAuditStatus())) throw new BusinessException(ResponseStatus.COMMENT_NOT_AVAILABLE);
        }
    }
}
