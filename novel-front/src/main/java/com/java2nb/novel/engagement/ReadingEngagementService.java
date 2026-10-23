package com.java2nb.novel.engagement;

import com.java2nb.novel.messaging.ReadingEngagementEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class ReadingEngagementService {

    private static final String HEARTBEAT_METRIC = "novel.reading.heartbeat";
    private static final String CREDITED_SECONDS_METRIC = "novel.reading.credited.seconds";

    private final ReadingIdentityHasher identityHasher;
    private final ReadingHeartbeatGate heartbeatGate;
    private final ReadingEngagementEventPublisher publisher;
    private final ReadingEngagementProperties properties;
    private final Clock clock;
    private final EnumMap<ReadingHeartbeatOutcome, Counter> outcomeCounters;
    private final Counter creditedSecondsCounter;
    private final Counter acceptedSecondsCounter;

    public ReadingEngagementService(
        ReadingIdentityHasher identityHasher,
        ReadingHeartbeatGate heartbeatGate,
        ReadingEngagementEventPublisher publisher,
        ReadingEngagementProperties properties,
        MeterRegistry meterRegistry,
        Clock clock
    ) {
        this.identityHasher = identityHasher;
        this.heartbeatGate = heartbeatGate;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
        this.outcomeCounters = registerOutcomeCounters(meterRegistry);
        this.creditedSecondsCounter = Counter.builder(CREDITED_SECONDS_METRIC)
            .description("Compatibility metric: seconds accepted by the Redis gate, before Kafka persistence")
            .register(meterRegistry);
        this.acceptedSecondsCounter = Counter.builder("novel.reading.heartbeat.accepted.seconds")
            .description("Reading seconds accepted by the Redis heartbeat gate")
            .register(meterRegistry);
    }

    public ReadingHeartbeatOutcome handle(
        ReadingHeartbeatRequest request,
        String userMark,
        String clientAddress
    ) {
        if (userMark == null || userMark.isBlank()) {
            return recordOutcome(ReadingHeartbeatOutcome.INVALID_PAGE);
        }

        String sessionHash = identityHasher.sessionHash(userMark);
        String ipHmac = identityHasher.ipHmac(clientAddress);
        Instant now = clock.instant();
        LocalDate statDate = LocalDate.ofInstant(now, properties.zoneId());
        ReadingHeartbeatCommand command = new ReadingHeartbeatCommand(
            request.bookId(),
            request.chapterId(),
            request.pageVisitId(),
            request.sequence(),
            sessionHash,
            ipHmac,
            statDate);

        ReadingHeartbeatOutcome outcome = recordOutcome(heartbeatGate.evaluate(command));
        if (outcome == ReadingHeartbeatOutcome.ACCEPTED) {
            creditedSecondsCounter.increment(properties.creditedSeconds());
            acceptedSecondsCounter.increment(properties.creditedSeconds());
            publisher.publish(
                request.bookId(),
                request.chapterId(),
                properties.creditedSeconds(),
                now,
                statDate);
        }
        return outcome;
    }

    private EnumMap<ReadingHeartbeatOutcome, Counter> registerOutcomeCounters(MeterRegistry meterRegistry) {
        EnumMap<ReadingHeartbeatOutcome, Counter> counters = new EnumMap<>(ReadingHeartbeatOutcome.class);
        for (ReadingHeartbeatOutcome outcome : ReadingHeartbeatOutcome.values()) {
            counters.put(outcome, meterRegistry.counter(
                HEARTBEAT_METRIC,
                "result",
                outcome.name().toLowerCase(Locale.ROOT)));
        }
        return counters;
    }

    private ReadingHeartbeatOutcome recordOutcome(ReadingHeartbeatOutcome outcome) {
        outcomeCounters.get(outcome).increment();
        return outcome;
    }
}
