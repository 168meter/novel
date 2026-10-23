package com.java2nb.novel.service.impl;

import com.java2nb.novel.mapper.*;
import com.java2nb.novel.entity.BookComment;
import com.java2nb.novel.entity.BookCommentReply;
import com.java2nb.novel.service.LikeService;
import org.springframework.data.redis.connection.DataType;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** Reuses the existing real Redis fixture. Only previously absent random target keys; retain raw evidence and keys. */
@EnabledIfEnvironmentVariable(named="CORRECTNESS_REDIS_PASSWORD", matches=".+")
class LikeConcurrencyRedisIT {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    LikeServiceImpl service;
    long target;
    String key;
    boolean ownsKey;
    List<String> keys;BookCommentMapper comments;BookCommentReplyMapper replies;
    BookComment parent;BookCommentReply reply;String testName;
    static int privateRedisPort(String port) {
        if(!"26381".equals(port))throw new IllegalArgumentException("Real reaction tests require explicit private correctness Redis port 26381; use Run-ReactionChecks.ps1");
        return 26381;
    }
    @BeforeEach void setup(TestInfo info) throws Exception {
        testName=info.getTestMethod().orElseThrow().getName();
        var cfg=new RedisStandaloneConfiguration("127.0.0.1",privateRedisPort(System.getenv("CORRECTNESS_REDIS_PORT")));
        cfg.setPassword(System.getenv("CORRECTNESS_REDIS_PASSWORD"));
        factory=new LettuceConnectionFactory(cfg); factory.afterPropertiesSet();factory.start();
        redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();
        target=8_000_000_000_000_000_000L+ThreadLocalRandom.current().nextLong(1_000_000_000_000_000L);
        key="like:comment:"+target;
        keys=List.of(key,"unlike:comment:"+target,"like:comment:reply:"+target,"unlike:comment:reply:"+target);
        for(String k:keys) assertThat(redis.hasKey(k)).as("Random test key must be absent: %s",k).isFalse();
        ownsKey=true;snapshot("before");
        comments=mock(BookCommentMapper.class);replies=mock(BookCommentReplyMapper.class);
        parent=new BookComment();parent.setId(target);parent.setAuditStatus((byte)1);
        reply=new BookCommentReply();reply.setId(target);reply.setCommentId(target);reply.setAuditStatus((byte)1);
        when(comments.selectByPrimaryKey(target)).thenReturn(Optional.of(parent));when(replies.selectByPrimaryKey(target)).thenReturn(Optional.of(reply));
        service=new LikeServiceImpl(redis,comments,replies);service.init();
    }
    @AfterEach void retainEvidence() throws Exception {
        try{if(redis!=null && ownsKey)snapshot("after");}finally{if(factory!=null)factory.destroy();}
    }
    void snapshot(String phase) throws Exception {
        String evidence=System.getenv("CORRECTNESS_EVIDENCE");if(evidence==null || evidence.isBlank())return;
        var rows=new ArrayList<Map<String,Object>>();
        for(String k:keys){var row=new LinkedHashMap<String,Object>();row.put("key",k);var type=redis.type(k);row.put("type",type.name());
            if(type==DataType.SET){row.put("count",redis.opsForSet().size(k));row.put("members",new TreeSet<>(redis.opsForSet().members(k)));}rows.add(row);}
        var result=new LinkedHashMap<String,Object>();result.put("test",testName);result.put("phase",phase);result.put("redisHost","127.0.0.1");result.put("redisPort",factory.getPort());result.put("target",String.valueOf(target));result.put("databaseVisibility","mocked approved/pending/rejected parent and reply; no database writes");result.put("keys",rows);
        Files.writeString(Path.of(evidence,"redis-"+testName+"-"+phase+".json"),new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    @Test void repeatedExplicitTrueHasOneMemberAndMatchingSnapshot(){
        assertThat(service.setCommentReaction(target,7L,true,true)).isEqualTo(new LikeService.ReactionState(1,0,true,false));
        assertThat(service.setCommentReaction(target,7L,true,true)).isEqualTo(new LikeService.ReactionState(1,0,true,false));
        assertThat(redis.opsForSet().members(key)).containsExactly("7");
    }
    @Test void repeatedExplicitFalseHasNoMember(){
        service.setCommentReaction(target,7L,true,true);
        assertThat(service.setCommentReaction(target,7L,true,false)).isEqualTo(new LikeService.ReactionState(0,0,false,false));
        assertThat(service.setCommentReaction(target,7L,true,false)).isEqualTo(new LikeService.ReactionState(0,0,false,false));
        assertThat(redis.opsForSet().size(key)).isZero();
    }
    @Test void twentyUsersSetTrueTwiceKeepExactlyTwentyMembers() throws Exception {
        concurrent(20,i->service.setCommentReaction(target,100L+i,true,true).likesCount());
        assertThat(redis.opsForSet().size(key)).isEqualTo(20);
        concurrent(20,i->{var state=service.setCommentReaction(target,100L+i,true,true);assertThat(state.likesCount()).isEqualTo(20);assertThat(state.liked()).isTrue();return state.likesCount();});
        assertThat(redis.opsForSet().members(key)).hasSize(20);
    }
    @Test void concurrentSameUserSameIntentIsIdempotent() throws Exception {
        concurrent(48,i->service.setCommentReaction(target,7L,true,true).likesCount());
        assertThat(redis.opsForSet().members(key)).containsExactly("7");
        concurrent(48,i->service.setCommentReaction(target,7L,true,false).likesCount());
        assertThat(service.getCommentReaction(target,7L)).isEqualTo(new LikeService.ReactionState(0,0,false,false));
    }
    @Test void likesAndDislikesRemainIndependentAndMatchRedis(){
        service.setCommentReaction(target,7L,true,true);
        assertThat(service.setCommentReaction(target,7L,false,true)).isEqualTo(new LikeService.ReactionState(1,1,true,true));
        assertThat(redis.opsForSet().members(keys.get(0))).containsExactly("7");assertThat(redis.opsForSet().members(keys.get(1))).containsExactly("7");
        assertThat(service.setCommentReaction(target,7L,true,false)).isEqualTo(new LikeService.ReactionState(0,1,false,true));
        assertThat(service.getCommentReaction(target,7L)).isEqualTo(new LikeService.ReactionState(0,1,false,true));
    }
    @Test void replyStateHasTheSameIndependentContract(){
        service.setReplyReaction(target,7L,true,true);service.setReplyReaction(target,7L,false,true);
        assertThat(service.getReplyReaction(target,7L)).isEqualTo(new LikeService.ReactionState(1,1,true,true));
        assertThat(redis.opsForSet().size(keys.get(2))).isEqualTo(1);assertThat(redis.opsForSet().size(keys.get(3))).isEqualTo(1);
        assertThat(service.setReplyReaction(target,7L,false,false)).isEqualTo(new LikeService.ReactionState(1,0,true,false));
    }
    @Test void hiddenCommentAndReplyAreRejectedByNewAndLegacyEntrypoints(){
        for(byte status:new byte[]{0,2}) {
            parent.setAuditStatus(status);
            assertThatThrownBy(()->service.setCommentReaction(target,7L,true,true)).isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
            assertThatThrownBy(()->service.setCommentReaction(target,7L,false,false)).isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
            assertThatThrownBy(()->service.toggleCommentLike(target,7L)).isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
            assertThatThrownBy(()->service.toggleCommentUnLike(target,7L)).isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
            assertThatThrownBy(()->service.setReplyReaction(target,7L,true,true)).isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
            assertThatThrownBy(()->service.toggleReplyLike(target,7L)).isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
            parent.setAuditStatus((byte)1);reply.setAuditStatus(status);
            assertThatThrownBy(()->service.setReplyReaction(target,7L,false,true)).isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
            assertThatThrownBy(()->service.toggleReplyUnLike(target,7L)).isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
            reply.setAuditStatus((byte)1);
        }
        for(String k:keys)assertThat(redis.hasKey(k)).isFalse();
    }
    @Test void wrongTypeInOtherSetFailsBeforeAnyMutation(){
        redis.opsForValue().set(keys.get(1),"test-only-wrong-type");
        assertThatThrownBy(()->service.setCommentReaction(target,7L,true,true)).isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
        assertThat(redis.hasKey(key)).isFalse();
    }
    @Test void readSnapshotDoesNotCreateEmptySetsAndReturnsOtherUsersCounts(){
        assertThat(service.getCommentReaction(target,7L)).isEqualTo(new LikeService.ReactionState(0,0,false,false));
        for(String k:keys)assertThat(redis.hasKey(k)).isFalse();
        service.setCommentReaction(target,8L,true,true);service.setCommentReaction(target,8L,false,true);
        assertThat(service.getCommentReaction(target,7L)).isEqualTo(new LikeService.ReactionState(1,1,false,false));
    }
    @Test void explicitHttpStateAndLegacyCountPayloadMatchRedis() throws Exception {
        var controller=new com.java2nb.novel.controller.BookController(mock(com.java2nb.novel.service.BookService.class),Map.of(),mock(com.java2nb.novel.service.IpLocationService.class),service,mock(com.java2nb.novel.messaging.BookVisitEventPublisher.class));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new com.java2nb.novel.core.advice.CommonExceptionHandler(),new com.java2nb.novel.core.advice.CustomResponseBodyAdvice(org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json())).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
        var user=new com.java2nb.novel.core.bean.UserDetails();user.setId(7L);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/book/setCommentLike").param("commentId",String.valueOf(target)).param("active","true").requestAttr("novel.auth.checked",true).requestAttr("novel.auth.user",user))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.likesCount").value("1")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.liked").value(true));
        assertThat(redis.opsForSet().members(key)).containsExactly("7");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/book/toggleCommentLike").param("commentId",String.valueOf(target)).requestAttr("novel.auth.checked",true).requestAttr("novel.auth.user",user))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").value("0")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Deprecation"));
        assertThat(redis.opsForSet().size(key)).isZero();
    }
    @Test void twoSequentialTogglesLikeThenCancel(){
        assertThat(service.toggleCommentLike(target,7L)).isEqualTo(1);
        assertThat(service.toggleCommentLike(target,7L)).isZero();
    }
    @Test void concurrentSameUserTogglesAreAtomicAndHaveParity() throws Exception {
        concurrent(24,i->service.toggleCommentLike(target,7L));
        assertThat(service.getCommentLikesCount(target)).isZero();
        concurrent(25,i->service.toggleCommentLike(target,7L));
        assertThat(service.getCommentLikesCount(target)).isEqualTo(1);
    }
    @Test void differentUsersAndConcurrentCancellationHaveExactCounts() throws Exception {
        concurrent(20,i->service.toggleCommentLike(target,100L+i));
        assertThat(service.getCommentLikesCount(target)).isEqualTo(20);
        concurrent(20,i->service.toggleCommentLike(target,100L+i));
        assertThat(service.getCommentLikesCount(target)).isZero();
    }
    void concurrent(int n,java.util.function.IntFunction<Long> task) throws Exception {
        try(var pool=Executors.newFixedThreadPool(6)){
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<Long>>();
            for(int i=0;i<n;i++){int id=i;futures.add(pool.submit(()->{gate.await();return task.apply(id);}));}
            gate.countDown();for(var f:futures)f.get(15,TimeUnit.SECONDS);
        }
    }
}
