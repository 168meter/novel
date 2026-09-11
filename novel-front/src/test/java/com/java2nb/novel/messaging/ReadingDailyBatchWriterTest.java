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
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReadingDailyBatchWriterTest {

    private static final String FIRST_ID = "00000000-0000-0000-0000-000000000001";
    private static final String SECOND_ID = "00000000-0000-0000-0000-000000000002";
    private final ReadingEventFingerprint fingerprint = new ReadingEventFingerprint();
    private ReadingAggregationMapper mapper;
    private ReadingDailyBatchWriter writer;

    @BeforeEach
    void setUp() {
        mapper = mock(ReadingAggregationMapper.class);
        writer = new ReadingDailyBatchWriter(
            mapper,
            new ReadingEventValidator(),
            fingerprint,
            new ReadingDailyBatchAggregator());
    }

    @Test
    void emptyBatchReturnsZerosWithoutTouchingTheDatabase() {
        ReadingBatchWriteResult result = writer.write(List.of());

        assertThat(result).isEqualTo(new ReadingBatchWriteResult(0, 0, 0, 0, 0L));
        verifyNoInteractions(mapper);
    }

    @Test
    void allNewEventsUseOneInsertOneSelectAndOneAggregateUpsert() {
        stubAllInsertedRowsAsOwnedByCurrentBatch();
        ReadingEngagementEvent first = event(FIRST_ID, 42L, "2026-09-10T00:00:00Z");
        ReadingEngagementEvent second = event(SECOND_ID, 42L, "2026-09-10T00:00:30Z");
        ReadingEngagementEvent third = event(
            "00000000-0000-0000-0000-000000000003", 43L, "2026-09-10T00:01:00Z");

        ReadingBatchWriteResult result = writer.write(List.of(first, second, third));

        assertThat(result).isEqualTo(new ReadingBatchWriteResult(3, 3, 0, 2, 90L));
        verify(mapper, times(1)).insertDedupRecords(anyList());
        verify(mapper, times(1)).findDedupStates(anyList());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ReadingDailyAggregate>> aggregates =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(mapper, times(1)).upsertDailyAggregates(aggregates.capture());
        assertThat(aggregates.getValue())
            .extracting(ReadingDailyAggregate::bookId)
            .containsExactly(42L, 43L);
    }

    @Test
    void priorDuplicateIsComparedButNotAggregatedAgain() {
        ReadingEngagementEvent event = event(FIRST_ID, 42L, "2026-09-10T00:00:00Z");
        when(mapper.insertDedupRecords(anyList())).thenReturn(0);
        when(mapper.findDedupStates(List.of(FIRST_ID))).thenReturn(List.of(
            new ReadingDedupState(FIRST_ID, fingerprint.fingerprint(event), "older-batch-token")));

        ReadingBatchWriteResult result = writer.write(List.of(event));

        assertThat(result).isEqualTo(new ReadingBatchWriteResult(1, 0, 1, 0, 0L));
        verify(mapper, times(1)).insertDedupRecords(anyList());
        verify(mapper, times(1)).findDedupStates(List.of(FIRST_ID));
        verify(mapper, never()).upsertDailyAggregates(anyList());
    }

    @Test
    void identicalSameBatchDuplicatesAreRegisteredAndAggregatedOnce() {
        stubAllInsertedRowsAsOwnedByCurrentBatch();
        ReadingEngagementEvent event = event(FIRST_ID, 42L, "2026-09-10T00:00:00Z");

        ReadingBatchWriteResult result = writer.write(List.of(event, event));

        assertThat(result).isEqualTo(new ReadingBatchWriteResult(2, 1, 1, 1, 30L));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ReadingDedupRecord>> records =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(mapper).insertDedupRecords(records.capture());
        assertThat(records.getValue()).hasSize(1);
        verify(mapper).findDedupStates(List.of(FIRST_ID));
        verify(mapper).upsertDailyAggregates(anyList());
    }

    @Test
    void conflictingSameBatchPayloadFailsBeforeDatabaseRegistration() {
        ReadingEngagementEvent first = event(FIRST_ID, 42L, "2026-09-10T00:00:00Z");
        ReadingEngagementEvent conflict = event(FIRST_ID, 43L, "2026-09-10T00:00:00Z");

        assertThatThrownBy(() -> writer.write(List.of(first, conflict)))
            .isInstanceOfSatisfying(ReadingEventConflictException.class,
                failure -> assertThat(failure.eventId()).isEqualTo(FIRST_ID));
        verifyNoInteractions(mapper);
    }

    @Test
    void storedFingerprintConflictFailsWithoutUpdatingDailyRows() {
        ReadingEngagementEvent event = event(FIRST_ID, 42L, "2026-09-10T00:00:00Z");
        byte[] conflictingFingerprint = fingerprint.fingerprint(event);
        conflictingFingerprint[0] ^= 1;
        when(mapper.insertDedupRecords(anyList())).thenReturn(0);
        when(mapper.findDedupStates(List.of(FIRST_ID))).thenReturn(List.of(
            new ReadingDedupState(FIRST_ID, conflictingFingerprint, "older-batch-token")));

        assertThatThrownBy(() -> writer.write(List.of(event)))
            .isInstanceOfSatisfying(ReadingEventConflictException.class,
                failure -> assertThat(failure.eventId()).isEqualTo(FIRST_ID));
        verify(mapper, never()).upsertDailyAggregates(anyList());
    }

    @Test
    void missingMapperStateFailsInsteadOfGuessingBatchOwnership() {
        ReadingEngagementEvent event = event(FIRST_ID, 42L, "2026-09-10T00:00:00Z");
        when(mapper.insertDedupRecords(anyList())).thenReturn(1);
        when(mapper.findDedupStates(List.of(FIRST_ID))).thenReturn(List.of());

        assertThatThrownBy(() -> writer.write(List.of(event)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dedup state");
        verify(mapper, never()).upsertDailyAggregates(anyList());
    }

    @Test
    void writeMethodRollsBackForEveryException() throws Exception {
        Method method = ReadingDailyBatchWriter.class.getMethod("write", List.class);

        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).containsExactly(Exception.class);
    }

    @Test
    void mysqlIntegrationTestIsPinnedToDedicatedLocalDatabase() {
        assertThat(ReadingDailyBatchWriterMySqlIT.TestConfig.jdbcUrl())
            .isEqualTo("jdbc:mysql://127.0.0.1:3307/novel_plus_reading_it"
                + "?createDatabaseIfNotExist=true&useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
    }

    private void stubAllInsertedRowsAsOwnedByCurrentBatch() {
        AtomicReference<List<ReadingDedupRecord>> inserted = new AtomicReference<>();
        when(mapper.insertDedupRecords(anyList())).thenAnswer(invocation -> {
            List<ReadingDedupRecord> records = List.copyOf(invocation.getArgument(0));
            inserted.set(records);
            return records.size();
        });
        when(mapper.findDedupStates(anyList())).thenAnswer(invocation -> inserted.get().stream()
            .map(record -> new ReadingDedupState(
                record.eventId(), record.eventFingerprint(), record.batchToken()))
            .toList());
    }

    private static ReadingEngagementEvent event(String eventId, long bookId, String occurredAt) {
        Instant instant = Instant.parse(occurredAt);
        return new ReadingEngagementEvent(
            eventId,
            bookId,
            7L,
            30,
            instant,
            instant.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate(),
            1);
    }
}
