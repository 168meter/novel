package com.java2nb.novel.core.config;

import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Finite existing outcome series need a zero baseline before the first failure. */
@Configuration(proxyBeanMethods = false)
public class ObservabilityMetricsConfig {

    @Bean
    MeterBinder benchmarkOutcomeCounters() {
        return registry -> {
            for (String name : List.of("novel.chapter.request.rejected",
                "novel.book.visit.kafka.retry", "novel.book.visit.kafka.dlt",
                "novel.reading.kafka.retry", "novel.reading.kafka.dlt",
                "novel.reading.kafka.dlt_publish_failures")) {
                registry.counter(name);
            }
            for (String result : List.of("success", "error")) {
                registry.counter("novel.reading.page.registration", "result", result);
            }
            for (String result : List.of("success", "db_error", "redis_error")) {
                registry.counter("novel.home.recommendation.refresh", "result", result);
            }
            for (String source : List.of("redis", "local", "configured", "empty")) {
                registry.counter("novel.home.recommendation.source", "source", source);
            }
        };
    }
}
