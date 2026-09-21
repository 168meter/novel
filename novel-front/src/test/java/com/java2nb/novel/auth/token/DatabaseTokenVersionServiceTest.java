package com.java2nb.novel.auth.token;

import com.java2nb.novel.mapper.FrontUserMapper;
import org.junit.jupiter.api.Test;
import java.util.OptionalLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DatabaseTokenVersionServiceTest {
    @Test void acceptsOnlyMatchingVersionAndFailsClosed() {
        FrontUserMapper users = mock(FrontUserMapper.class);
        DatabaseTokenVersionService versions = new DatabaseTokenVersionService(users);
        when(users.selectTokenVersion(7L)).thenReturn(OptionalLong.of(2L));
        assertThat(versions.isCurrent(7L, 2L)).isTrue();
        assertThat(versions.isCurrent(7L, 1L)).isFalse();
        when(users.selectTokenVersion(7L)).thenReturn(OptionalLong.empty());
        assertThat(versions.isCurrent(7L, 0L)).isFalse();
        when(users.selectTokenVersion(7L)).thenThrow(new IllegalStateException("db down"));
        assertThat(versions.isCurrent(7L, 0L)).isFalse();
    }
}
