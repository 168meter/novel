package com.java2nb.novel.messaging;

import com.java2nb.novel.engagement.ReadingBatchWriteResult;
import com.java2nb.novel.engagement.ReadingEventConflictException;
import com.java2nb.novel.engagement.ReadingEventFingerprint;
import com.java2nb.novel.engagement.ReadingEventValidator;
import com.java2nb.novel.event.ReadingEngagementEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.serializer.SerializationUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ReadingEngagementConsumerTest {

    private final ReadingDailyBatchWriter writer = mock(ReadingDailyBatchWriter.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReadingEngagementConsumer consumer = new ReadingEngagementConsumer(
        new ReadingEventValidator(), new ReadingEventFingerprint(), writer, registry);

    @Test
    void registersOnlyTheDedicatedReadingListener() throws Exception {
        KafkaListener listener = ReadingEngagementConsumer.class
            .getMethod("consumeRecords", List.class).getAnnotation(KafkaListener.class);
        assertThat(listener.containerFactory())
            .isEqualTo("readingEngagementKafkaListenerContainerFactory");
        assertThat(listener.topics()).containsExactly(
            "${novel.kafka.reading-engagement.topic:novel-reading-engagement-v1}");
        assertThat(listener.groupId()).isEqualTo(
            "${novel.kafka.reading-engagement.group-id:novel-reading-engagement-writer-v1}");
    }

    @Test
    void recordsOnlyCommittedWriterResultsIncludingReplay() {
        List<ReadingEngagementEvent> batch = List.of(event(1, 42), event(1, 42), event(2, 43));
        when(writer.write(batch)).thenReturn(new ReadingBatchWriteResult(3, 2, 1, 2, 20));
        consumer.consume(batch);
        verify(writer, times(1)).write(batch);
        assertThat(count("consumed")).isEqualTo(3);
        assertThat(count("deduplicated")).isEqualTo(1);
        assertThat(count("persisted_seconds")).isEqualTo(20);
        assertThat(count("daily_rows_updated")).isEqualTo(2);
        assertThat(registry.get("novel.reading.kafka.batch_size").summary().totalAmount())
            .isEqualTo(3);
    }

    @Test
    void invalidMiddleRecordCommitsPrefixBeforeReportingItsIndex() {
        ReadingEngagementEvent valid = event(1, 42);
        List<ReadingEngagementEvent> prefix = List.of(valid);
        when(writer.write(prefix)).thenReturn(new ReadingBatchWriteResult(1, 1, 0, 1, 10));
        assertIndexedFailure(List.of(valid, event(2, 0), event(3, 43)), 1);
        verify(writer).write(prefix);
        verifyNoMoreInteractions(writer);
        assertThat(count("persisted_seconds")).isEqualTo(10);
        assertThat(registry.counter("novel.reading.kafka.invalid", "reason", "validation")
            .count()).isEqualTo(1);
    }

    @Test
    void deserializationNullAtStartDoesNotWriteAnything() {
        assertIndexedFailure(Arrays.asList(null, event(1, 42)), 0);
        verifyNoInteractions(writer);
        assertThat(count("consumed")).isZero();
    }

    @Test
    void historicalConflictRollsBackFullBatchThenCommitsValidPrefix() {
        List<ReadingEngagementEvent> batch = List.of(event(1, 42), event(2, 43), event(3, 44));
        when(writer.write(batch)).thenThrow(new ReadingEventConflictException(event(2, 43).eventId()));
        when(writer.write(batch.subList(0, 1)))
            .thenReturn(new ReadingBatchWriteResult(1, 1, 0, 1, 10));
        assertIndexedFailure(batch, 1);
        var order = inOrder(writer);
        order.verify(writer).write(batch);
        order.verify(writer).write(batch.subList(0, 1));
        assertThat(count("consumed")).isEqualTo(1);
        assertThat(count("persisted_seconds")).isEqualTo(10);
    }

    @Test
    void sameBatchConflictPointsToDifferingOccurrenceNotFirstValidOne() {
        List<ReadingEngagementEvent> batch = List.of(event(1, 42), event(2, 43), event(1, 44));
        when(writer.write(batch)).thenThrow(new ReadingEventConflictException(event(1, 42).eventId()));
        when(writer.write(batch.subList(0, 2)))
            .thenReturn(new ReadingBatchWriteResult(2, 2, 0, 2, 20));
        assertIndexedFailure(batch, 2);
        verify(writer).write(batch.subList(0, 2));
        assertThat(count("persisted_seconds")).isEqualTo(20);
    }

    @Test
    void earlierConflictInPrefixTakesPrecedenceOverLaterConflict() {
        List<ReadingEngagementEvent> batch = List.of(
            event(1, 42), event(2, 43), event(1, 44));
        when(writer.write(batch)).thenThrow(new ReadingEventConflictException(event(1, 42).eventId()));
        when(writer.write(batch.subList(0, 2)))
            .thenThrow(new ReadingEventConflictException(event(2, 43).eventId()));
        when(writer.write(batch.subList(0, 1)))
            .thenReturn(new ReadingBatchWriteResult(1, 1, 0, 1, 10));
        assertIndexedFailure(batch, 1);
        assertThat(count("persisted_seconds")).isEqualTo(10);
    }

    @Test
    void prefixDatabaseFailureDoesNotPermitAcknowledgingItsOffsets() {
        List<ReadingEngagementEvent> batch = List.of(event(1, 42), event(2, 0));
        IllegalStateException failure = new IllegalStateException("database offline");
        when(writer.write(batch.subList(0, 1))).thenThrow(failure);
        assertThatThrownBy(() -> consumer.consume(batch)).isSameAs(failure);
        assertThat(count("persisted_seconds")).isZero();
        assertThat(count("consumed")).isZero();
        assertThat(registry.get("novel.reading.kafka.batch_size").summary().count()).isZero();
    }

    @Test
    void transientFullBatchFailureIsRethrownWithoutSuccessfulMetrics() {
        List<ReadingEngagementEvent> batch = List.of(event(1, 42));
        IllegalStateException failure = new IllegalStateException("commit failed");
        when(writer.write(batch)).thenThrow(failure);
        assertThatThrownBy(() -> consumer.consume(batch)).isSameAs(failure);
        assertThat(count("persisted_seconds")).isZero();
        assertThat(count("consumed")).isZero();
    }

    @Test
    void unknownConflictIdCannotCauseFalseIndexedAcknowledgment() {
        List<ReadingEngagementEvent> batch = List.of(event(1, 42));
        when(writer.write(batch)).thenThrow(new ReadingEventConflictException(event(99, 42).eventId()));
        assertThatThrownBy(() -> consumer.consume(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(count("consumed")).isZero();
    }

    @Test
    void emptyBatchHasNoWriterOrSuccessfulMetricSideEffects() {
        consumer.consume(List.of());
        verifyNoInteractions(writer);
        assertThat(registry.get("novel.reading.kafka.batch_size").summary().count()).isZero();
    }

    @Test
    void badKeyWithValidValueIsRejectedOnlyAfterItsValidPrefixCommits() {
        ConsumerRecord<Long, ReadingEngagementEvent> first = record(0, event(1, 42));
        ConsumerRecord<Long, ReadingEngagementEvent> failed = record(1, event(2, 43));
        failed.headers().add(SerializationUtils.KEY_DESERIALIZER_EXCEPTION_HEADER, new byte[] {1});
        when(writer.write(List.of(first.value())))
            .thenReturn(new ReadingBatchWriteResult(1, 1, 0, 1, 10));
        assertThatThrownBy(() -> consumer.consumeRecords(List.of(first, failed, record(2, event(3, 44)))))
            .isInstanceOfSatisfying(BatchListenerFailedException.class,
                failure -> assertThat(failure.getIndex()).isEqualTo(1));
        verify(writer).write(List.of(first.value()));
        verifyNoMoreInteractions(writer);
        assertThat(count("persisted_seconds")).isEqualTo(10);
        assertThat(registry.counter("novel.reading.kafka.invalid", "reason", "deserialization")
            .count()).isEqualTo(1);
    }

    @Test
    void earlierInvalidValueTakesPrecedenceOverLaterDeserializationError() {
        ConsumerRecord<Long, ReadingEngagementEvent> failed = record(1, event(2, 43));
        failed.headers().add(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, new byte[] {1});
        assertThatThrownBy(() -> consumer.consumeRecords(List.of(record(0, event(1, 0)), failed)))
            .isInstanceOfSatisfying(BatchListenerFailedException.class,
                failure -> assertThat(failure.getIndex()).isZero());
        verifyNoInteractions(writer);
    }

    private static ConsumerRecord<Long, ReadingEngagementEvent> record(
        long offset, ReadingEngagementEvent value
    ) {
        return new ConsumerRecord<>("reading-topic", 0, offset, null, value);
    }

    private void assertIndexedFailure(List<ReadingEngagementEvent> batch, int index) {
        assertThatThrownBy(() -> consumer.consume(batch))
            .isInstanceOfSatisfying(BatchListenerFailedException.class,
                failure -> assertThat(failure.getIndex()).isEqualTo(index));
    }

    private double count(String suffix) {
        return registry.counter("novel.reading.kafka." + suffix).count();
    }

    static ReadingEngagementEvent event(int id, long book) {
        return new ReadingEngagementEvent(
            "92000000-0000-0000-0000-%012d".formatted(id), book, 7L, 10,
            Instant.parse("2026-09-14T04:00:00Z"), LocalDate.of(2026, 9, 14), 1);
    }
}
