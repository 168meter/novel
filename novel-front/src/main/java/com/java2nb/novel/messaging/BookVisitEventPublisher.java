package com.java2nb.novel.messaging;

import com.java2nb.novel.config.BookVisitKafkaProperties;
import com.java2nb.novel.event.BookVisitEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final long FAILURE_LOG_INTERVAL = 1000L;
    private final KafkaTemplate<Long, BookVisitEvent> kafkaTemplate;
    private final BookVisitKafkaProperties properties;
    private final Clock clock;
    private final Counter successCounter;
    private final Counter failedCounter;
    private final AtomicLong failureCount = new AtomicLong();

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
                        recordFailure(bookId, event, failure);
                    }
                });
        } catch (KafkaException failure) {
            recordFailure(bookId, event, failure);
        }
    }

    private void recordFailure(Long bookId, BookVisitEvent event, Throwable failure) {
        failedCounter.increment();
        long totalFailures = failureCount.incrementAndGet();
        if (totalFailures == 1L || totalFailures % FAILURE_LOG_INTERVAL == 0L) {
            log.warn("Kafka book visit publish failures={} latestBookId={} latestEventId={}",
                totalFailures, bookId, event.eventId(), failure);
        }
    }
}
