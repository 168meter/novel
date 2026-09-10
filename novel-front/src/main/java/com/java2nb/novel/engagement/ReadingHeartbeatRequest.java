package com.java2nb.novel.engagement;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record ReadingHeartbeatRequest(
    @NotNull @Positive Long bookId,
    @NotNull @Positive Long chapterId,
    @NotBlank @Pattern(regexp = "[0-9a-f]{32}") String pageVisitId,
    @Positive @Max(10000) long sequence
) {
}
