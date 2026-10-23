package com.java2nb.novel.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.cache.ChapterContentCacheKey;
import com.java2nb.novel.entity.BookContent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.*;
import static org.assertj.core.api.Assertions.*;

/** Actual Redis/Lua, fresh keys only. Supplier/commit signals are test controls, not MySQL evidence. */
@EnabledIfEnvironmentVariable(named="CHAPTER_CACHE_REDIS_IT",matches="true")
@Timeout(20)
class ChapterContentCacheFenceRedisIT {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    ChapterContentCache cache;
    long book,chapter;
    String key,lock,test;
    Path evidence;
    boolean owned;
    List<String> trace=new CopyOnWriteArrayList<>();
    @BeforeEach void setup(TestInfo info) throws Exception {
        test=info.getTestMethod().orElseThrow().getName();
        if(!"26381".equals(System.getenv("CORRECTNESS_REDIS_PORT"))) throw new IllegalStateException("Only private correctness Redis port 26381 is accepted");
        String password=System.getenv("CORRECTNESS_REDIS_PASSWORD");
        if(password==null || password.isBlank())throw new IllegalStateException("Private Redis credentials required");
        evidence=Path.of(Objects.requireNonNull(System.getenv("CORRECTNESS_EVIDENCE")));
        var cfg=new RedisStandaloneConfiguration("127.0.0.1",26381);cfg.setPassword(password);
        factory=new LettuceConnectionFactory(cfg,LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build());
        factory.afterPropertiesSet();factory.start();redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();
        book=8_100_000_000_000_000_000L+ThreadLocalRandom.current().nextLong(1_000_000_000_000_000L);chapter=book-1;
        key=ChapterContentCacheKey.of(book,chapter);lock=ChapterContentCacheKey.lockOf(book,chapter);
        assertThat(redis.hasKey(key)).as("Fresh content key").isFalse();
        assertThat(redis.hasKey(lock)).as("Fresh loader key").isFalse();owned=true;snapshot("before");
        cache=new ChapterContentCache(redis,new ObjectMapper(),30,0,10,10,0,0,n->{},()->0,new SimpleMeterRegistry());
        Files.writeString(evidence.resolve("chapter-fill.lua"),ChapterContentCache.FILL_IF_OWNER_SCRIPT.getScriptAsString());
    }
    @AfterEach void finish() throws Exception {
        try{if(owned)snapshot("after");}finally{if(factory!=null)factory.destroy();}
        // Do not clean shared Redis or preexisting keys; newly written values have bounded TTLs.
    }
    @Test void normalMissFillAndHitUseRealLuaAndTtl(){
        var loads=new AtomicInteger();
        assertThat(cache.getOrLoad(book,chapter,()->{loads.incrementAndGet();return body("current");}).getContent()).isEqualTo("current");
        assertThat(redis.getExpire(key,TimeUnit.SECONDS)).isBetween(1L,30L);
        assertThat(cache.getOrLoad(book,chapter,()->{throw new AssertionError("Unexpected loader");}).getContent()).isEqualTo("current");
        assertThat(loads).hasValue(1);
    }
    @Test void oldValueCannotRefillAfterCompletedInvalidation() throws Exception {lateFill(false);}
    @Test void oldNullCannotRefillAfterCompletedInsertInvalidation() throws Exception {lateFill(true);}
    void lateFill(boolean missing) throws Exception {
        var loaded=new CountDownLatch(1);var resume=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try{
            var old=pool.submit(()->cache.getOrLoad(book,chapter,()->{mark("old database value captured (test supplier)");loaded.countDown();await(resume);return missing?null:body("old");}));
            assertThat(loaded.await(5,TimeUnit.SECONDS)).isTrue();
            cache.evict(book,chapter);mark("invalidation acknowledged");
            assertThat(redis.hasKey(lock)).isFalse();
            resume.countDown();old.get(5,TimeUnit.SECONDS);mark("old loader completed");
            assertThat(redis.hasKey(key)).isFalse();
            assertThat(cache.getOrLoad(book,chapter,()->body("new")).getContent()).isEqualTo("new");
            assertThat(redis.opsForValue().get(key)).contains("new").doesNotContain("old");
        }finally{resume.countDown();pool.shutdownNow();}
    }
    @Test void oldLoaderCannotDeleteNewOwnersTokenOrOverwriteItsFill() throws Exception {
        var oldLoaded=new CountDownLatch(1);var oldResume=new CountDownLatch(1);
        var newLoaded=new CountDownLatch(1);var newResume=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var old=pool.submit(()->cache.getOrLoad(book,chapter,()->{oldLoaded.countDown();await(oldResume);return body("old");}));
            assertThat(oldLoaded.await(5,TimeUnit.SECONDS)).isTrue();cache.evict(book,chapter);mark("old token revoked");
            var current=pool.submit(()->cache.getOrLoad(book,chapter,()->{newLoaded.countDown();await(newResume);return body("new");}));
            assertThat(newLoaded.await(5,TimeUnit.SECONDS)).isTrue();String newToken=redis.opsForValue().get(lock);assertThat(newToken).isNotBlank();
            oldResume.countDown();old.get(5,TimeUnit.SECONDS);mark("old loader fill/unlock rejected");
            assertThat(redis.opsForValue().get(lock)).isEqualTo(newToken);assertThat(redis.hasKey(key)).isFalse();
            newResume.countDown();assertThat(current.get(5,TimeUnit.SECONDS).getContent()).isEqualTo("new");
            assertThat(redis.opsForValue().get(key)).contains("new").doesNotContain("old");
        }finally{oldResume.countDown();newResume.countDown();pool.shutdownNow();}
    }
    @Test void expiredLeaseRejectsFillWithRealRedisExpiration(){
        cache.getOrLoad(book,chapter,()->{
            redis.expire(lock,Duration.ofMillis(1));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(Boolean.TRUE.equals(redis.hasKey(lock))){
                if(System.nanoTime()>end)throw new AssertionError("Lease did not expire");
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            mark("test lease expired before fill");return body("old");
        });
        assertThat(redis.hasKey(key)).isFalse();
    }
    BookContent body(String value){var b=new BookContent();b.setId(chapter-1);b.setIndexId(chapter);b.setContent(value);return b;}
    static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    void mark(String text){trace.add(Instant.now()+" "+text);}
    void snapshot(String phase) throws Exception {
        var rows=new ArrayList<Map<String,Object>>();
        for(String k:List.of(key,lock)){
            var row=new LinkedHashMap<String,Object>();row.put("key",k);row.put("type",redis.type(k));row.put("ttlMillis",redis.getExpire(k,TimeUnit.MILLISECONDS));
            row.put("value",redis.opsForValue().get(k));rows.add(row);
        }
        var result=new LinkedHashMap<String,Object>();result.put("test",test);result.put("phase",phase);result.put("time",Instant.now().toString());
        result.put("redisPort",26381);result.put("keys",rows);result.put("trace",trace);
        result.put("scope","Real Redis and production Lua/cache; supplier is controlled test data, not MySQL");
        Files.writeString(evidence.resolve("chapter-redis-"+test+"-"+phase+".json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
