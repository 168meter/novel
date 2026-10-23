package com.java2nb.novel.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChapterCacheIdentityTest {

    @Test
    void delegatesToTheSharedProductionKeyContract() {
        ChapterCacheIdentity identity = new ChapterCacheIdentity(101L, 202L);

        assertThat(identity.cacheKey()).isEqualTo("novel:chapter:v1:101:202");
    }
}
