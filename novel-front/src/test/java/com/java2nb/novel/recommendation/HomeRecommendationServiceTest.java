package com.java2nb.novel.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.vo.BookSettingVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HomeRecommendationServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-15T01:00:00Z"));
    private HomeRecommendationMapper mapper;
    private StringRedisTemplate redis;
    private ValueOperations<String,String> values;
    private SimpleMeterRegistry meters;
    private HomeRecommendationService service;

    @BeforeEach void setUp() {
        mapper=mock(HomeRecommendationMapper.class); redis=mock(StringRedisTemplate.class);
        values=mock(ValueOperations.class); when(redis.opsForValue()).thenReturn(values);
        when(mapper.listCandidates(any(),any(),any())).thenReturn(List.of());
        when(mapper.listConfigured()).thenReturn(List.of(book(1,0)));
        meters=new SimpleMeterRegistry();
        service=new HomeRecommendationService(clock,mapper,new HomeRecommendationAssembler(),redis,json,meters);
    }

    @Test void refreshPublishesLocalSnapshotEvenWhenRedisWriteFails() {
        doThrow(new IllegalStateException("redis down")).when(values).set(anyString(),anyString(),any(Duration.class));
        service.refresh(); when(values.get(anyString())).thenReturn(null);
        assertThat(ids(service.getHome(),"0")).containsExactly(1L);
        assertThat(meters.counter("novel.home.recommendation.refresh","result","redis_error").count()).isEqualTo(1);
    }

    @Test void databaseFailureDoesNotReplaceLastSuccessfulSnapshot() {
        service.refresh(); when(values.get(anyString())).thenReturn(null);
        when(mapper.listConfigured()).thenThrow(new IllegalStateException("db down"));
        service.refresh();
        assertThat(ids(service.getHome(),"0")).containsExactly(1L);
        assertThat(meters.counter("novel.home.recommendation.refresh","result","db_error").count()).isEqualTo(1);
    }

    @Test void redisSnapshotWinsAndReturnedMutationCannotCorruptNextRead() throws Exception {
        when(values.get(anyString())).thenReturn(
            snapshot(Instant.parse("2026-09-15T00:59:00Z"),groups(book(9,0)),1)).thenReturn(null);
        var first=service.getHome(); first.get("0").get(0).setBookName("changed");
        assertThat(service.getHome().get("0").get(0).getBookName()).isEqualTo("book9");
        verify(mapper,never()).listConfigured();
    }

    @Test void rejectsWrongVersionFutureOldDuplicateAndOverCapSnapshots() throws Exception {
        for (String bad : List.of(
            "not-json",
            snapshot(clock.instant(),groups(book(9,0)),2),
            snapshot(clock.instant().plus(Duration.ofDays(1)),groups(book(9,0)),1),
            snapshot(clock.instant().minus(Duration.ofHours(25)),groups(book(9,0)),1),
            snapshot(clock.instant(),groups(book(9,0),book(9,0)),1),
            snapshot(clock.instant(),groups(book(1,0),book(2,0),book(3,0),book(4,0),book(5,0)),1))) {
            reset(values); when(values.get(anyString())).thenReturn(bad);
            assertThat(ids(service.getHome(),"0")).containsExactly(1L);
            clock.advance(Duration.ofSeconds(31));
        }
    }

    @Test void refreshUsesExactWindowAndWritesOneVersionedSnapshotWithFifteenMinuteTtl() throws Exception {
        service.refresh();
        verify(mapper).listCandidates(java.time.LocalDate.of(2026,9,9),java.time.LocalDate.of(2026,9,1),java.time.LocalDate.of(2026,9,16));
        var encoded=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(HomeRecommendationService.REDIS_KEY),encoded.capture(),eq(Duration.ofMinutes(15)));
        var snapshot=json.readValue(encoded.getValue(),HomeRecommendationService.Snapshot.class);
        assertThat(snapshot.version()).isEqualTo(1);
        assertThat(snapshot.generatedAt()).isEqualTo(clock.instant());
        assertThat(meters.counter("novel.home.recommendation.refresh","result","success").count()).isEqualTo(1);
        assertThat(meters.find("novel.home.recommendation.generation").timer().count()).isEqualTo(1);
    }

    @Test void expiredLocalSnapshotFallsBackToConfiguredRows() {
        service.refresh(); when(values.get(anyString())).thenReturn(null);
        clock.advance(Duration.ofHours(25)); when(mapper.listConfigured()).thenReturn(List.of(book(2,0)));
        assertThat(ids(service.getHome(),"0")).containsExactly(2L);
    }

    @Test void failedColdFallbackIsCachedForThirtySeconds() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(mapper.listConfigured()).thenThrow(new IllegalStateException("db down"));
        assertThat(service.getHome().values()).allSatisfy(rows -> assertThat(rows).isEmpty());
        assertThat(service.getHome().values()).allSatisfy(rows -> assertThat(rows).isEmpty());
        verify(mapper,times(1)).listConfigured();
        assertThat(meters.counter("novel.home.recommendation.source","source","empty").count()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(31)); service.getHome(); verify(mapper,times(2)).listConfigured();
    }

    @Test void fallbackTtlStartsAfterSlowDatabaseLoadCompletes() {
        when(values.get(anyString())).thenReturn(null);
        when(mapper.listConfigured()).thenAnswer(call -> { clock.advance(Duration.ofSeconds(31)); return List.of(book(8,0)); });
        assertThat(ids(service.getHome(),"0")).containsExactly(8L);
        assertThat(ids(service.getHome(),"0")).containsExactly(8L);
        verify(mapper,times(1)).listConfigured();
    }

    @Test void concurrentColdStartLoadsConfiguredFallbackOnlyOnce() throws Exception {
        when(values.get(anyString())).thenReturn(null);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(mapper.listConfigured()).thenAnswer(call -> { entered.countDown(); release.await(2,TimeUnit.SECONDS); return List.of(book(7,0)); });
        var pool=Executors.newFixedThreadPool(8);
        try {
            var futures=new ArrayList<java.util.concurrent.Future<Map<String,List<BookSettingVO>>>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(service::getHome));
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue(); release.countDown();
            for(var future:futures) assertThat(ids(future.get(2,TimeUnit.SECONDS),"0")).containsExactly(7L);
            verify(mapper,times(1)).listConfigured(); verify(mapper,never()).listCandidates(any(),any(),any());
        } finally { pool.shutdownNow(); }
    }

    @Test void coldFallbackWaitersAreBoundedWhenDatabaseLoaderHangs() throws Exception {
        when(values.get(anyString())).thenReturn(null);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(mapper.listConfigured()).thenAnswer(call -> { entered.countDown(); release.await(2,TimeUnit.SECONDS); return List.of(book(7,0)); });
        var pool=Executors.newFixedThreadPool(2); var secondDone=new CountDownLatch(1);
        try {
            var loader=pool.submit(service::getHome); assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            var waiter=pool.submit(() -> { try { return service.getHome(); } finally { secondDone.countDown(); } });
            assertThat(secondDone.await(750,TimeUnit.MILLISECONDS)).isTrue();
            assertThat(waiter.get().values()).allSatisfy(rows -> assertThat(rows).isEmpty());
            release.countDown(); assertThat(ids(loader.get(2,TimeUnit.SECONDS),"0")).containsExactly(7L);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void successfulEmptyRefreshIsPublishedRatherThanOldData() {
        service.refresh(); when(values.get(anyString())).thenReturn(null);
        when(mapper.listConfigured()).thenReturn(List.of()); service.refresh();
        assertThat(service.getHome().values()).allSatisfy(rows -> assertThat(rows).isEmpty());
    }

    private String snapshot(Instant instant,Map<String,List<BookSettingVO>> groups,int version) throws Exception {
        return json.writeValueAsString(new HomeRecommendationService.Snapshot(version,instant,groups));
    }
    private Map<String,List<BookSettingVO>> groups(BookSettingVO... zero) {
        return Map.of("0",List.of(zero),"1",List.of(),"2",List.of(),"3",List.of(),"4",List.of());
    }
    private BookSettingVO book(long id,int type) { var b=new BookSettingVO(); b.setBookId(id); b.setType((byte)type); b.setSort((byte)1); b.setBookName("book"+id); return b; }
    private List<Long> ids(Map<String,List<BookSettingVO>> home,String type) { return home.get(type).stream().map(BookSettingVO::getBookId).toList(); }

    private static final class MutableClock extends Clock {
        private Instant instant; MutableClock(Instant value){instant=value;}
        void advance(Duration duration){instant=instant.plus(duration);}
        public ZoneId getZone(){return ZoneId.of("UTC");}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return instant;}
    }
}
