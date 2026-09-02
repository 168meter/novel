package com.java2nb.novel.messaging;

import com.java2nb.novel.event.BookVisitEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Collapses a Kafka batch into one database increment per distinct book.
 */
@Component
public class BookVisitBatchAggregator {

    public Map<Long, Long> aggregate(List<BookVisitEvent> events) {
        Map<Long, Long> totals = new LinkedHashMap<>();
        for (BookVisitEvent event : events) {
            if (event == null || event.bookId() == null || event.bookId() <= 0) {
                throw new IllegalArgumentException("bookId must be positive");
            }
            if (event.delta() == null || event.delta() <= 0) {
                throw new IllegalArgumentException("delta must be positive");
            }
            totals.merge(event.bookId(), event.delta(), Math::addExact);
        }
        return Map.copyOf(totals);
    }
}
