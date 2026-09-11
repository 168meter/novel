package com.java2nb.novel.engagement;

import com.java2nb.novel.event.ReadingEngagementEvent;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadingEventValidatorTest {

    private static final String EVENT_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-09T16:00:00Z");
    private static final LocalDate STAT_DATE = LocalDate.of(2026, 9, 10);
    private final ReadingEventValidator validator = new ReadingEventValidator();

    @Test
    void acceptsVersionOneEventWhoseDateMatchesShanghaiTime() {
        ReadingEngagementEvent event = event(
            EVENT_ID, 42L, 7L, 30, OCCURRED_AT, STAT_DATE, 1);

        assertThat(validator.validate(event)).isSameAs(event);
    }

    @Test
    void rejectsNullAndMalformedEventIds() {
        assertInvalid(null);
        assertInvalid(event(null, 42L, 7L, 30, OCCURRED_AT, STAT_DATE, 1));
        assertInvalid(event("not-a-uuid", 42L, 7L, 30, OCCURRED_AT, STAT_DATE, 1));
        assertInvalid(event("1-1-1-1-1", 42L, 7L, 30, OCCURRED_AT, STAT_DATE, 1));
    }

    @Test
    void rejectsNonPositiveOrMissingBookAndChapterIds() {
        assertInvalid(event(EVENT_ID, null, 7L, 30, OCCURRED_AT, STAT_DATE, 1));
        assertInvalid(event(EVENT_ID, 0L, 7L, 30, OCCURRED_AT, STAT_DATE, 1));
        assertInvalid(event(EVENT_ID, -1L, 7L, 30, OCCURRED_AT, STAT_DATE, 1));
        assertInvalid(event(EVENT_ID, 42L, null, 30, OCCURRED_AT, STAT_DATE, 1));
        assertInvalid(event(EVENT_ID, 42L, 0L, 30, OCCURRED_AT, STAT_DATE, 1));
        assertInvalid(event(EVENT_ID, 42L, -1L, 30, OCCURRED_AT, STAT_DATE, 1));
    }

    @Test
    void rejectsInvalidSecondsTimestampDateAndVersion() {
        assertInvalid(event(EVENT_ID, 42L, 7L, null, OCCURRED_AT, STAT_DATE, 1));
        assertInvalid(event(EVENT_ID, 42L, 7L, 29, OCCURRED_AT, STAT_DATE, 1));
        assertInvalid(event(EVENT_ID, 42L, 7L, 31, OCCURRED_AT, STAT_DATE, 1));
        assertInvalid(event(EVENT_ID, 42L, 7L, 30, null, STAT_DATE, 1));
        assertInvalid(event(EVENT_ID, 42L, 7L, 30, OCCURRED_AT, null, 1));
        assertInvalid(event(EVENT_ID, 42L, 7L, 30, OCCURRED_AT, STAT_DATE, null));
        assertInvalid(event(EVENT_ID, 42L, 7L, 30, OCCURRED_AT, STAT_DATE, 2));
    }

    @Test
    void rejectsStatDateThatDoesNotMatchShanghaiCalendarDate() {
        ReadingEngagementEvent event = event(
            EVENT_ID, 42L, 7L, 30, OCCURRED_AT, LocalDate.of(2026, 9, 9), 1);

        assertInvalid(event);
    }

    @Test
    void rejectsInstantsOutsideMysqlDatetimeRangeAsInvalidEvents() {
        assertInvalid(event(EVENT_ID, 42L, 7L, 30,
            Instant.parse("0999-12-31T15:59:59.999Z"), LocalDate.of(999, 12, 31), 1));
        assertInvalid(event(EVENT_ID, 42L, 7L, 30,
            Instant.parse("9999-12-31T16:00:00Z"), LocalDate.of(10000, 1, 1), 1));
        assertInvalid(event(EVENT_ID, 42L, 7L, 30,
            Instant.MAX, LocalDate.MAX, 1));
    }

    @Test
    void acceptsMysqlDatetimeBoundaryInstants() {
        ReadingEngagementEvent minimum = event(EVENT_ID, 42L, 7L, 30,
            Instant.parse("0999-12-31T16:00:00Z"), LocalDate.of(1000, 1, 1), 1);
        ReadingEngagementEvent maximum = event(EVENT_ID, 42L, 7L, 30,
            Instant.parse("9999-12-31T15:59:59.999Z"), LocalDate.of(9999, 12, 31), 1);

        assertThat(validator.validate(minimum)).isSameAs(minimum);
        assertThat(validator.validate(maximum)).isSameAs(maximum);
    }

    @Test
    void conflictExceptionPreservesOnlyTheEventId() {
        ReadingEventConflictException conflict = new ReadingEventConflictException(EVENT_ID);

        assertThat(conflict.eventId()).isEqualTo(EVENT_ID);
        assertThat(conflict.getMessage()).doesNotContain(EVENT_ID);
    }

    private void assertInvalid(ReadingEngagementEvent event) {
        assertThatThrownBy(() -> validator.validate(event))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static ReadingEngagementEvent event(
        String eventId,
        Long bookId,
        Long chapterId,
        Integer seconds,
        Instant occurredAt,
        LocalDate statDate,
        Integer version
    ) {
        return new ReadingEngagementEvent(
            eventId, bookId, chapterId, seconds, occurredAt, statDate, version);
    }
}
