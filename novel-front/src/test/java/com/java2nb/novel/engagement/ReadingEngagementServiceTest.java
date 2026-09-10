package com.java2nb.novel.engagement;

import com.java2nb.novel.messaging.ReadingEngagementEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReadingEngagementServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-10T16:30:00Z");
    private static final LocalDate SHANGHAI_STAT_DATE = LocalDate.parse("2026-09-11");
    private static final String PAGE_VISIT_ID = "0123456789abcdef0123456789abcdef";

    private ReadingIdentityHasher identityHasher;
    private ReadingHeartbeatGate heartbeatGate;
    private ReadingEngagementEventPublisher publisher;
    private SimpleMeterRegistry meterRegistry;
    private Clock clock;
    private ReadingEngagementService service;

    @BeforeEach
    void setUp() {
        identityHasher = mock(ReadingIdentityHasher.class);
        heartbeatGate = mock(ReadingHeartbeatGate.class);
        publisher = mock(ReadingEngagementEventPublisher.class);
        meterRegistry = new SimpleMeterRegistry();
        ReadingEngagementProperties properties = new ReadingEngagementProperties();
        properties.setIpHmacSecret("test-secret");
        clock = org.mockito.Mockito.spy(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        service = new ReadingEngagementService(
            identityHasher, heartbeatGate, publisher, properties, meterRegistry, clock);
    }

    @Test
    void acceptedHeartbeatBuildsExactCommandAndPublishesThirtySeconds() {
        ReadingHeartbeatRequest request = new ReadingHeartbeatRequest(42L, 7L, PAGE_VISIT_ID, 3L);
        when(identityHasher.sessionHash("browser-user-mark")).thenReturn("session-hash");
        when(identityHasher.ipHmac("203.0.113.9")).thenReturn("ip-hmac");
        when(heartbeatGate.evaluate(org.mockito.ArgumentMatchers.any())).thenReturn(ReadingHeartbeatOutcome.ACCEPTED);

        ReadingHeartbeatOutcome outcome = service.handle(request, "browser-user-mark", "203.0.113.9");

        assertThat(outcome).isEqualTo(ReadingHeartbeatOutcome.ACCEPTED);
        verify(identityHasher).sessionHash("browser-user-mark");
        verify(identityHasher).ipHmac("203.0.113.9");
        ArgumentCaptor<ReadingHeartbeatCommand> commandCaptor =
            ArgumentCaptor.forClass(ReadingHeartbeatCommand.class);
        verify(heartbeatGate).evaluate(commandCaptor.capture());
        verify(clock, org.mockito.Mockito.times(1)).instant();
        assertThat(commandCaptor.getValue()).isEqualTo(new ReadingHeartbeatCommand(
            42L,
            7L,
            PAGE_VISIT_ID,
            3L,
            "session-hash",
            "ip-hmac",
            SHANGHAI_STAT_DATE,
            1_789_057_800_000L));
        verify(publisher).publish(42L, 7L, 30, FIXED_INSTANT, SHANGHAI_STAT_DATE);
        assertThat(heartbeatCount(ReadingHeartbeatOutcome.ACCEPTED)).isEqualTo(1.0);
        assertThat(meterRegistry.counter("novel.reading.credited.seconds").count()).isEqualTo(30.0);
    }

    @ParameterizedTest
    @EnumSource(value = ReadingHeartbeatOutcome.class, names = "ACCEPTED", mode = EnumSource.Mode.EXCLUDE)
    void rejectedHeartbeatRecordsItsOutcomeWithoutPublishing(ReadingHeartbeatOutcome outcome) {
        ReadingHeartbeatRequest request = new ReadingHeartbeatRequest(42L, 7L, PAGE_VISIT_ID, 3L);
        when(identityHasher.sessionHash("browser-user-mark")).thenReturn("session-hash");
        when(identityHasher.ipHmac("203.0.113.9")).thenReturn("ip-hmac");
        when(heartbeatGate.evaluate(org.mockito.ArgumentMatchers.any())).thenReturn(outcome);

        assertThat(service.handle(request, "browser-user-mark", "203.0.113.9")).isEqualTo(outcome);

        verify(heartbeatGate).evaluate(org.mockito.ArgumentMatchers.any());
        verify(publisher, never()).publish(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
        assertThat(heartbeatCount(outcome)).isEqualTo(1.0);
        assertThat(meterRegistry.counter("novel.reading.credited.seconds").count()).isZero();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingOrBlankBrowserIdentityIsRejectedBeforeHashingOrRedis(String userMark) {
        ReadingHeartbeatRequest request = new ReadingHeartbeatRequest(42L, 7L, PAGE_VISIT_ID, 3L);

        ReadingHeartbeatOutcome outcome = service.handle(request, userMark, "203.0.113.9");

        assertThat(outcome).isEqualTo(ReadingHeartbeatOutcome.INVALID_PAGE);
        verifyNoInteractions(identityHasher, heartbeatGate, publisher);
        verify(clock, never()).instant();
        assertThat(heartbeatCount(ReadingHeartbeatOutcome.INVALID_PAGE)).isEqualTo(1.0);
        assertThat(meterRegistry.counter("novel.reading.credited.seconds").count()).isZero();
    }

    @Test
    void preRegistersOneHeartbeatCounterForEveryFiniteOutcome() {
        Set<String> resultTags = meterRegistry.find("novel.reading.heartbeat").counters().stream()
            .map(counter -> counter.getId().getTag("result"))
            .collect(Collectors.toSet());

        assertThat(resultTags).containsExactlyInAnyOrder(
            "accepted",
            "duplicate",
            "invalid_page",
            "session_rate_limited",
            "ip_rate_limited",
            "daily_cap_reached",
            "redis_error");
        assertThat(meterRegistry.find("novel.reading.heartbeat").counters()).hasSize(7);
    }

    private double heartbeatCount(ReadingHeartbeatOutcome outcome) {
        Counter counter = meterRegistry.get("novel.reading.heartbeat")
            .tag("result", outcome.name().toLowerCase(Locale.ROOT))
            .counter();
        return counter.count();
    }
}
