package com.java2nb.novel.config;

import com.java2nb.novel.engagement.ReadingEventConflictException;
import com.java2nb.novel.event.ReadingEngagementEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReadingEngagementKafkaProperties.class)
public class ReadingEngagementKafkaConfig {

    private final ReadingEngagementKafkaProperties properties;

    public ReadingEngagementKafkaConfig(ReadingEngagementKafkaProperties properties) {
        this.properties = properties;
    }

    @Bean
    NewTopic readingEngagementTopic() {
        return TopicBuilder.name(properties.topic()).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic readingEngagementDltTopic() {
        return TopicBuilder.name(properties.dltTopic()).partitions(3).replicas(1).build();
    }

    @Bean(defaultCandidate = false)
    DefaultKafkaConsumerFactory<Long, ReadingEngagementEvent> readingEngagementConsumerFactory(
        KafkaProperties kafkaProperties
    ) {
        Map<String, Object> consumerProperties = new HashMap<>(
            kafkaProperties.buildConsumerProperties(null));
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            ErrorHandlingDeserializer.class);
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            ErrorHandlingDeserializer.class);
        consumerProperties.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,
            LongDeserializer.class);
        consumerProperties.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
            JsonDeserializer.class);
        consumerProperties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
            properties.maxPollRecords());
        consumerProperties.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
            ReadingEngagementEvent.class.getName());
        consumerProperties.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        consumerProperties.put(JsonDeserializer.TRUSTED_PACKAGES,
            "com.java2nb.novel.event");
        return new DefaultKafkaConsumerFactory<>(consumerProperties);
    }

    @Bean(defaultCandidate = false, destroyMethod = "destroy")
    DefaultKafkaProducerFactory<Object, Object> readingEngagementDltProducerFactory(
        KafkaProperties kafkaProperties
    ) {
        // Deserialization failures carry original bytes; JsonSerializer alone would encode Base64.
        DelegatingByTypeSerializer keys = new DelegatingByTypeSerializer(Map.of(
            Long.class, new LongSerializer(), byte[].class, new ByteArraySerializer()));
        DelegatingByTypeSerializer values = new DelegatingByTypeSerializer(Map.of(
            ReadingEngagementEvent.class, new JsonSerializer<>(),
            byte[].class, new ByteArraySerializer()));
        return new DefaultKafkaProducerFactory<>(
            kafkaProperties.buildProducerProperties(null), keys, values);
    }

    @Bean(defaultCandidate = false)
    KafkaTemplate<Object, Object> readingEngagementDltKafkaTemplate(
        @Qualifier("readingEngagementDltProducerFactory")
        DefaultKafkaProducerFactory<Object, Object> producerFactory
    ) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean(defaultCandidate = false)
    DefaultErrorHandler readingEngagementErrorHandler(
        @Qualifier("readingEngagementDltKafkaTemplate") KafkaTemplate<Object, Object> kafkaTemplate,
        MeterRegistry meterRegistry
    ) {
        Counter dltCounter = meterRegistry.counter("novel.reading.kafka.dlt");
        Counter dltFailureCounter = meterRegistry.counter(
            "novel.reading.kafka.dlt_publish_failures");
        DeadLetterPublishingRecoverer dltPublisher = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) ->
                new TopicPartition(properties.dltTopic(), record.partition()));
        dltPublisher.setFailIfSendResultIsError(true);
        dltPublisher.setWaitForSendResultTimeout(Duration.ofSeconds(5));
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            try {
                dltPublisher.accept(record, exception);
                dltCounter.increment();
            } catch (RuntimeException failure) {
                dltFailureCounter.increment();
                throw failure;
            }
        };
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            recoverer,
            new FixedBackOff(properties.retryInterval().toMillis(), properties.maxRetries()));
        errorHandler.addNotRetryableExceptions(
            IllegalArgumentException.class,
            ReadingEventConflictException.class);
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) -> {
            if (deliveryAttempt > 1) {
                meterRegistry.counter("novel.reading.kafka.retry").increment();
            }
        });
        return errorHandler;
    }

    @Bean(defaultCandidate = false)
    ConcurrentKafkaListenerContainerFactory<Long, ReadingEngagementEvent>
        readingEngagementKafkaListenerContainerFactory(
            @Qualifier("readingEngagementConsumerFactory")
            DefaultKafkaConsumerFactory<Long, ReadingEngagementEvent> consumerFactory,
            @Qualifier("readingEngagementErrorHandler")
            DefaultErrorHandler readingEngagementErrorHandler
        ) {
        ConcurrentKafkaListenerContainerFactory<Long, ReadingEngagementEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.setCommonErrorHandler(readingEngagementErrorHandler);
        return factory;
    }
}
