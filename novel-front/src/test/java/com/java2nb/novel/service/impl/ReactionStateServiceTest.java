package com.java2nb.novel.service.impl;
import com.java2nb.novel.mapper.*;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.service.LikeService;
import io.github.xxyopen.web.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.RedisConnectionFailureException;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class ReactionStateServiceTest {
 StringRedisTemplate redis;BookCommentMapper comments;BookCommentReplyMapper replies;LikeServiceImpl service;
 @BeforeEach void setup(){redis=mock(StringRedisTemplate.class);comments=mock(BookCommentMapper.class);replies=mock(BookCommentReplyMapper.class);
  var c=new BookComment();c.setAuditStatus((byte)1);when(comments.selectByPrimaryKey(1L)).thenReturn(Optional.of(c));
  var r=new BookCommentReply();r.setCommentId(1L);r.setAuditStatus((byte)1);when(replies.selectByPrimaryKey(2L)).thenReturn(Optional.of(r));
  service=new LikeServiceImpl(redis,comments,replies);service.init();}
 @Test void realRedisFixtureRejectsFrozenOrUnspecifiedEndpointBeforeConnecting(){
  assertThatThrownBy(()->LikeConcurrencyRedisIT.privateRedisPort(null)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->LikeConcurrencyRedisIT.privateRedisPort("26380")).isInstanceOf(IllegalArgumentException.class);
  assertThat(LikeConcurrencyRedisIT.privateRedisPort("26381")).isEqualTo(26381);
 }
 @Test void explicitSnapshotIncludesBothCountsAndCurrentUserSelections(){
  when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(List.of(3L,2L,1L,1L));
  assertThat(service.setCommentReaction(1L,7L,true,true)).isEqualTo(new LikeService.ReactionState(3,2,true,true));
  assertThat(service.setReplyReaction(2L,7L,false,false)).isEqualTo(new LikeService.ReactionState(3,2,true,true));
 }
 @ParameterizedTest @CsvSource({"0,1","2,1","1,0","1,2"})
 void explicitReplySetAndReadRejectHiddenTargetsBeforeRedis(int self,int parent){
  var c=new BookComment();c.setAuditStatus((byte)parent);when(comments.selectByPrimaryKey(1L)).thenReturn(Optional.of(c));
  var r=new BookCommentReply();r.setCommentId(1L);r.setAuditStatus((byte)self);when(replies.selectByPrimaryKey(2L)).thenReturn(Optional.of(r));
  assertThatThrownBy(()->service.setReplyReaction(2L,7L,true,true)).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.setReplyReaction(2L,7L,false,false)).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.getReplyReaction(2L,7L)).isInstanceOf(BusinessException.class);verifyNoInteractions(redis);
 }
 @ParameterizedTest @CsvSource({"0","2"})
 void explicitCommentSetAndReadRejectHiddenTargetsBeforeRedis(int status){
  var c=new BookComment();c.setAuditStatus((byte)status);when(comments.selectByPrimaryKey(1L)).thenReturn(Optional.of(c));
  assertThatThrownBy(()->service.setCommentReaction(1L,7L,true,true)).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.setCommentReaction(1L,7L,false,false)).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.getCommentReaction(1L,7L)).isInstanceOf(BusinessException.class);verifyNoInteractions(redis);
 }
 @Test void anonymousInvalidMissingAndOrphanTargetsNeverReachRedis(){
  assertThatThrownBy(()->service.setCommentReaction(1L,null,true,true)).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.setCommentReaction(-1L,7L,true,true)).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.getCommentReaction(99L,7L)).isInstanceOf(BusinessException.class);
  when(comments.selectByPrimaryKey(1L)).thenReturn(Optional.empty());
  assertThatThrownBy(()->service.setReplyReaction(2L,7L,true,true)).isInstanceOf(BusinessException.class);verifyNoInteractions(redis);
 }
 @Test void nullOrMalformedRedisResultIsNotSuccessfulZeroState(){
  assertThatThrownBy(()->service.getCommentReaction(1L,7L)).isInstanceOf(BusinessException.class);
  when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(List.of(0L,0L,1L,0L));
  assertThatThrownBy(()->service.getCommentReaction(1L,7L)).isInstanceOf(BusinessException.class);
 }
 @Test void redisFailureIsExplicitForSetAndRead(){
  when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenThrow(new RedisConnectionFailureException("offline"));
  assertThatThrownBy(()->service.setCommentReaction(1L,7L,true,true)).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.getCommentReaction(1L,7L)).isInstanceOf(BusinessException.class);
 }
}
