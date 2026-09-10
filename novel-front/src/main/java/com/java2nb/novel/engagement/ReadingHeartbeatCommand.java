package com.java2nb.novel.engagement;

import java.time.LocalDate;

public record ReadingHeartbeatCommand(
    Long bookId,
    Long chapterId,
    String pageVisitId,
    long sequence,
    String sessionHash,
    String ipHmac,
    LocalDate statDate,
    long nowEpochMillis
) {
}
