package com.java2nb.novel.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import com.java2nb.novel.event.BookVisitEvent;
import com.java2nb.novel.messaging.BookVisitBatchAggregator.InvalidVisitEventException;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
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
        @Qualifier("bookVisitDltKafkaTemplate") KafkaTemplate<Object, Object> kafkaTemplate,
        MeterRegistry meterRegistry
    ) {
        DeadLetterPublishingRecoverer dltPublisher = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) ->
                new TopicPartition(properties.dltTopic(), record.partition()));
        dltPublisher.setFailIfSendResultIsError(true);
        dltPublisher.setWaitForSendResultTimeout(Duration.ofSeconds(5));
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            try {
                dltPublisher.accept(record, exception);
                meterRegistry.counter("novel.book.visit.kafka.dlt").increment();
            } catch (RuntimeException failedDlt) {
                meterRegistry.counter("novel.book.visit.kafka.dlt_publish_failures").increment();
                throw failedDlt;
            }
        };
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            recoverer, new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        // Database/unknown failures retain their offsets. Only our explicit validation can skip a record.
        errorHandler.setClassifications(Map.of(InvalidVisitEventException.class, false), true);
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
            meterRegistry.counter("novel.book.visit.kafka.retry").increment());
        return errorHandler;
    }

    @Bean(defaultCandidate = false, destroyMethod = "destroy")
    DefaultKafkaProducerFactory<Object, Object> bookVisitDltProducerFactory(KafkaProperties properties) {
        var keys = new DelegatingByTypeSerializer(Map.of(
            Long.class, new LongSerializer(), byte[].class, new ByteArraySerializer()));
        var values = new DelegatingByTypeSerializer(Map.of(
            BookVisitEvent.class, new JsonSerializer<>(), byte[].class, new ByteArraySerializer()));
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(null), keys, values);
    }

    @Bean(defaultCandidate = false)
    KafkaTemplate<Object, Object> bookVisitDltKafkaTemplate(
        @Qualifier("bookVisitDltProducerFactory") DefaultKafkaProducerFactory<Object, Object> factory
    ) {
        return new KafkaTemplate<>(factory);
    }

    /**
     * Injectable clock keeps event timestamps deterministic in tests.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
