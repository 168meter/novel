package com.java2nb.novel.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadingEngagementEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-10T00:00:00Z");
    private static final LocalDate STAT_DATE = LocalDate.of(2026, 9, 10);

    @Test
    void createsVersionedAnonymousCreditEvent() {
        ReadingEngagementEvent event = ReadingEngagementEvent.create(
            42L, 7L, 10, OCCURRED_AT, STAT_DATE);

        assertThat(event.eventId()).isNotBlank();
        assertThat(event.bookId()).isEqualTo(42L);
        assertThat(event.chapterId()).isEqualTo(7L);
        assertThat(event.creditedSeconds()).isEqualTo(10);
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.statDate()).isEqualTo(STAT_DATE);
        assertThat(event.version()).isEqualTo(1);
    }

    @Test
    void assignsDistinctEventIdsForSeparateEvents() {
        ReadingEngagementEvent first = ReadingEngagementEvent.create(
            42L, 7L, 10, OCCURRED_AT, STAT_DATE);
        ReadingEngagementEvent second = ReadingEngagementEvent.create(
            42L, 7L, 10, OCCURRED_AT, STAT_DATE);

        assertThat(second.eventId()).isNotEqualTo(first.eventId());
    }

    @Test
    void rejectsInvalidIdentifiersCreditAndTimes() {
        assertThatThrownBy(() -> ReadingEngagementEvent.create(0L, 7L, 10, OCCURRED_AT, STAT_DATE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bookId");
        assertThatThrownBy(() -> ReadingEngagementEvent.create(42L, null, 10, OCCURRED_AT, STAT_DATE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("chapterId");
        assertThatThrownBy(() -> ReadingEngagementEvent.create(42L, 7L, 9, OCCURRED_AT, STAT_DATE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("creditedSeconds");
        assertThatThrownBy(() -> ReadingEngagementEvent.create(42L, 7L, 30, OCCURRED_AT, STAT_DATE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("creditedSeconds");
        assertThatThrownBy(() -> ReadingEngagementEvent.create(42L, 7L, 10, null, STAT_DATE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("occurredAt");
        assertThatThrownBy(() -> ReadingEngagementEvent.create(42L, 7L, 10, OCCURRED_AT, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("statDate");
    }

    @Test
    void exposesOnlyPrivacyMinimizedRecordComponents() {
        Set<String> componentNames = java.util.Arrays.stream(ReadingEngagementEvent.class.getRecordComponents())
            .map(component -> component.getName().toLowerCase())
            .collect(Collectors.toSet());

        assertThat(componentNames).containsExactlyInAnyOrder(
            "eventid", "bookid", "chapterid", "creditedseconds", "occurredat", "statdate", "version");
        assertThat(componentNames).doesNotContain(
            "cookie", "session", "sessionhash", "pagevisitid", "ip", "iphmac", "email", "userid");
    }
}
