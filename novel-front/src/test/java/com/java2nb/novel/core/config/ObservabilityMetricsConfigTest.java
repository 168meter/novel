package com.java2nb.novel.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityMetricsConfigTest {

    @Test
    void exposesFailureCountersBeforeAnyEventWithoutChangingTheirValues() throws Exception {
        // Resolve by name so the missing configuration is an assertion failure before implementation.
        String name = "com.java2nb.novel.core.config.ObservabilityMetricsConfig";
        assertThat(org.springframework.util.ClassUtils.isPresent(name, getClass().getClassLoader()))
            .as("startup observability counter configuration").isTrue();
        new ApplicationContextRunner().withUserConfiguration(Class.forName(name))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .run(context -> {
                var registry = context.getBean(MeterRegistry.class);
                context.getBeansOfType(MeterBinder.class).values().forEach(binder -> binder.bindTo(registry));
                for (String metric : List.of("novel.chapter.request.rejected",
                    "novel.book.visit.kafka.retry", "novel.book.visit.kafka.dlt",
                    "novel.reading.kafka.retry", "novel.reading.kafka.dlt",
                    "novel.reading.kafka.dlt_publish_failures")) {
                    assertThat(registry.get(metric).counter().count()).isZero();
                }
                for (String result : List.of("success", "error")) {
                    assertThat(registry.get("novel.reading.page.registration")
                        .tag("result", result).counter().count()).isZero();
                }
                for (String result : List.of("success", "db_error", "redis_error")) {
                    assertThat(registry.get("novel.home.recommendation.refresh")
                        .tag("result", result).counter().count()).isZero();
                }
                for (String source : List.of("redis", "local", "configured", "empty")) {
                    assertThat(registry.get("novel.home.recommendation.source")
                        .tag("source", source).counter().count()).isZero();
                }
                // Existing event code continues to obtain the same counter and increments once.
                registry.counter("novel.reading.kafka.retry").increment();
                context.getBeansOfType(MeterBinder.class).values().forEach(binder -> binder.bindTo(registry));
                assertThat(registry.get("novel.reading.kafka.retry").counter().count()).isEqualTo(1);
                assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isIn("result", "source")));
            });
    }
}
