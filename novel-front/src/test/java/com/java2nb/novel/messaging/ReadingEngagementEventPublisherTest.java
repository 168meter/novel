package com.java2nb.novel.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.java2nb.novel.engagement.ReadingEngagementProperties;
import com.java2nb.novel.event.ReadingEngagementEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadingEngagementEventPublisherTest {

    private KafkaTemplate<Long, ReadingEngagementEvent> kafkaTemplate;
    private SimpleMeterRegistry registry;
    private ReadingEngagementEventPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        registry = new SimpleMeterRegistry();
        ReadingEngagementProperties properties = new ReadingEngagementProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC);
        publisher = new ReadingEngagementEventPublisher(kafkaTemplate, properties, registry, clock);
    }

    @Test
    void publishesPrivacyMinimizedEventAndRecordsSuccess() {
        @SuppressWarnings("unchecked")
        SendResult<Long, ReadingEngagementEvent> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq("novel-reading-engagement-v1"), eq(42L), any(ReadingEngagementEvent.class)))
            .thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publish(42L, 7L, 30, Instant.parse("2026-09-10T00:00:00Z"), LocalDate.of(2026, 9, 10));

        var eventCaptor = org.mockito.ArgumentCaptor.forClass(ReadingEngagementEvent.class);
        verify(kafkaTemplate).send(eq("novel-reading-engagement-v1"), eq(42L), eventCaptor.capture());
        ReadingEngagementEvent event = eventCaptor.getValue();
        assertThat(event.bookId()).isEqualTo(42L);
        assertThat(event.chapterId()).isEqualTo(7L);
        assertThat(event.creditedSeconds()).isEqualTo(30);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-10T00:00:00Z"));
        assertThat(event.statDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(registry.counter("novel.reading.kafka.send", "result", "success").count()).isEqualTo(1);
    }

    @Test
    void recordsAsynchronousSendFailureWithoutPropagating() {
        when(kafkaTemplate.send(eq("novel-reading-engagement-v1"), eq(42L), any(ReadingEngagementEvent.class)))
            .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker unavailable")));

        assertThatCode(() -> publisher.publish(
            42L, 7L, 30, Instant.parse("2026-09-10T00:00:00Z"), LocalDate.of(2026, 9, 10)))
            .doesNotThrowAnyException();

        assertThat(registry.counter("novel.reading.kafka.send", "result", "failed").count()).isEqualTo(1);
    }

    @Test
    void recordsSynchronousSendFailureWithoutPropagating() {
        when(kafkaTemplate.send(eq("novel-reading-engagement-v1"), eq(42L), any(ReadingEngagementEvent.class)))
            .thenThrow(new KafkaException("metadata unavailable"));

        assertThatCode(() -> publisher.publish(
            42L, 7L, 30, Instant.parse("2026-09-10T00:00:00Z"), LocalDate.of(2026, 9, 10)))
            .doesNotThrowAnyException();

        assertThat(registry.counter("novel.reading.kafka.send", "result", "failed").count()).isEqualTo(1);
    }

    @Test
    void recordsNonKafkaRuntimeSendFailureWithoutPropagating() {
        when(kafkaTemplate.send(eq("novel-reading-engagement-v1"), eq(42L), any(ReadingEngagementEvent.class)))
            .thenThrow(new IllegalStateException("producer state unavailable"));

        assertThatCode(() -> publisher.publish(
            42L, 7L, 30, Instant.parse("2026-09-10T00:00:00Z"), LocalDate.of(2026, 9, 10)))
            .doesNotThrowAnyException();

        assertThat(registry.counter("novel.reading.kafka.send", "result", "failed").count()).isEqualTo(1);
    }

    @Test
    void recordsEventConstructionFailureWithoutPropagating() {
        assertThatCode(() -> publisher.publish(
            42L, 7L, 60, Instant.parse("2026-09-10T00:00:00Z"), LocalDate.of(2026, 9, 10)))
            .doesNotThrowAnyException();

        assertThat(registry.counter("novel.reading.kafka.send", "result", "failed").count()).isEqualTo(1);
    }

    @Test
    void logsFailureCountWithoutCanaryExceptionDataOrThrowableProxy() {
        RuntimeException canary = new RuntimeException(
            "cookie=canary session=canary pageVisitId=canary ip=canary");
        when(kafkaTemplate.send(eq("novel-reading-engagement-v1"), eq(42L), any(ReadingEngagementEvent.class)))
            .thenReturn(CompletableFuture.failedFuture(canary));
        Logger logger = (Logger) LoggerFactory.getLogger(ReadingEngagementEventPublisher.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            publisher.publish(42L, 7L, 30, Instant.parse("2026-09-10T00:00:00Z"),
                LocalDate.of(2026, 9, 10));

            assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .hasSize(1)
                .allSatisfy(event -> {
                    assertThat(event.getFormattedMessage()).doesNotContain(
                        "cookie", "session", "pageVisitId", "ip", "canary");
                    assertThat(event.getThrowableProxy()).isNull();
                });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void rateLimitsWarningsToFirstAndThousandthFailure() {
        when(kafkaTemplate.send(eq("novel-reading-engagement-v1"), eq(42L), any(ReadingEngagementEvent.class)))
            .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker overloaded")));
        Logger logger = (Logger) LoggerFactory.getLogger(ReadingEngagementEventPublisher.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            for (int failure = 0; failure < 1001; failure++) {
                publisher.publish(42L, 7L, 30, Instant.parse("2026-09-10T00:00:00Z"),
                    LocalDate.of(2026, 9, 10));
            }

            assertThat(registry.counter("novel.reading.kafka.send", "result", "failed").count())
                .isEqualTo(1001);
            assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .containsExactly("Kafka reading engagement publish failures=1",
                    "Kafka reading engagement publish failures=1000");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
