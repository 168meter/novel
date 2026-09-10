package com.java2nb.novel.engagement;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadingPageVisitRegistrarTest {

    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry meterRegistry;
    private ReadingPageVisitRegistrar registrar;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        ReadingEngagementProperties properties = new ReadingEngagementProperties();
        properties.setIpHmacSecret("test-secret");
        properties.setPageTtl(Duration.ofSeconds(7200));
        registrar = new ReadingPageVisitRegistrar(redisTemplate, new ReadingIdentityHasher(properties), properties,
            meterRegistry);
    }

    @Test
    void registersAReadablePageWithTheHashedSessionAndConfiguredTtl() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any())).thenReturn(1L);

        Optional<String> pageVisitId = registrar.register("reader-mark", 1L, 100L);

        assertThat(pageVisitId).hasValueSatisfying(token -> assertThat(token).matches("[0-9a-f]{32}"));
        ArgumentCaptor<DefaultRedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> argumentCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).execute(scriptCaptor.capture(), keysCaptor.capture(), argumentCaptor.capture(),
            argumentCaptor.capture(), argumentCaptor.capture(), argumentCaptor.capture());
        assertThat(keysCaptor.getValue()).containsExactly("reading:page:{" + pageVisitId.orElseThrow() + "}");
        assertThat(argumentCaptor.getAllValues()).containsExactly(
            "17c041e3cfda08cd1b9972637dfe6f2fabb0f674fd7d7c55439635e6925bb13c", "1", "100", "7200");
        assertThat(scriptCaptor.getValue().getScriptAsString()).contains("redis.call('HSET', KEYS[1]");
        assertThat(meterRegistry.counter("novel.reading.page.registration", "result", "success").count()).isEqualTo(1);
    }

    @Test
    void treatsANullRedisResultAsAnErrorWithoutThrowing() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any())).thenReturn(null);

        Optional<String> pageVisitId = registrar.register("reader-mark", 1L, 100L);

        assertThat(pageVisitId).isEmpty();
        assertThat(meterRegistry.counter("novel.reading.page.registration", "result", "error").count()).isEqualTo(1);
    }

    @Test
    void treatsRedisConnectionFailuresAsErrorsWithoutThrowing() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any()))
            .thenThrow(new RedisConnectionFailureException("Redis is unavailable"));

        assertThatCode(() -> registrar.register("reader-mark", 1L, 100L)).doesNotThrowAnyException();

        assertThat(registrar.register("reader-mark", 1L, 100L)).isEmpty();
        assertThat(meterRegistry.counter("novel.reading.page.registration", "result", "error").count()).isEqualTo(2);
    }
}
