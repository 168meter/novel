package com.java2nb.novel.messaging;

import com.java2nb.novel.engagement.ReadingBatchWriteResult;
import com.java2nb.novel.engagement.ReadingEventConflictException;
import com.java2nb.novel.engagement.ReadingEventFingerprint;
import com.java2nb.novel.engagement.ReadingEventValidator;
import com.java2nb.novel.event.ReadingEngagementEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.stereotype.Component;

/** Persists each acknowledged prefix through the separate transactional writer bean. */
@Component
public class ReadingEngagementConsumer {

    private final ReadingEventValidator validator;
    private final ReadingEventFingerprint fingerprint;
    private final ReadingDailyBatchWriter writer;
    private final Counter consumed;
    private final Counter deduplicated;
    private final Counter persistedSeconds;
    private final Counter dailyRows;
    private final Counter invalidValidation;
    private final Counter invalidConflict;
    private final Counter invalidDeserialization;
    private final DistributionSummary batchSize;

    public ReadingEngagementConsumer(
        ReadingEventValidator validator,
        ReadingEventFingerprint fingerprint,
        ReadingDailyBatchWriter writer,
        MeterRegistry registry
    ) {
        this.validator = validator;
        this.fingerprint = fingerprint;
        this.writer = writer;
        consumed = registry.counter("novel.reading.kafka.consumed");
        deduplicated = registry.counter("novel.reading.kafka.deduplicated");
        persistedSeconds = registry.counter("novel.reading.kafka.persisted_seconds");
        dailyRows = registry.counter("novel.reading.kafka.daily_rows_updated");
        invalidValidation = registry.counter("novel.reading.kafka.invalid", "reason", "validation");
        invalidConflict = registry.counter("novel.reading.kafka.invalid", "reason", "conflict");
        invalidDeserialization = registry.counter("novel.reading.kafka.invalid", "reason", "deserialization");
        batchSize = registry.summary("novel.reading.kafka.batch_size");
    }

    @KafkaListener(
        topics = "${novel.kafka.reading-engagement.topic:novel-reading-engagement-v1}",
        groupId = "${novel.kafka.reading-engagement.group-id:novel-reading-engagement-writer-v1}",
        containerFactory = "readingEngagementKafkaListenerContainerFactory"
    )
    public void consumeRecords(List<ConsumerRecord<Long, ReadingEngagementEvent>> records) {
        Objects.requireNonNull(records, "records must not be null");
        List<ReadingEngagementEvent> events = records.stream().map(ConsumerRecord::value).toList();
        for (int index = 0; index < records.size(); index++) {
            ConsumerRecord<Long, ReadingEngagementEvent> record = records.get(index);
            if (record.headers().lastHeader(SerializationUtils.KEY_DESERIALIZER_EXCEPTION_HEADER) != null
                || record.headers().lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER) != null) {
                failAfterPersistingPrefix(events, index,
                    new IllegalArgumentException("Reading record deserialization failed"), invalidDeserialization);
            }
        }
        consume(events);
    }

    void consume(List<ReadingEngagementEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        process(events);
    }

    private void process(List<ReadingEngagementEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        for (int index = 0; index < events.size(); index++) {
            try {
                validator.validate(events.get(index));
            } catch (IllegalArgumentException invalid) {
                failAfterPersistingPrefix(events, index, invalid, invalidValidation);
            }
        }

        ReadingBatchWriteResult result;
        try {
            // A successful proxy return means the database transaction has committed.
            result = writer.write(events);
        } catch (ReadingEventConflictException conflict) {
            int index = conflictIndex(events, conflict.eventId());
            failAfterPersistingPrefix(events, index, conflict, invalidConflict);
            return;
        }
        consumed.increment(result.receivedEvents());
        deduplicated.increment(result.deduplicatedEvents());
        persistedSeconds.increment(result.persistedSeconds());
        dailyRows.increment(result.dailyRows());
        batchSize.record(result.receivedEvents());
    }

    private void failAfterPersistingPrefix(
        List<ReadingEngagementEvent> events, int index, IllegalArgumentException cause,
        Counter invalidCounter
    ) {
        // Spring Kafka commits offsets before this index. Never report it before persistence.
        // Prefix failures take precedence; database failures escape without a failed index.
        process(events.subList(0, index));
        invalidCounter.increment();
        throw new BatchListenerFailedException("Invalid reading event", cause, index);
    }

    private int conflictIndex(List<ReadingEngagementEvent> events, String eventId) {
        int firstIndex = -1;
        byte[] firstFingerprint = null;
        for (int index = 0; index < events.size(); index++) {
            ReadingEngagementEvent event = events.get(index);
            if (event.eventId().equals(eventId)) {
                byte[] currentFingerprint = fingerprint.fingerprint(event);
                if (firstIndex < 0) {
                    firstIndex = index;
                    firstFingerprint = currentFingerprint;
                } else if (!MessageDigest.isEqual(firstFingerprint, currentFingerprint)) {
                    return index;
                }
            }
        }
        if (firstIndex < 0) {
            throw new IllegalStateException("Writer conflict ID is absent from reading batch");
        }
        return firstIndex;
    }
}
