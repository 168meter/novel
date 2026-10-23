package com.java2nb.novel.cache;

import java.util.Objects;

/** Shared contract for the production chapter-content cache key. */
public final class ChapterContentCacheKey {

    private static final String PREFIX = "novel:chapter:v1:";
    private static final String LOCK_PREFIX = "novel:chapter:lock:v1:";

    private ChapterContentCacheKey() {
    }

    public static String of(Long bookId, Long bookIndexId) {
        return PREFIX + Objects.requireNonNull(bookId, "bookId") + ":"
            + Objects.requireNonNull(bookIndexId, "bookIndexId");
    }

    /** The loader token is also its revocable permission to populate this cache entry. */
    public static String lockOf(Long bookId, Long bookIndexId) {
        return LOCK_PREFIX + Objects.requireNonNull(bookId, "bookId") + ":"
            + Objects.requireNonNull(bookIndexId, "bookIndexId");
    }
}
