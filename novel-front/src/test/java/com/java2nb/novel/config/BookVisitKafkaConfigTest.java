package com.java2nb.novel.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.ZoneOffset;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import com.java2nb.novel.messaging.BookVisitBatchAggregator.InvalidVisitEventException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.support.SendResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class BookVisitKafkaConfigTest {

    @Test
    void createsVersionedTopicsAndRetryHandler() {
        BookVisitKafkaProperties properties = new BookVisitKafkaProperties(
            "visit-topic", "visit-dlt", "visit-group", 500);
        BookVisitKafkaConfig config = new BookVisitKafkaConfig(properties);
        @SuppressWarnings("unchecked")
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);

        NewTopic visitTopic = config.visitTopic();
        NewTopic dltTopic = config.visitDltTopic();
        DefaultErrorHandler errorHandler = config.bookVisitErrorHandler(
            kafkaTemplate, new SimpleMeterRegistry());

        assertThat(visitTopic.name()).isEqualTo("visit-topic");
        assertThat(visitTopic.numPartitions()).isEqualTo(3);
        assertThat(visitTopic.replicationFactor()).isEqualTo((short) 1);
        assertThat(dltTopic.name()).isEqualTo("visit-dlt");
        assertThat(dltTopic.numPartitions()).isEqualTo(3);
        assertThat(config.clock().getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(errorHandler).isNotNull();
        assertThat(errorHandler.isAckAfterHandle()).isTrue();
    }

    @Test
    void dltSerializersPreserveOriginalBytesAndAlsoSupportTypedVisitEvents() throws Exception {
        var config = new BookVisitKafkaConfig(new BookVisitKafkaProperties("visit-topic", "visit-dlt", "group", 500));
        var factory = config.bookVisitDltProducerFactory(new org.springframework.boot.autoconfigure.kafka.KafkaProperties());
        try {
            byte[] raw = {(byte)0xff, 0, 1, 2};
            assertThat(factory.getKeySerializer().serialize("visit-dlt", raw)).containsExactly(raw);
            assertThat(factory.getValueSerializer().serialize("visit-dlt", raw)).containsExactly(raw);
            assertThat(factory.getKeySerializer().serialize("visit-dlt", 42L))
                .containsExactly(java.nio.ByteBuffer.allocate(8).putLong(42L).array());
            var bad = new com.java2nb.novel.event.BookVisitEvent("bad", 42L, 0L, java.time.Instant.now(), 1);
            var encoded = factory.getValueSerializer().serialize("visit-dlt", bad);
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(encoded).get("delta").asLong()).isZero();
            assertThat(factory.getValueSerializer().serialize("visit-dlt", null)).isNull();
        } finally {factory.destroy();}
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void recoversOriginalKeyAndValueBytesFromDeserializationHeaders() {
        var config = new BookVisitKafkaConfig(new BookVisitKafkaProperties("visit-topic", "visit-dlt", "group", 500));
        KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var record = new ConsumerRecord<Object, Object>("visit-topic", 0, 10L, null, null);
        byte[] key = {1, 2, 3}, value = {(byte)0xff, 0, 4};
        org.springframework.kafka.support.serializer.SerializationUtils.deserializationException(
            record.headers(), key, new IllegalArgumentException("invalid key bytes"), true);
        org.springframework.kafka.support.serializer.SerializationUtils.deserializationException(
            record.headers(), value, new IllegalArgumentException("invalid value bytes"), false);
        var handler = config.bookVisitErrorHandler(template, new SimpleMeterRegistry());
        assertThat(handler.handleOne(new InvalidVisitEventException("null event"), record,
            mock(Consumer.class), mock(MessageListenerContainer.class))).isTrue();
        var capture = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(template).send(capture.capture());
        assertThat((byte[])capture.getValue().key()).containsExactly(key);
        assertThat((byte[])capture.getValue().value()).containsExactly(value);
    }

    @Test
    void newDltFactoryDoesNotReplaceDefaultOrReadingKafkaClients() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration.class))
            .withUserConfiguration(BookVisitKafkaConfig.class, ReadingEngagementKafkaConfig.class)
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:1",
                "spring.kafka.admin.auto-create=false", "spring.kafka.listener.auto-startup=false",
                "spring.kafka.listener.type=batch", "spring.kafka.listener.ack-mode=batch",
                "novel.kafka.book-visit.topic=visit-topic", "novel.kafka.book-visit.dlt-topic=visit-dlt",
                "novel.kafka.book-visit.group-id=group", "novel.kafka.book-visit.max-poll-records=500")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var factory = context.getBean("kafkaListenerContainerFactory",
                    org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory.class);
                assertThat(factory.isBatchListener()).isTrue();
                assertThat(factory.getContainerProperties().getAckMode())
                    .isEqualTo(org.springframework.kafka.listener.ContainerProperties.AckMode.BATCH);
                assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler"))
                    .isSameAs(context.getBean("bookVisitErrorHandler"));
                assertThat(context.getBean("kafkaTemplate")).isNotSameAs(context.getBean("bookVisitDltKafkaTemplate"));
                var reading = context.getBean("readingEngagementKafkaListenerContainerFactory",
                    org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory.class);
                assertThat(ReflectionTestUtils.getField(reading, "commonErrorHandler"))
                    .isSameAs(context.getBean("readingEngagementErrorHandler"));
                assertThat(DefaultErrorHandler.class.getPackage().getImplementationVersion()).isEqualTo("3.3.0");
            });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void onlyRecoversExplicitBadEventsAndDoesNotRecoverFailedDltSends() {
        var config = new BookVisitKafkaConfig(new BookVisitKafkaProperties("visit-topic", "visit-dlt", "group", 500));
        KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new org.springframework.kafka.KafkaException("DLT offline")))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var registry = new SimpleMeterRegistry();
        var handler = config.bookVisitErrorHandler(template, registry);
        var record = new ConsumerRecord<>("visit-topic", 0, 11L, 1L, new com.java2nb.novel.event.BookVisitEvent("bad", 1L, 0L, java.time.Instant.now(), 1));
        assertThat(handler.handleOne(new InvalidVisitEventException("delta"), record,
            mock(Consumer.class), mock(MessageListenerContainer.class))).isFalse();
        assertThat(registry.counter("novel.book.visit.kafka.dlt").count()).isZero();
        assertThat(handler.handleOne(new InvalidVisitEventException("delta"), record,
            mock(Consumer.class), mock(MessageListenerContainer.class))).isTrue();
        assertThat(registry.counter("novel.book.visit.kafka.dlt").count()).isEqualTo(1);
        assertThat(registry.counter("novel.book.visit.kafka.dlt_publish_failures").count()).isEqualTo(1);
        var tracker = ReflectionTestUtils.getField(handler, "failureTracker");
        var backOff = (FixedBackOff) ReflectionTestUtils.getField(tracker, "backOff");
        assertThat(backOff.getMaxAttempts()).isEqualTo(FixedBackOff.UNLIMITED_ATTEMPTS);
        var classifier = (org.springframework.classify.BinaryExceptionClassifier) ReflectionTestUtils.invokeMethod(handler, "getClassifier");
        assertThat(classifier.classify(new IllegalArgumentException("SQL API failure"))).isTrue();
        assertThat(classifier.classify(new org.springframework.dao.DataAccessResourceFailureException("DB offline"))).isTrue();
        assertThat(classifier.classify(new InvalidVisitEventException("bad delta"))).isFalse();
    }
}
