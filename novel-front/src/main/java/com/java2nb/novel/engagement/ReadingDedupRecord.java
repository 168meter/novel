package com.java2nb.novel.engagement;

import java.time.LocalDate;

public record ReadingDedupRecord(
    String eventId,
    byte[] eventFingerprint,
    String batchToken,
    LocalDate statDate
) {
}
