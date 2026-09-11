package com.java2nb.novel.messaging;

import com.java2nb.novel.engagement.ReadingBatchWriteResult;
import com.java2nb.novel.engagement.ReadingDailyAggregate;
import com.java2nb.novel.engagement.ReadingDailyBatchAggregator;
import com.java2nb.novel.engagement.ReadingDedupRecord;
import com.java2nb.novel.engagement.ReadingDedupState;
import com.java2nb.novel.engagement.ReadingEventConflictException;
import com.java2nb.novel.engagement.ReadingEventFingerprint;
import com.java2nb.novel.engagement.ReadingEventValidator;
import com.java2nb.novel.event.ReadingEngagementEvent;
import com.java2nb.novel.mapper.ReadingAggregationMapper;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReadingDailyBatchWriter {

    private final ReadingAggregationMapper mapper;
    private final ReadingEventValidator validator;
    private final ReadingEventFingerprint fingerprint;
    private final ReadingDailyBatchAggregator aggregator;

    public ReadingDailyBatchWriter(
        ReadingAggregationMapper mapper,
        ReadingEventValidator validator,
        ReadingEventFingerprint fingerprint,
        ReadingDailyBatchAggregator aggregator
    ) {
        this.mapper = mapper;
        this.validator = validator;
        this.fingerprint = fingerprint;
        this.aggregator = aggregator;
    }

    @Transactional(rollbackFor = Exception.class)
    public ReadingBatchWriteResult write(List<ReadingEngagementEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        if (events.isEmpty()) {
            return new ReadingBatchWriteResult(0, 0, 0, 0, 0L);
        }

        LinkedHashMap<String, PreparedEvent> uniqueEvents = prepareUniqueEvents(events);
        String batchToken = UUID.randomUUID().toString();
        List<ReadingDedupRecord> records = uniqueEvents.values().stream()
            .map(prepared -> new ReadingDedupRecord(
                prepared.event().eventId(),
                prepared.fingerprint(),
                batchToken,
                prepared.event().statDate()))
            .toList();
        mapper.insertDedupRecords(records);

        List<String> eventIds = List.copyOf(uniqueEvents.keySet());
        Map<String, ReadingDedupState> states = indexStates(
            mapper.findDedupStates(eventIds), uniqueEvents);
        List<ReadingEngagementEvent> newEvents = new ArrayList<>();
        for (Map.Entry<String, PreparedEvent> entry : uniqueEvents.entrySet()) {
            ReadingDedupState state = states.get(entry.getKey());
            if (state == null) {
                throw new IllegalStateException("Missing dedup state after registration");
            }
            if (!MessageDigest.isEqual(
                entry.getValue().fingerprint(), state.eventFingerprint())) {
                throw new ReadingEventConflictException(entry.getKey());
            }
            if (batchToken.equals(state.batchToken())) {
                newEvents.add(entry.getValue().event());
            }
        }

        List<ReadingDailyAggregate> aggregates = aggregator.aggregate(newEvents);
        if (!aggregates.isEmpty()) {
            mapper.upsertDailyAggregates(aggregates);
        }
        long persistedSeconds = aggregates.stream()
            .mapToLong(ReadingDailyAggregate::creditedSeconds)
            .reduce(0L, Math::addExact);
        int received = events.size();
        int added = newEvents.size();
        return new ReadingBatchWriteResult(
            received,
            added,
            Math.subtractExact(received, added),
            aggregates.size(),
            persistedSeconds);
    }

    private LinkedHashMap<String, PreparedEvent> prepareUniqueEvents(
        List<ReadingEngagementEvent> events
    ) {
        LinkedHashMap<String, PreparedEvent> uniqueEvents = new LinkedHashMap<>();
        for (ReadingEngagementEvent candidate : events) {
            ReadingEngagementEvent event = validator.validate(candidate);
            byte[] eventFingerprint = fingerprint.fingerprint(event);
            PreparedEvent existing = uniqueEvents.get(event.eventId());
            if (existing != null) {
                if (!MessageDigest.isEqual(existing.fingerprint(), eventFingerprint)) {
                    throw new ReadingEventConflictException(event.eventId());
                }
                continue;
            }
            uniqueEvents.put(event.eventId(), new PreparedEvent(event, eventFingerprint));
        }
        return uniqueEvents;
    }

    private Map<String, ReadingDedupState> indexStates(
        List<ReadingDedupState> returnedStates,
        Map<String, PreparedEvent> expectedEvents
    ) {
        if (returnedStates == null) {
            throw new IllegalStateException("Missing dedup states after registration");
        }
        Map<String, ReadingDedupState> states = new LinkedHashMap<>();
        for (ReadingDedupState state : returnedStates) {
            if (state == null || !expectedEvents.containsKey(state.eventId())) {
                throw new IllegalStateException("Unexpected dedup state after registration");
            }
            if (state.eventFingerprint() == null || state.batchToken() == null
                || states.putIfAbsent(state.eventId(), state) != null) {
                throw new IllegalStateException("Invalid dedup state after registration");
            }
        }
        return states;
    }

    private record PreparedEvent(ReadingEngagementEvent event, byte[] fingerprint) {
    }
}
