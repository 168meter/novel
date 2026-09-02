package com.java2nb.novel.event;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Versioned event representing one accepted book visit.
 */
public record BookVisitEvent(
    String eventId,
    Long bookId,
    Long delta,
    Instant occurredAt,
    Integer version
) {

    public static BookVisitEvent create(Long bookId, Clock clock) {
        if (bookId == null || bookId <= 0) {
            throw new IllegalArgumentException("bookId must be positive");
        }
        return new BookVisitEvent(
            UUID.randomUUID().toString(), bookId, 1L, clock.instant(), 1);
    }
}
