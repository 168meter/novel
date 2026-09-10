package com.java2nb.novel.engagement;

public enum ReadingHeartbeatOutcome {
    ACCEPTED,
    DUPLICATE,
    INVALID_PAGE,
    SESSION_RATE_LIMITED,
    IP_RATE_LIMITED,
    DAILY_CAP_REACHED,
    REDIS_ERROR
}
