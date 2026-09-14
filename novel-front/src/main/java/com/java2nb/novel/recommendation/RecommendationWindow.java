package com.java2nb.novel.recommendation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

public record RecommendationWindow(LocalDate weekStart, LocalDate hotStart, LocalDate endExclusive) {
    public static RecommendationWindow from(Clock clock) {
        LocalDate today = clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        return new RecommendationWindow(today.minusDays(6), today.minusDays(14), today.plusDays(1));
    }
}
