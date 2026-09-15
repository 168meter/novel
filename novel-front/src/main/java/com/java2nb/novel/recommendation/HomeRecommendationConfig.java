package com.java2nb.novel.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.apache.ibatis.session.SqlSession;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
public class HomeRecommendationConfig {
    @Bean HomeRecommendationMapper homeRecommendationMapper(SqlSession session) { return session.getMapper(HomeRecommendationMapper.class); }
    @Bean HomeRecommendationAssembler homeRecommendationAssembler() { return new HomeRecommendationAssembler(); }
    @Bean HomeRecommendationService homeRecommendationService(Clock clock,HomeRecommendationMapper mapper,
        HomeRecommendationAssembler assembler,StringRedisTemplate redis,ObjectMapper json,MeterRegistry meters) {
        return new HomeRecommendationService(clock,mapper,assembler,redis,json,meters);
    }
    @Bean(name="homeRecommendationScheduler") ThreadPoolTaskScheduler homeRecommendationScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("home-recommendation-"); scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize(); return scheduler;
    }
    @Bean(initMethod="start",destroyMethod="close") HomeRecommendationRefreshJob homeRecommendationRefreshJob(
        HomeRecommendationService service,@Qualifier("homeRecommendationScheduler") TaskScheduler scheduler) {
        return new HomeRecommendationRefreshJob(service,scheduler);
    }
}
