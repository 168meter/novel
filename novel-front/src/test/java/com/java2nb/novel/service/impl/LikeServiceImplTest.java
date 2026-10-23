package com.java2nb.novel.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.java2nb.novel.mapper.BookCommentMapper;
import com.java2nb.novel.mapper.BookCommentReplyMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LikeServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void displayCountsDefaultToZeroWhenRedisIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.size(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        LikeServiceImpl service = new LikeServiceImpl(redis, mock(BookCommentMapper.class), mock(BookCommentReplyMapper.class));

        assertThat(service.getCommentLikesCount(1L)).isZero();
        assertThat(service.getCommentUnLikesCount(1L)).isZero();
        assertThat(service.getReplyLikesCount(2L)).isZero();
        assertThat(service.getReplyUnLikesCount(2L)).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void displayCountsKeepNormalRedisValues() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.size("like:comment:1")).thenReturn(4L);
        when(sets.size("unlike:comment:1")).thenReturn(2L);
        LikeServiceImpl service = new LikeServiceImpl(redis, mock(BookCommentMapper.class), mock(BookCommentReplyMapper.class));

        assertThat(service.getCommentLikesCount(1L)).isEqualTo(4L);
        assertThat(service.getCommentUnLikesCount(1L)).isEqualTo(2L);
    }

    @Test
    void nonexistentTargetAndAnonymousUserCannotCreateReactionKeys() {
        var redis = mock(StringRedisTemplate.class);
        var service = new LikeServiceImpl(redis, mock(BookCommentMapper.class), mock(BookCommentReplyMapper.class));
        service.init();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.toggleCommentLike(99L, 1L))
            .isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.toggleReplyLike(99L, null))
            .isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(redis);
    }
}
