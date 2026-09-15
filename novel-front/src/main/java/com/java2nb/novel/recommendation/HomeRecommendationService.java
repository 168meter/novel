package com.java2nb.novel.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.vo.BookSettingVO;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.data.redis.core.StringRedisTemplate;

public class HomeRecommendationService {
    static final String REDIS_KEY="novel:home:reading-recommendation:v1";
    private static final Duration REDIS_TTL=Duration.ofMinutes(15);
    private static final Duration MAX_LOCAL_AGE=Duration.ofHours(24);
    private static final Duration FALLBACK_TTL=Duration.ofSeconds(30);
    private static final int[] CAPS={4,10,5,6,6};

    public record Snapshot(int version,Instant generatedAt,Map<String,List<BookSettingVO>> groups) {}
    private record Encoded(String json,Instant generatedAt) {}
    private record Fallback(Map<String,List<BookSettingVO>> groups,Instant expiresAt,String source) {}

    private final Clock clock;
    private final HomeRecommendationMapper mapper;
    private final HomeRecommendationAssembler assembler;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final AtomicReference<Encoded> local=new AtomicReference<>();
    private final AtomicReference<Fallback> fallback=new AtomicReference<>();
    private final ReentrantLock fallbackLock=new ReentrantLock();
    private final AtomicLong localAgeSeconds=new AtomicLong(-1);

    public HomeRecommendationService(Clock clock,HomeRecommendationMapper mapper,
        HomeRecommendationAssembler assembler,StringRedisTemplate redis,ObjectMapper json,MeterRegistry meters) {
        this.clock=clock; this.mapper=mapper; this.assembler=assembler; this.redis=redis; this.json=json; this.meters=meters;
        meters.gauge("novel.home.recommendation.local.age.seconds",localAgeSeconds);
    }

    public Map<String,List<BookSettingVO>> getHome() {
        Instant now=clock.instant();
        try {
            String encoded=redis.opsForValue().get(REDIS_KEY);
            Snapshot snapshot=decodeValid(encoded,now);
            if(snapshot!=null) {
                local.set(new Encoded(encoded,snapshot.generatedAt()));
                localAgeSeconds.set(Duration.between(snapshot.generatedAt(),now).toSeconds());
                source("redis"); return copy(snapshot.groups());
            }
        } catch(RuntimeException ignored) { }
        Encoded saved=local.get();
        if(saved!=null && ageValid(saved.generatedAt(),now)) {
            Snapshot snapshot=decodeValid(saved.json(),now);
            if(snapshot!=null) { localAgeSeconds.set(Duration.between(snapshot.generatedAt(),now).toSeconds()); source("local"); return copy(snapshot.groups()); }
        }
        return configuredFallback(now);
    }

    public void refresh() {
        long started=System.nanoTime();
        Instant now=clock.instant();
        String encoded;
        try {
            RecommendationWindow window=RecommendationWindow.from(Clock.fixed(now,clock.getZone()));
            var configured=mapper.listConfigured();
            var candidates=mapper.listCandidates(window.weekStart(),window.hotStart(),window.endExclusive());
            encoded=json.writeValueAsString(new Snapshot(1,now,assembler.compose(candidates,configured)));
            if(decodeValid(encoded,now)==null) throw new IllegalStateException("generated invalid recommendation snapshot");
        } catch(Exception failure) {
            meters.counter("novel.home.recommendation.refresh","result","db_error").increment();
            meters.timer("novel.home.recommendation.generation").record(System.nanoTime()-started,TimeUnit.NANOSECONDS);
            return;
        }
        local.set(new Encoded(encoded,now)); localAgeSeconds.set(0);
        try {
            redis.opsForValue().set(REDIS_KEY,encoded,REDIS_TTL);
            meters.counter("novel.home.recommendation.refresh","result","success").increment();
        } catch(RuntimeException failure) {
            meters.counter("novel.home.recommendation.refresh","result","redis_error").increment();
        }
        meters.timer("novel.home.recommendation.generation").record(System.nanoTime()-started,TimeUnit.NANOSECONDS);
    }

    private Map<String,List<BookSettingVO>> configuredFallback(Instant now) {
        Fallback cached=fallback.get();
        if(cached!=null && now.isBefore(cached.expiresAt())) return fallbackResult(cached);
        try {
            if(!fallbackLock.tryLock(250,TimeUnit.MILLISECONDS)) { source("empty"); return assembler.configuredOnly(List.of()); }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt(); source("empty"); return assembler.configuredOnly(List.of());
        }
        try {
            cached=fallback.get();
            if(cached!=null && now.isBefore(cached.expiresAt())) return fallbackResult(cached);
            Map<String,List<BookSettingVO>> groups;
            String label="configured";
            try { groups=assembler.configuredOnly(mapper.listConfigured()); }
            catch(RuntimeException failure) { groups=assembler.configuredOnly(List.of()); label="empty"; }
            cached=new Fallback(groups,clock.instant().plus(FALLBACK_TTL),label); fallback.set(cached);
            source(label); return copyConfigured(groups);
        } finally {
            fallbackLock.unlock();
        }
    }

    private Map<String,List<BookSettingVO>> fallbackResult(Fallback cached) {
        source(cached.source()); return copyConfigured(cached.groups());
    }

    private Snapshot decodeValid(String encoded,Instant now) {
        if(encoded==null || encoded.isBlank()) return null;
        try {
            Snapshot snapshot=json.readValue(encoded,Snapshot.class);
            if(snapshot.version()!=1 || snapshot.generatedAt()==null || snapshot.generatedAt().isAfter(now)
                || !ageValid(snapshot.generatedAt(),now) || snapshot.groups()==null
                || !snapshot.groups().keySet().equals(Set.of("0","1","2","3","4"))) return null;
            for(int type=0;type<=4;type++) {
                List<BookSettingVO> group=snapshot.groups().get(Integer.toString(type));
                if(group==null || group.size()>CAPS[type]) return null;
                var ids=new HashSet<Long>();
                for(BookSettingVO row:group) if(row==null || row.getBookId()==null || row.getType()==null
                    || row.getType()!=type || !ids.add(row.getBookId())) return null;
            }
            return snapshot;
        } catch(Exception ignored) { return null; }
    }

    private boolean ageValid(Instant generated,Instant now) { return !generated.isAfter(now) && Duration.between(generated,now).compareTo(MAX_LOCAL_AGE)<=0; }
    private Map<String,List<BookSettingVO>> copy(Map<String,List<BookSettingVO>> groups) {
        try {
            String encoded=json.writeValueAsString(new Snapshot(1,clock.instant(),groups));
            return json.readValue(encoded,Snapshot.class).groups();
        } catch(Exception failure) { return assembler.configuredOnly(List.of()); }
    }
    private Map<String,List<BookSettingVO>> copyConfigured(Map<String,List<BookSettingVO>> groups) {
        return assembler.configuredOnly(groups.values().stream().flatMap(List::stream).toList());
    }
    private void source(String source) { meters.counter("novel.home.recommendation.source","source",source).increment(); }
}
