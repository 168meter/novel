package com.java2nb.novel.config;

import com.java2nb.novel.event.ReadingEngagementEvent;
import com.java2nb.novel.engagement.ReadingEventConflictException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.FixedBackOff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class ReadingEngagementKafkaConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
        .withUserConfiguration(BookVisitKafkaConfig.class, ReadingEngagementKafkaConfig.class)
        .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
        .withPropertyValues(
            "spring.kafka.bootstrap-servers=localhost:9092",
            "novel.kafka.book-visit.topic=novel-book-visit-v1",
            "novel.kafka.book-visit.dlt-topic=novel-book-visit-dlt",
            "novel.kafka.book-visit.group-id=novel-book-visit-writer-v1",
            "novel.kafka.book-visit.max-poll-records=500");

    @Test
    void createsDedicatedTopicsAndTypedBatchFactory() {
        ReadingEngagementKafkaProperties properties = new ReadingEngagementKafkaProperties();
        ReadingEngagementKafkaConfig config = new ReadingEngagementKafkaConfig(properties);
        KafkaProperties springProperties = new KafkaProperties();

        NewTopic topic = config.readingEngagementTopic();
        NewTopic dlt = config.readingEngagementDltTopic();
        DefaultKafkaConsumerFactory<Long, ReadingEngagementEvent> consumerFactory =
            config.readingEngagementConsumerFactory(springProperties);
        DefaultErrorHandler errorHandler = config.readingEngagementErrorHandler(
            mock(KafkaTemplate.class), new SimpleMeterRegistry());
        ConcurrentKafkaListenerContainerFactory<Long, ReadingEngagementEvent> listenerFactory =
            config.readingEngagementKafkaListenerContainerFactory(consumerFactory, errorHandler);

        assertThat(topic.name()).isEqualTo("novel-reading-engagement-v1");
        assertThat(dlt.name()).isEqualTo("novel-reading-engagement-dlt");
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(dlt.numPartitions()).isEqualTo(3);
        Map<String, Object> consumer = consumerFactory.getConfigurationProperties();
        assertThat(consumer.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo(500);
        assertThat(consumer.get(JsonDeserializer.VALUE_DEFAULT_TYPE))
            .isEqualTo(ReadingEngagementEvent.class.getName());
        assertThat(consumer.get(JsonDeserializer.USE_TYPE_INFO_HEADERS)).isEqualTo(false);
        assertThat(listenerFactory.isBatchListener()).isTrue();
        assertThat(listenerFactory.getContainerProperties().getAckMode())
            .isEqualTo(ContainerProperties.AckMode.BATCH);
        assertThat(errorHandler.isAckAfterHandle()).isTrue();
        Object failureTracker = ReflectionTestUtils.getField(errorHandler, "failureTracker");
        FixedBackOff backOff = (FixedBackOff) ReflectionTestUtils.getField(
            failureTracker, "backOff");
        assertThat(backOff.getInterval()).isEqualTo(5000L);
        assertThat(backOff.getMaxAttempts()).isEqualTo(12L);
    }

    @Test
    void keepsBookVisitHandlerOnDefaultFactoryAndReadingHandlerOnDedicatedFactory() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            DefaultErrorHandler bookVisit = context.getBean(
                "bookVisitErrorHandler", DefaultErrorHandler.class);
            DefaultErrorHandler reading = context.getBean(
                "readingEngagementErrorHandler", DefaultErrorHandler.class);
            ConcurrentKafkaListenerContainerFactory<?, ?> defaultFactory = context.getBean(
                "kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
            ConcurrentKafkaListenerContainerFactory<?, ?> readingFactory = context.getBean(
                "readingEngagementKafkaListenerContainerFactory",
                ConcurrentKafkaListenerContainerFactory.class);
            DefaultKafkaConsumerFactory<?, ?> readingConsumerFactory = context.getBean(
                "readingEngagementConsumerFactory", DefaultKafkaConsumerFactory.class);

            assertThat(ReflectionTestUtils.getField(defaultFactory, "commonErrorHandler"))
                .isSameAs(bookVisit);
            assertThat(ReflectionTestUtils.getField(readingFactory, "commonErrorHandler"))
                .isSameAs(reading);
            assertThat(defaultFactory.getConsumerFactory()).isNotSameAs(readingConsumerFactory);
            assertThat(readingFactory.getConsumerFactory()).isSameAs(readingConsumerFactory);
        });
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sendsNonRetryableFailuresToTheSameDltPartitionAndRecordsMetrics() {
        ReadingEngagementKafkaProperties properties = new ReadingEngagementKafkaProperties();
        ReadingEngagementKafkaConfig config = new ReadingEngagementKafkaConfig(properties);
        KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
        SendResult<Object, Object> sendResult = mock(SendResult.class);
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(sendResult));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DefaultErrorHandler handler = config.readingEngagementErrorHandler(template, registry);
        Consumer consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);

        assertThat(handler.handleOne(new IllegalArgumentException("invalid"),
            record(2, 10L), consumer, container)).isTrue();
        assertThat(handler.handleOne(new ReadingEventConflictException("conflict"),
            record(1, 11L), consumer, container)).isTrue();

        var captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, org.mockito.Mockito.times(2)).send(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(value -> value.topic() + ":" + value.partition())
            .containsExactly("novel-reading-engagement-dlt:2",
                "novel-reading-engagement-dlt:1");
        assertThat(registry.counter("novel.reading.kafka.dlt").count()).isEqualTo(2);
        assertThat(registry.counter("novel.reading.kafka.retry").count()).isZero();
        assertThat(registry.counter("novel.reading.kafka.dlt_publish_failures").count())
            .isZero();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void doesNotMarkRecordRecoveredWhenDltPublishFails() {
        ReadingEngagementKafkaConfig config = new ReadingEngagementKafkaConfig(
            new ReadingEngagementKafkaProperties());
        KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new KafkaException("dlt unavailable")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DefaultErrorHandler handler = config.readingEngagementErrorHandler(template, registry);

        boolean recovered = handler.handleOne(new IllegalArgumentException("invalid"),
            record(0, 12L), mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(recovered).isFalse();
        assertThat(registry.counter("novel.reading.kafka.dlt").count()).isZero();
        assertThat(registry.counter("novel.reading.kafka.dlt_publish_failures").count())
            .isEqualTo(1);
    }

    private static ConsumerRecord<Long, ReadingEngagementEvent> record(int partition, long offset) {
        ReadingEngagementEvent event = ReadingEngagementEvent.create(
            42L, 7L, 30, Instant.parse("2026-09-10T00:00:00Z"),
            LocalDate.of(2026, 9, 10));
        return new ConsumerRecord<>("novel-reading-engagement-v1", partition, offset, 42L, event);
    }
}
