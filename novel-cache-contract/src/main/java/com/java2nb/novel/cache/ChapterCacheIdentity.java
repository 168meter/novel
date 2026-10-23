package com.java2nb.novel.cache;

import java.util.Objects;

public record ChapterCacheIdentity(Long bookId, Long bookIndexId) {

    public ChapterCacheIdentity {
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(bookIndexId, "bookIndexId");
    }

    public String cacheKey() {
        return ChapterContentCacheKey.of(bookId, bookIndexId);
    }
}
