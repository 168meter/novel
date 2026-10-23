package com.java2nb.novel.engagement;

/** Public, finite-cardinality result returned to the browser heartbeat client. */
public record ReadingHeartbeatResponse(Outcome outcome) {

    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        TOO_EARLY,
        DAILY_CAP,
        RATE_LIMIT,
        INVALID_PAGE,
        REDIS_ERROR
    }
}
