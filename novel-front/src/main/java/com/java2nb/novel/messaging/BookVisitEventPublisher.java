package com.java2nb.novel.messaging;

import com.java2nb.novel.config.BookVisitKafkaProperties;
import com.java2nb.novel.event.BookVisitEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes noncritical book visit events without blocking on broker acknowledgement.
 */
@Slf4j
@Component
public class BookVisitEventPublisher {

    private final KafkaTemplate<Long, BookVisitEvent> kafkaTemplate;
    private final BookVisitKafkaProperties properties;
    private final Clock clock;
    private final Counter successCounter;
    private final Counter failedCounter;

    public BookVisitEventPublisher(
        KafkaTemplate<Long, BookVisitEvent> kafkaTemplate,
        BookVisitKafkaProperties properties,
        MeterRegistry meterRegistry,
        Clock clock
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
        this.successCounter = meterRegistry.counter(
            "novel.book.visit.kafka.send", "result", "success");
        this.failedCounter = meterRegistry.counter(
            "novel.book.visit.kafka.send", "result", "failed");
    }

    public void publish(Long bookId) {
        BookVisitEvent event = BookVisitEvent.create(bookId, clock);
        try {
            kafkaTemplate.send(properties.topic(), bookId, event)
                .whenComplete((result, failure) -> {
                    if (failure == null) {
                        successCounter.increment();
                    } else {
                        failedCounter.increment();
                        log.warn("Failed to publish book visit event bookId={} eventId={}",
                            bookId, event.eventId(), failure);
                    }
                });
        } catch (KafkaException failure) {
            failedCounter.increment();
            log.warn("Failed to start publishing book visit event bookId={} eventId={}",
                bookId, event.eventId(), failure);
        }
    }
}
