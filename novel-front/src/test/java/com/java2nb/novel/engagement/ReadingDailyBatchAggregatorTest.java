package com.java2nb.novel.engagement;

import com.java2nb.novel.event.ReadingEngagementEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadingDailyBatchAggregatorTest {

    private final ReadingDailyBatchAggregator aggregator = new ReadingDailyBatchAggregator();

    @Test
    void groupsByDateAndBookWithExactCountsAndTimeBounds() {
        LocalDate firstDate = LocalDate.of(2026, 9, 10);
        LocalDate secondDate = firstDate.plusDays(1);
        Instant early = Instant.parse("2026-09-10T00:00:00Z");
        Instant middle = early.plusSeconds(30);
        Instant late = early.plusSeconds(60);

        List<ReadingDailyAggregate> result = aggregator.aggregate(List.of(
            event("00000000-0000-0000-0000-000000000003", 42L, secondDate, late),
            event("00000000-0000-0000-0000-000000000002", 41L, firstDate, middle),
            event("00000000-0000-0000-0000-000000000001", 42L, firstDate, early),
            event("00000000-0000-0000-0000-000000000004", 42L, firstDate, late)));

        assertThat(result).containsExactly(
            new ReadingDailyAggregate(firstDate, 41L, 30L, 1L, middle, middle),
            new ReadingDailyAggregate(firstDate, 42L, 60L, 2L, early, late),
            new ReadingDailyAggregate(secondDate, 42L, 30L, 1L, late, late));
    }

    @Test
    void returnsAnEmptyDeterministicResultForNoEvents() {
        assertThat(aggregator.aggregate(List.of())).isEmpty();
    }

    @Test
    void usesCheckedAdditionForAggregateCounters() {
        assertThatThrownBy(() -> ReadingDailyBatchAggregator.addExact(Long.MAX_VALUE, 1L))
            .isInstanceOf(ArithmeticException.class);
    }

    private static ReadingEngagementEvent event(
        String eventId,
        long bookId,
        LocalDate statDate,
        Instant occurredAt
    ) {
        return new ReadingEngagementEvent(
            eventId, bookId, 7L, 30, occurredAt, statDate, 1);
    }
}
