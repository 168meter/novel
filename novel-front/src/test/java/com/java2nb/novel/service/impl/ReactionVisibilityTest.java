package com.java2nb.novel.service.impl;
import com.java2nb.novel.mapper.*;
import com.java2nb.novel.entity.*;
import io.github.xxyopen.web.exception.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class ReactionVisibilityTest {
 @ParameterizedTest @CsvSource({"0,false","2,false","0,true","2,true"})
 void oldCommentToggleRejectsUnpublishedTargets(int status,boolean dislike) {
  var redis=mock(StringRedisTemplate.class);var comments=mock(BookCommentMapper.class);
  var c=new BookComment();c.setAuditStatus((byte)status);when(comments.selectByPrimaryKey(1L)).thenReturn(Optional.of(c));
  when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L);
  var service=new LikeServiceImpl(redis,comments,mock(BookCommentReplyMapper.class));service.init();
  assertThatThrownBy(()->{if(dislike)service.toggleCommentUnLike(1L,7L);else service.toggleCommentLike(1L,7L);}).isInstanceOf(BusinessException.class);
  verifyNoInteractions(redis);
 }
 @ParameterizedTest @CsvSource({"0,1,false","2,1,true","1,0,false","1,2,true"})
 void oldReplyToggleRequiresApprovedReplyAndApprovedParent(int self,int parent,boolean dislike) {
  var redis=mock(StringRedisTemplate.class);var comments=mock(BookCommentMapper.class);var replies=mock(BookCommentReplyMapper.class);
  var c=new BookComment();c.setAuditStatus((byte)parent);when(comments.selectByPrimaryKey(1L)).thenReturn(Optional.of(c));
  var reply=new BookCommentReply();reply.setCommentId(1L);reply.setAuditStatus((byte)self);when(replies.selectByPrimaryKey(2L)).thenReturn(Optional.of(reply));
  when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(1L);
  var service=new LikeServiceImpl(redis,comments,replies);service.init();
  assertThatThrownBy(()->{if(dislike)service.toggleReplyUnLike(2L,7L);else service.toggleReplyLike(2L,7L);}).isInstanceOf(BusinessException.class);
  verifyNoInteractions(redis);
 }
}
