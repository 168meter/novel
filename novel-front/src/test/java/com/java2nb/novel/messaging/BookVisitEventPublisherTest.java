package com.java2nb.novel.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.java2nb.novel.config.BookVisitKafkaProperties;
import com.java2nb.novel.event.BookVisitEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookVisitEventPublisherTest {

    private KafkaTemplate<Long, BookVisitEvent> kafkaTemplate;
    private SimpleMeterRegistry registry;
    private BookVisitEventPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
        BookVisitKafkaProperties properties = new BookVisitKafkaProperties(
            "visit-topic", "visit-dlt", "visit-group", 500);
        publisher = new BookVisitEventPublisher(kafkaTemplate, properties, registry, clock);
    }

    @Test
    void publishesVersionedVisitAndRecordsSuccess() {
        @SuppressWarnings("unchecked")
        SendResult<Long, BookVisitEvent> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq("visit-topic"), eq(42L), any(BookVisitEvent.class)))
            .thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publish(42L);

        var eventCaptor = org.mockito.ArgumentCaptor.forClass(BookVisitEvent.class);
        verify(kafkaTemplate).send(eq("visit-topic"), eq(42L), eventCaptor.capture());
        BookVisitEvent event = eventCaptor.getValue();
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.bookId()).isEqualTo(42L);
        assertThat(event.delta()).isEqualTo(1L);
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
        assertThat(registry.counter("novel.book.visit.kafka.send", "result", "success").count())
            .isEqualTo(1);
    }

    @Test
    void recordsAsynchronousSendFailureWithoutPropagating() {
        when(kafkaTemplate.send(eq("visit-topic"), eq(42L), any(BookVisitEvent.class)))
            .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker unavailable")));

        assertThatCode(() -> publisher.publish(42L)).doesNotThrowAnyException();

        assertThat(registry.counter("novel.book.visit.kafka.send", "result", "failed").count())
            .isEqualTo(1);
    }

    @Test
    void recordsSynchronousSendFailureWithoutPropagating() {
        when(kafkaTemplate.send(eq("visit-topic"), eq(42L), any(BookVisitEvent.class)))
            .thenThrow(new KafkaException("metadata unavailable"));

        assertThatCode(() -> publisher.publish(42L)).doesNotThrowAnyException();

        assertThat(registry.counter("novel.book.visit.kafka.send", "result", "failed").count())
            .isEqualTo(1);
    }

    @Test
    void rateLimitsWarningsButCountsEverySendFailure() {
        when(kafkaTemplate.send(eq("visit-topic"), eq(42L), any(BookVisitEvent.class)))
            .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker overloaded")));
        Logger logger = (Logger) LoggerFactory.getLogger(BookVisitEventPublisher.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            for (int failure = 0; failure < 1001; failure++) {
                publisher.publish(42L);
            }

            assertThat(registry.counter(
                "novel.book.visit.kafka.send", "result", "failed").count())
                .isEqualTo(1001);
            assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .hasSize(2);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void rejectsInvalidBookIdBeforeCallingKafka() {
        assertThatThrownBy(() -> publisher.publish(0L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bookId");
        assertThatThrownBy(() -> publisher.publish(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bookId");

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }
}
