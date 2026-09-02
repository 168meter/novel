package com.java2nb.novel.messaging;

import com.java2nb.novel.event.BookVisitEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes a Kafka batch, aggregates it, and persists it transactionally.
 */
@Component
public class BookVisitEventConsumer {

    private final BookVisitBatchAggregator aggregator;
    private final BookVisitBatchWriter writer;
    private final Counter consumedCounter;
    private final Counter databaseUpdateCounter;
    private final DistributionSummary batchSizeSummary;
    private final DistributionSummary aggregatedBookSummary;

    public BookVisitEventConsumer(
        BookVisitBatchAggregator aggregator,
        BookVisitBatchWriter writer,
        MeterRegistry meterRegistry
    ) {
        this.aggregator = aggregator;
        this.writer = writer;
        this.consumedCounter = meterRegistry.counter("novel.book.visit.kafka.consumed");
        this.databaseUpdateCounter = meterRegistry.counter("novel.book.visit.kafka.db_updates");
        this.batchSizeSummary = meterRegistry.summary("novel.book.visit.kafka.batch_size");
        this.aggregatedBookSummary = meterRegistry.summary(
            "novel.book.visit.kafka.aggregated_books");
    }

    @KafkaListener(
        topics = "${novel.kafka.book-visit.topic}",
        groupId = "${novel.kafka.book-visit.group-id}"
    )
    public void consume(List<BookVisitEvent> events) {
        Map<Long, Long> totals = aggregator.aggregate(events);
        writer.write(totals);
        consumedCounter.increment(events.size());
        databaseUpdateCounter.increment(totals.size());
        batchSizeSummary.record(events.size());
        aggregatedBookSummary.record(totals.size());
    }
}
