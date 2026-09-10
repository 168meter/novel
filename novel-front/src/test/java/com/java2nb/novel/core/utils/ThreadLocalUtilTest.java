package com.java2nb.novel.core.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadLocalUtilTest {

    @Test
    void returnsTheClientIdSetForTheCurrentThread() {
        ThreadLocalUtil.setClientId("reader-mark");

        assertThat(ThreadLocalUtil.getClientId()).isEqualTo("reader-mark");
    }
}
