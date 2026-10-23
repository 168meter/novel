package com.java2nb.novel.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.entity.BookContent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Deterministic Redis command model; actual Lua requires the opt-in Redis integration check. */
@SuppressWarnings({"rawtypes","unchecked"})
@Timeout(10)
class ChapterContentCacheFenceTest {
    final String key="novel:chapter:v1:101:202", lock="novel:chapter:lock:v1:101:202";
    final Map<String,String> state = new HashMap<>();
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations values = mock(ValueOperations.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ChapterContentCache cache;
    ChapterContentCacheFenceTest() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> {synchronized(state){return state.get(i.getArgument(0));}});
        when(values.setIfAbsent(anyString(),anyString(),anyLong(),any(TimeUnit.class))).thenAnswer(i -> {
            synchronized(state){return state.putIfAbsent(i.getArgument(0),i.getArgument(1))==null;}
        });
        doAnswer(i -> {synchronized(state){state.put(i.getArgument(0),i.getArgument(1));return null;}})
            .when(values).set(anyString(),anyString(),anyLong(),any(TimeUnit.class));
        when(redis.delete(anyCollection())).thenAnswer(i -> {
            synchronized(state){long n=0;for(Object k:(Collection)i.getArgument(0)){if(state.remove(k)!=null)n++;}return n;}
        });
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenAnswer(i -> {
            List<String> keys=i.getArgument(1);
            synchronized(state){
                String token=i.getArgument(2);
                String lockKey=keys.getLast();
                if(!token.equals(state.get(lockKey)))return 0L;
                if(keys.size()==1){state.remove(lockKey);return 1L;}
                state.put(keys.getFirst(),i.getArgument(3));return 1L;
            }
        });
        cache=new ChapterContentCache(redis,new ObjectMapper(),1800,300,60,10,0,0,n->{},()->7,metrics);
    }
    @Test void completedInvalidationPreventsLateOldFill() throws Exception { lateFill(false,false); }
    @Test void completedInsertInvalidationPreventsLateNullFill() throws Exception { lateFill(true,false); }
    @Test void newOwnerFillCannotBeOverwrittenOrUnlockedByOldLoader() throws Exception { lateFill(false,true); }
    void lateFill(boolean nullValue,boolean refill) throws Exception {
        var loaded=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        try {
            Future<BookContent> old=executor.submit(() -> cache.getOrLoad(101L,202L,() -> {
                loaded.countDown();try{if(!resume.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Latch timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return nullValue?null:body("old");
            }));
            assertThat(loaded.await(5,TimeUnit.SECONDS)).isTrue();
            cache.evict(101L,202L);
            if(refill) assertThat(cache.getOrLoad(101L,202L,() -> body("new")).getContent()).isEqualTo("new");
            resume.countDown();old.get(5,TimeUnit.SECONDS);
            synchronized(state){
                if(refill) assertThat(state.get(key)).contains("new").doesNotContain("old");
                else assertThat(state).doesNotContainKey(key);
            }
        }finally{resume.countDown();executor.shutdownNow();}
    }
    @Test void expiredOrMissingTokenDoesNotAllowFill() {
        var loaded=cache.getOrLoad(101L,202L,() -> {synchronized(state){state.remove(lock);}return body("old");});
        assertThat(loaded.getContent()).isEqualTo("old");
        assertThat(state).doesNotContainKey(key);
    }
    @Test void replacementTokenIsPreserved() {
        cache.getOrLoad(101L,202L,() -> {synchronized(state){state.put(lock,"new-owner");}return body("old");});
        assertThat(state).doesNotContainKey(key).containsEntry(lock,"new-owner");
    }
    @Test void lostFillRightsAreNotReportedAsSuccessfulWrite() {
        cache.getOrLoad(101L,202L,() -> {cache.evict(101L,202L);return body("old");});
        assertThat(metrics.counter("novel.chapter.cache.write","result","success").count()).isZero();
    }
    static BookContent body(String text){var b=new BookContent();b.setId(303L);b.setIndexId(202L);b.setContent(text);return b;}
}
