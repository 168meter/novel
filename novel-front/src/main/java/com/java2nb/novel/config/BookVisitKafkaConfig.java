package com.java2nb.novel.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookVisitKafkaProperties.class)
public class BookVisitKafkaConfig {

    private final BookVisitKafkaProperties properties;

    public BookVisitKafkaConfig(BookVisitKafkaProperties properties) {
        this.properties = properties;
    }

    @Bean
    NewTopic visitTopic() {
        return TopicBuilder.name(properties.topic())
            .partitions(3)
            .replicas(1)
            .build();
    }

    @Bean
    NewTopic visitDltTopic() {
        return TopicBuilder.name(properties.dltTopic())
            .partitions(3)
            .replicas(1)
            .build();
    }

    @Bean
    DefaultErrorHandler bookVisitErrorHandler(
        KafkaTemplate<Object, Object> kafkaTemplate,
        MeterRegistry meterRegistry
    ) {
        DeadLetterPublishingRecoverer dltPublisher = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) ->
                new TopicPartition(properties.dltTopic(), record.partition()));
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            dltPublisher.accept(record, exception);
            meterRegistry.counter("novel.book.visit.kafka.dlt").increment();
        };
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            recoverer, new FixedBackOff(5000L, 12L));
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
            meterRegistry.counter("novel.book.visit.kafka.retry").increment());
        return errorHandler;
    }

    /**
     * Injectable clock keeps event timestamps deterministic in tests.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
