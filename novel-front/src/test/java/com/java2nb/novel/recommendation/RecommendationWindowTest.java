package com.java2nb.novel.recommendation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RecommendationWindowTest {
    @Test
    void includesShanghaiTodayRatherThanUtcToday() {
        var window = RecommendationWindow.from(Clock.fixed(
            Instant.parse("2026-09-14T16:00:00Z"), ZoneOffset.UTC));
        assertThat(window.weekStart()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(window.hotStart()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(window.endExclusive()).isEqualTo(LocalDate.of(2026, 9, 16));
    }

    @Test
    void handlesMonthBoundaryBeforeShanghaiMidnight() {
        var window = RecommendationWindow.from(Clock.fixed(
            Instant.parse("2026-09-30T15:59:59Z"), ZoneOffset.UTC));
        assertThat(window.weekStart()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(window.hotStart()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(window.endExclusive()).isEqualTo(LocalDate.of(2026, 10, 1));
    }
}
