package com.java2nb.novel.engagement;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadingHeartbeatGateTest {

    private static final ReadingHeartbeatCommand COMMAND = new ReadingHeartbeatCommand(
        42L, 99L, "abc123", 7L,
        "session-hash", "ip-hmac",
        LocalDate.of(2026, 9, 10), 1_789_000_000_000L);

    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry meterRegistry;
    private ReadingHeartbeatGate gate;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        ReadingEngagementProperties properties = new ReadingEngagementProperties();
        properties.setIpHmacSecret("test-secret");
        gate = new ReadingHeartbeatGate(redisTemplate, properties, meterRegistry);
    }

    @ParameterizedTest(name = "Redis result {0} maps to {1}")
    @MethodSource("redisOutcomes")
    void mapsEveryRedisResultCode(long redisResult, ReadingHeartbeatOutcome expectedOutcome) {
        stubRedisResult(redisResult);

        assertThat(gate.evaluate(COMMAND)).isEqualTo(expectedOutcome);
        assertThat(meterRegistry.timer("novel.reading.redis.gate").count()).isEqualTo(1);
    }

    private static Stream<Arguments> redisOutcomes() {
        return Stream.of(
            Arguments.of(1L, ReadingHeartbeatOutcome.ACCEPTED),
            Arguments.of(2L, ReadingHeartbeatOutcome.DUPLICATE),
            Arguments.of(3L, ReadingHeartbeatOutcome.INVALID_PAGE),
            Arguments.of(4L, ReadingHeartbeatOutcome.SESSION_RATE_LIMITED),
            Arguments.of(5L, ReadingHeartbeatOutcome.IP_RATE_LIMITED),
            Arguments.of(6L, ReadingHeartbeatOutcome.DAILY_CAP_REACHED));
    }

    @Test
    void buildsExactKeysAndArgumentsForTheAtomicRedisTransition() {
        stubRedisResult(1L);

        gate.evaluate(COMMAND);

        ArgumentCaptor<DefaultRedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> argumentCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).execute(scriptCaptor.capture(), keysCaptor.capture(),
            argumentCaptor.capture(), argumentCaptor.capture(), argumentCaptor.capture(), argumentCaptor.capture(),
            argumentCaptor.capture(), argumentCaptor.capture(), argumentCaptor.capture(), argumentCaptor.capture(),
            argumentCaptor.capture(), argumentCaptor.capture(), argumentCaptor.capture(), argumentCaptor.capture(),
            argumentCaptor.capture());
        assertThat(scriptCaptor.getValue()).isSameAs(ReadingHeartbeatGate.GATE_SCRIPT);
        assertThat(keysCaptor.getValue()).containsExactly(
            "reading:page:{abc123}",
            "reading:rate:session:session-hash",
            "reading:rate:ip:ip-hmac",
            "reading:credit:2026-09-10:session-hash:42:99");
        assertThat(argumentCaptor.getAllValues()).containsExactly(
            "session-hash", "42", "99", "7", "1789000000000", "1788999940000", "2", "120", "1800",
            "30", "120", "172800", "abc123:7");
    }

    @Test
    void mapsNullUnknownAndThrownRedisResultsToErrorsAndTimesEveryCall() {
        stubRedisResult(null);
        assertThat(gate.evaluate(COMMAND)).isEqualTo(ReadingHeartbeatOutcome.REDIS_ERROR);

        stubRedisResult(999L);
        assertThat(gate.evaluate(COMMAND)).isEqualTo(ReadingHeartbeatOutcome.REDIS_ERROR);

        stubRedisFailure(new RedisConnectionFailureException("Redis is unavailable"));
        assertThat(gate.evaluate(COMMAND)).isEqualTo(ReadingHeartbeatOutcome.REDIS_ERROR);

        assertThat(meterRegistry.timer("novel.reading.redis.gate").count()).isEqualTo(3);
    }

    @Test
    void luaContractKeepsValidationRateLimitsAndCreditMutationAtomic() {
        assertThat(ReadingHeartbeatGate.GATE_SCRIPT.getScriptAsString())
            .contains("HGET", "ZREMRANGEBYSCORE", "ZCARD", "ZADD", "lastSequence",
                "credited + creditSeconds > tonumber(ARGV[9])",
                "redis.call('SET', KEYS[4], credited + creditSeconds, 'EX', tonumber(ARGV[12]))");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubRedisResult(Long result) {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(result);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubRedisFailure(RuntimeException exception) {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(exception);
    }
}
