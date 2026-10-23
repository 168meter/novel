package com.java2nb.novel.config;

import com.java2nb.novel.cache.AdminChapterCacheInvalidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class ChapterCacheConfiguration {

    @Bean
    AdminChapterCacheInvalidator adminChapterCacheInvalidator(
        StringRedisTemplate redisTemplate,
        ObjectProvider<MeterRegistry> meterRegistries
    ) {
        MeterRegistry meterRegistry = meterRegistries.getIfAvailable(() -> Metrics.globalRegistry);
        return new AdminChapterCacheInvalidator(redisTemplate, meterRegistry);
    }
}
