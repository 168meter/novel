package com.java2nb.novel.engagement;

import com.java2nb.novel.event.ReadingEngagementEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ReadingEventValidator {

    private static final int SUPPORTED_VERSION = ReadingEngagementEvent.VERSION;
    private static final int CREDITED_SECONDS = ReadingEngagementEvent.CREDITED_SECONDS;
    private static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant MIN_DATABASE_INSTANT = LocalDateTime.of(
        1000, 1, 1, 0, 0).atZone(STAT_ZONE).toInstant();
    private static final Instant MAX_DATABASE_INSTANT = LocalDateTime.of(
        9999, 12, 31, 23, 59, 59, 999_000_000).atZone(STAT_ZONE).toInstant();
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public ReadingEngagementEvent validate(ReadingEngagementEvent event) {
        if (event == null) {
            throw invalid("event must not be null");
        }
        validateEventId(event.eventId());
        if (event.bookId() == null || event.bookId() <= 0) {
            throw invalid("bookId must be positive");
        }
        if (event.chapterId() == null || event.chapterId() <= 0) {
            throw invalid("chapterId must be positive");
        }
        if (event.creditedSeconds() == null || event.creditedSeconds() != CREDITED_SECONDS) {
            throw invalid("creditedSeconds must be 10");
        }
        if (event.occurredAt() == null) {
            throw invalid("occurredAt must not be null");
        }
        if (event.occurredAt().isBefore(MIN_DATABASE_INSTANT)
            || event.occurredAt().isAfter(MAX_DATABASE_INSTANT)) {
            throw invalid("occurredAt is outside the database time range");
        }
        if (event.statDate() == null) {
            throw invalid("statDate must not be null");
        }
        if (event.version() == null || event.version() != SUPPORTED_VERSION) {
            throw invalid("version must be 1");
        }
        if (!event.statDate().equals(event.occurredAt().atZone(STAT_ZONE).toLocalDate())) {
            throw invalid("statDate does not match occurredAt");
        }
        return event;
    }

    private static void validateEventId(String eventId) {
        if (eventId == null || !UUID_PATTERN.matcher(eventId).matches()) {
            throw invalid("eventId must be a canonical UUID");
        }
        try {
            UUID.fromString(eventId);
        } catch (IllegalArgumentException malformed) {
            throw invalid("eventId must be a canonical UUID");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
