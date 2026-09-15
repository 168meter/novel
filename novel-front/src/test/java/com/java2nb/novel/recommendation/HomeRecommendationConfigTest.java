package com.java2nb.novel.recommendation;

import org.apache.ibatis.session.SqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HomeRecommendationConfigTest {
    @Test void explicitlyRegistersMapperOutsideApplicationMapperScan() {
        SqlSession session=mock(SqlSession.class); var mapper=mock(HomeRecommendationMapper.class);
        when(session.getMapper(HomeRecommendationMapper.class)).thenReturn(mapper);
        when(mapper.listConfigured()).thenReturn(java.util.List.of());
        when(mapper.listCandidates(any(),any(),any())).thenReturn(java.util.List.of());
        new ApplicationContextRunner().withUserConfiguration(HomeRecommendationConfig.class)
            .withBean(SqlSession.class,() -> session)
            .withBean(Clock.class,Clock::systemUTC)
            .withBean(StringRedisTemplate.class,() -> mock(StringRedisTemplate.class))
            .withBean(ObjectMapper.class,() -> new ObjectMapper().findAndRegisterModules())
            .withBean(SimpleMeterRegistry.class,SimpleMeterRegistry::new)
            .run(context -> {
                assertThat(context).hasSingleBean(HomeRecommendationMapper.class);
                assertThat(context).hasSingleBean(HomeRecommendationService.class);
                assertThat(context.getBean(HomeRecommendationMapper.class)).isSameAs(mapper);
            });
    }

    @Test void createsDedicatedSingleThreadScheduler() throws Exception {
        ThreadPoolTaskScheduler scheduler=new HomeRecommendationConfig().homeRecommendationScheduler();
        try {
            var started=new AtomicInteger(); var firstStarted=new CountDownLatch(1);
            var release=new CountDownLatch(1); var bothFinished=new CountDownLatch(2);
            Runnable task=() -> { started.incrementAndGet(); firstStarted.countDown();
                try { release.await(1,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { bothFinished.countDown(); } };
            scheduler.execute(task); scheduler.execute(task);
            assertThat(firstStarted.await(1,TimeUnit.SECONDS)).isTrue();
            assertThat(started.get()).isEqualTo(1);
            release.countDown(); assertThat(bothFinished.await(1,TimeUnit.SECONDS)).isTrue();
        } finally { scheduler.shutdown(); }
    }
}
