package com.java2nb.novel.messaging;

import com.java2nb.novel.event.BookVisitEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookVisitBatchAggregatorTest {

    private final BookVisitBatchAggregator aggregator = new BookVisitBatchAggregator();

    @Test
    void aggregatesVisitsByBook() {
        List<BookVisitEvent> events = List.of(
            event(1L, 1L), event(1L, 1L), event(2L, 1L), event(1L, 1L));

        assertThat(aggregator.aggregate(events))
            .containsExactlyInAnyOrderEntriesOf(Map.of(1L, 3L, 2L, 1L));
    }

    @Test
    void rejectsInvalidEvent() {
        assertThatThrownBy(() -> aggregator.aggregate(List.of(event(0L, 1L))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bookId");
        assertThatThrownBy(() -> aggregator.aggregate(List.of(event(1L, 0L))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("delta");
    }

    @Test
    void rejectsNullEvent() {
        assertThatThrownBy(() -> aggregator.aggregate(java.util.Arrays.asList((BookVisitEvent) null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bookId");
    }

    @Test
    void returnsImmutableEmptyMapForEmptyBatch() {
        Map<Long, Long> totals = aggregator.aggregate(List.of());

        assertThat(totals).isEmpty();
        assertThatThrownBy(() -> totals.put(1L, 1L))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsOverflowInsteadOfSilentlyWrapping() {
        assertThatThrownBy(() -> aggregator.aggregate(List.of(
            event(1L, Long.MAX_VALUE), event(1L, 1L))))
            .isInstanceOf(ArithmeticException.class);
    }

    private static BookVisitEvent event(Long bookId, Long delta) {
        return new BookVisitEvent(
            "event-" + bookId + "-" + delta,
            bookId,
            delta,
            Instant.parse("2026-09-02T00:00:00Z"),
            1);
    }
}
