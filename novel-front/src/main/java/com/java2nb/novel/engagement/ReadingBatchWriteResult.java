package com.java2nb.novel.engagement;

public record ReadingBatchWriteResult(
    int receivedEvents,
    int newEvents,
    int deduplicatedEvents,
    int dailyRows,
    long persistedSeconds
) {
}
