package com.java2nb.novel.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Privacy-minimized event representing one credited reading interval.
 */
public record ReadingEngagementEvent(
    String eventId,
    Long bookId,
    Long chapterId,
    Integer creditedSeconds,
    Instant occurredAt,
    LocalDate statDate,
    Integer version
) {

    private static final int CREDITED_SECONDS = 30;

    public static ReadingEngagementEvent create(
        Long bookId,
        Long chapterId,
        int creditedSeconds,
        Instant occurredAt,
        LocalDate statDate
    ) {
        if (bookId == null || bookId <= 0) {
            throw new IllegalArgumentException("bookId must be positive");
        }
        if (chapterId == null || chapterId <= 0) {
            throw new IllegalArgumentException("chapterId must be positive");
        }
        if (creditedSeconds != CREDITED_SECONDS) {
            throw new IllegalArgumentException("creditedSeconds must be 30");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
        if (statDate == null) {
            throw new IllegalArgumentException("statDate must not be null");
        }
        return new ReadingEngagementEvent(
            UUID.randomUUID().toString(), bookId, chapterId, creditedSeconds, occurredAt, statDate, 1);
    }
}
