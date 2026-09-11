package com.java2nb.novel.engagement;

import com.java2nb.novel.event.ReadingEngagementEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingEventFingerprintTest {

    private static final ReadingEngagementEvent BASE = new ReadingEngagementEvent(
        "123e4567-e89b-12d3-a456-426614174000",
        42L,
        7L,
        30,
        Instant.parse("2026-09-10T00:00:00Z"),
        LocalDate.of(2026, 9, 10),
        1);
    private final ReadingEventFingerprint fingerprint = new ReadingEventFingerprint();

    @Test
    void producesStableSha256FromTheCanonicalFieldOrder() {
        byte[] first = fingerprint.fingerprint(BASE);
        byte[] second = fingerprint.fingerprint(BASE);

        assertThat(first).hasSize(32).containsExactly(second);
        assertThat(HexFormat.of().formatHex(first))
            .isEqualTo("f4490a2e34966cda829193fb7518313635d7212ff44c22c5a4210cd55d279ae2");
    }

    @Test
    void everyBusinessFieldParticipatesInTheFingerprint() {
        String baseHex = hex(BASE);
        List<ReadingEngagementEvent> mutations = List.of(
            copy("123e4567-e89b-12d3-a456-426614174001", 42L, 7L, 30,
                BASE.occurredAt(), BASE.statDate(), 1),
            copy(BASE.eventId(), 43L, 7L, 30, BASE.occurredAt(), BASE.statDate(), 1),
            copy(BASE.eventId(), 42L, 8L, 30, BASE.occurredAt(), BASE.statDate(), 1),
            copy(BASE.eventId(), 42L, 7L, 31, BASE.occurredAt(), BASE.statDate(), 1),
            copy(BASE.eventId(), 42L, 7L, 30, BASE.occurredAt().plusMillis(1),
                BASE.statDate(), 1),
            copy(BASE.eventId(), 42L, 7L, 30, BASE.occurredAt(),
                BASE.statDate().plusDays(1), 1),
            copy(BASE.eventId(), 42L, 7L, 30, BASE.occurredAt(), BASE.statDate(), 2));

        assertThat(mutations).allSatisfy(event -> assertThat(hex(event)).isNotEqualTo(baseHex));
    }

    private String hex(ReadingEngagementEvent event) {
        return HexFormat.of().formatHex(fingerprint.fingerprint(event));
    }

    private static ReadingEngagementEvent copy(
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
