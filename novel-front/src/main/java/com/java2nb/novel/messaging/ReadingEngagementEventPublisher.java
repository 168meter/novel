package com.java2nb.novel.messaging;

import com.java2nb.novel.config.ReadingEngagementKafkaProperties;
import com.java2nb.novel.event.ReadingEngagementEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes privacy-minimized reading events without blocking on broker acknowledgement.
 */
@Slf4j
@Component
public class ReadingEngagementEventPublisher {

    private static final long FAILURE_LOG_INTERVAL = 1000L;
    private final KafkaTemplate<Long, ReadingEngagementEvent> kafkaTemplate;
    private final ReadingEngagementKafkaProperties properties;
    private final Counter successCounter;
    private final Counter failedCounter;
    private final AtomicLong failureCount = new AtomicLong();

    public ReadingEngagementEventPublisher(
        KafkaTemplate<Long, ReadingEngagementEvent> kafkaTemplate,
        ReadingEngagementKafkaProperties properties,
        MeterRegistry meterRegistry,
        Clock clock
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.successCounter = meterRegistry.counter(
            "novel.reading.kafka.send", "result", "success");
        this.failedCounter = meterRegistry.counter(
            "novel.reading.kafka.send", "result", "failed");
    }

    public void publish(
        Long bookId,
        Long chapterId,
        int creditedSeconds,
        Instant occurredAt,
        LocalDate statDate
    ) {
        try {
            ReadingEngagementEvent event = ReadingEngagementEvent.create(
                bookId, chapterId, creditedSeconds, occurredAt, statDate);
            kafkaTemplate.send(properties.topic(), bookId, event)
                .whenComplete((result, failure) -> {
                    if (failure == null) {
                        successCounter.increment();
                    } else {
                        recordFailure();
                    }
                });
        } catch (RuntimeException failure) {
            recordFailure();
        }
    }

    private void recordFailure() {
        failedCounter.increment();
        long totalFailures = failureCount.incrementAndGet();
        if (totalFailures == 1L || totalFailures % FAILURE_LOG_INTERVAL == 0L) {
            log.warn("Kafka reading engagement publish failures={}", totalFailures);
        }
    }
}
