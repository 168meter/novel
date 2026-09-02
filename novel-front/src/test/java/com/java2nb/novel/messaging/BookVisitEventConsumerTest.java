package com.java2nb.novel.messaging;

import com.java2nb.novel.event.BookVisitEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookVisitEventConsumerTest {

    @Test
    void aggregatesThenWritesOneBatchAndRecordsMetrics() {
        BookVisitBatchAggregator aggregator = mock(BookVisitBatchAggregator.class);
        BookVisitBatchWriter writer = mock(BookVisitBatchWriter.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BookVisitEventConsumer consumer = new BookVisitEventConsumer(aggregator, writer, registry);
        List<BookVisitEvent> events = List.of(event(1L), event(1L), event(2L));
        Map<Long, Long> totals = Map.of(1L, 2L, 2L, 1L);
        when(aggregator.aggregate(events)).thenReturn(totals);

        consumer.consume(events);

        verify(writer).write(totals);
        assertThat(registry.counter("novel.book.visit.kafka.consumed").count()).isEqualTo(3);
        assertThat(registry.counter("novel.book.visit.kafka.db_updates").count()).isEqualTo(2);
        assertThat(registry.get("novel.book.visit.kafka.batch_size").summary().count()).isEqualTo(1);
        assertThat(registry.get("novel.book.visit.kafka.batch_size").summary().totalAmount()).isEqualTo(3);
        assertThat(registry.get("novel.book.visit.kafka.aggregated_books").summary().totalAmount()).isEqualTo(2);
    }

    @Test
    void rethrowsWriterFailureWithoutRecordingSuccessfulWork() {
        BookVisitBatchAggregator aggregator = mock(BookVisitBatchAggregator.class);
        BookVisitBatchWriter writer = mock(BookVisitBatchWriter.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BookVisitEventConsumer consumer = new BookVisitEventConsumer(aggregator, writer, registry);
        List<BookVisitEvent> events = List.of(event(1L));
        Map<Long, Long> totals = Map.of(1L, 1L);
        when(aggregator.aggregate(events)).thenReturn(totals);
        doThrow(new IllegalStateException("database offline")).when(writer).write(totals);

        assertThatThrownBy(() -> consumer.consume(events))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("database offline");

        assertThat(registry.counter("novel.book.visit.kafka.consumed").count()).isZero();
        assertThat(registry.counter("novel.book.visit.kafka.db_updates").count()).isZero();
        assertThat(registry.get("novel.book.visit.kafka.batch_size").summary().count()).isZero();
    }

    private static BookVisitEvent event(Long bookId) {
        return new BookVisitEvent(
            "event-" + bookId,
            bookId,
            1L,
            Instant.parse("2026-09-02T00:00:00Z"),
            1);
    }
}
