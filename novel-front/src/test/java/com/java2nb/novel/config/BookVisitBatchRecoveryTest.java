package com.java2nb.novel.config;

import com.java2nb.novel.event.BookVisitEvent;
import com.java2nb.novel.messaging.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.SendResult;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring Kafka 3.3.0 error handler; Kafka Consumer/producer are mocks, not a broker. */
@SuppressWarnings({"unchecked", "rawtypes"})
class BookVisitBatchRecoveryTest {
    final TopicPartition partition = new TopicPartition("visit-topic", 0);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final BookVisitBatchWriter writer = mock(BookVisitBatchWriter.class);
    final BookVisitEventConsumer listener = new BookVisitEventConsumer(new BookVisitBatchAggregator(), writer, registry);
    final KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
    final Consumer kafka = mock(Consumer.class);
    final MessageListenerContainer container = mock(MessageListenerContainer.class);

    DefaultErrorHandler handler() {
        var props = new ContainerProperties("visit-topic");
        props.setAckMode(ContainerProperties.AckMode.BATCH);
        props.setSyncCommitTimeout(Duration.ofSeconds(1));
        when(container.getContainerProperties()).thenReturn(props);
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        return new BookVisitKafkaConfig(new BookVisitKafkaProperties("visit-topic", "visit-dlt", "group", 500))
            .bookVisitErrorHandler(template, registry);
    }

    ConsumerRecords<Long, BookVisitEvent> records(boolean includeA) {
        var b = record(11L, 2L, 0L);
        var c = record(12L, 3L, 1L);
        return new ConsumerRecords<>(Map.of(partition, includeA ? List.of(record(10L, 1L, 1L), b, c) : List.of(b, c)));
    }

    ConsumerRecord<Long, BookVisitEvent> record(long offset, long book, long delta) {
        return new ConsumerRecord<>("visit-topic", 0, offset, book,
            new BookVisitEvent("e-" + offset, book, delta, Instant.now(), 1));
    }

    BatchListenerFailedException invoke(ConsumerRecords<Long, BookVisitEvent> records) {
        return catchThrowableOfType(() -> listener.consume(records.records(partition).stream().map(ConsumerRecord::value).toList()),
            BatchListenerFailedException.class);
    }

    void recover(DefaultErrorHandler handler, BatchListenerFailedException failure, ConsumerRecords<Long, BookVisitEvent> records) {
        assertThat(failure).isNotNull();
        assertThatThrownBy(() -> handler.handleBatch(failure, records, kafka, container,
            () -> {throw new AssertionError("Indexed failure must not use whole-batch in-memory fallback");}))
            .isInstanceOf(RuntimeException.class); // Seek/retry signal; C (or B+C on failed recovery) is redelivered.
    }

    @Test
    void commitsOnlyPersistedPrefixThenRecoversOnlyBadRecordAndRedeliversSuffix() {
        var handler = handler();
        var batch = records(true);
        recover(handler, invoke(batch), batch);
        var order = inOrder(writer, kafka, template);
        order.verify(writer).write(Map.of(1L, 1L));
        order.verify(kafka).commitSync(Map.of(partition, new OffsetAndMetadata(11L)), Duration.ofSeconds(1));
        order.verify(template).send(argThat((ProducerRecord<Object, Object> r) ->
            r.topic().equals("visit-dlt") && ((BookVisitEvent) r.value()).delta() == 0L));
        order.verify(kafka).commitSync(Map.of(partition, new OffsetAndMetadata(12L)), Duration.ofSeconds(1));
        verify(kafka).seek(partition, 12L);
        listener.consume(List.of(record(12L, 3L, 1L).value()));
        verify(writer, times(1)).write(Map.of(1L, 1L));
        verify(writer, times(1)).write(Map.of(3L, 1L));
        verifyNoMoreInteractions(writer);
        assertThat(registry.counter("novel.book.visit.kafka.consumed").count()).isEqualTo(2);
        assertThat(registry.counter("novel.book.visit.kafka.dlt").count()).isEqualTo(1);
    }

    @Test
    void dltFailureKeepsBadOffsetAndRetriesWithoutRewritingCommittedPrefix() {
        var handler = handler();
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new org.springframework.kafka.KafkaException("DLT offline")))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var batch = records(true);
        recover(handler, invoke(batch), batch);
        verify(kafka).commitSync(Map.of(partition, new OffsetAndMetadata(11L)), Duration.ofSeconds(1));
        verify(kafka, never()).commitSync(Map.of(partition, new OffsetAndMetadata(12L)), Duration.ofSeconds(1));
        verify(kafka).seek(partition, 11L);
        assertThat(registry.counter("novel.book.visit.kafka.dlt").count()).isZero();
        var remaining = records(false);
        recover(handler, invoke(remaining), remaining);
        listener.consume(List.of(record(12L, 3L, 1L).value()));
        verify(writer, times(1)).write(Map.of(1L, 1L));
        verify(writer, times(1)).write(Map.of(3L, 1L));
        verifyNoMoreInteractions(writer);
        assertThat(registry.counter("novel.book.visit.kafka.dlt").count()).isEqualTo(1);
    }

    @Test
    void temporaryDatabaseFailureSeeksWholeBatchThenRecoveryStillIsolatesOnlyB() {
        var handler = handler();
        doThrow(new DataAccessResourceFailureException("DB offline")).doNothing().when(writer).write(Map.of(1L, 1L));
        var batch = records(true);
        var failed = invoke(batch);
        assertThat(failed.getIndex()).isZero();
        recover(handler, failed, batch);
        verify(kafka, never()).commitSync(anyMap(), any(Duration.class));
        verify(template, never()).send(any(ProducerRecord.class));
        verify(kafka).seek(partition, 10L);
        recover(handler, invoke(batch), batch);
        listener.consume(List.of(record(12L, 3L, 1L).value()));
        assertThat(registry.counter("novel.book.visit.kafka.consumed").count()).isEqualTo(2);
        assertThat(registry.counter("novel.book.visit.kafka.dlt").count()).isEqualTo(1);
    }
}
