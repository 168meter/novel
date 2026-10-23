package com.java2nb.novel.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChapterContentCacheKeyTest {

    @Test
    void lockIdentityMatchesTheExistingLoaderKeyFormat() {
        assertThat(ChapterContentCacheKey.lockOf(101L,202L)).isEqualTo("novel:chapter:lock:v1:101:202");
    }

    @Test
    void formatsTheExistingProductionKeyWithoutChangingItsIds() {
        assertThat(ChapterContentCacheKey.of(9_007_199_254_740_993L, 9_007_199_254_740_994L))
            .isEqualTo("novel:chapter:v1:9007199254740993:9007199254740994");
    }
}
