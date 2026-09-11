package com.java2nb.novel.engagement;

import com.java2nb.novel.event.ReadingEngagementEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class ReadingDailyBatchAggregator {

    private static final Comparator<DailyKey> DAILY_KEY_ORDER =
        Comparator.comparing(DailyKey::statDate).thenComparingLong(DailyKey::bookId);

    public List<ReadingDailyAggregate> aggregate(List<ReadingEngagementEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        Map<DailyKey, MutableAggregate> grouped = new TreeMap<>(DAILY_KEY_ORDER);
        for (ReadingEngagementEvent event : events) {
            Objects.requireNonNull(event, "event must not be null");
            DailyKey key = new DailyKey(event.statDate(), event.bookId());
            grouped.computeIfAbsent(key, ignored -> new MutableAggregate())
                .add(event.creditedSeconds(), event.occurredAt());
        }

        List<ReadingDailyAggregate> result = new ArrayList<>(grouped.size());
        grouped.forEach((key, value) -> result.add(value.toAggregate(key)));
        return List.copyOf(result);
    }

    static long addExact(long current, long increment) {
        return Math.addExact(current, increment);
    }

    private record DailyKey(LocalDate statDate, long bookId) {
    }

    private static final class MutableAggregate {
        private long creditedSeconds;
        private long heartbeatCount;
        private Instant firstEventAt;
        private Instant lastEventAt;

        private void add(long seconds, Instant occurredAt) {
            creditedSeconds = addExact(creditedSeconds, seconds);
            heartbeatCount = addExact(heartbeatCount, 1L);
            if (firstEventAt == null || occurredAt.isBefore(firstEventAt)) {
                firstEventAt = occurredAt;
            }
            if (lastEventAt == null || occurredAt.isAfter(lastEventAt)) {
                lastEventAt = occurredAt;
            }
        }

        private ReadingDailyAggregate toAggregate(DailyKey key) {
            return new ReadingDailyAggregate(
                key.statDate(), key.bookId(), creditedSeconds, heartbeatCount,
                firstEventAt, lastEventAt);
        }
    }
}
