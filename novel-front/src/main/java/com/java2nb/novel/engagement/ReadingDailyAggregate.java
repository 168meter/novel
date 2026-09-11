package com.java2nb.novel.engagement;

import java.time.Instant;
import java.time.LocalDate;

public record ReadingDailyAggregate(
    LocalDate statDate,
    long bookId,
    long creditedSeconds,
    long heartbeatCount,
    Instant firstEventAt,
    Instant lastEventAt
) {
}
