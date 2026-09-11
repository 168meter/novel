package com.java2nb.novel.engagement;

public record ReadingDedupState(
    String eventId,
    byte[] eventFingerprint,
    String batchToken
) {
}
