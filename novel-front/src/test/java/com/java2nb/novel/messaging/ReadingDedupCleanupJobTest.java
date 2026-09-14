package com.java2nb.novel.messaging;

import com.java2nb.novel.config.ReadingEngagementKafkaProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class ReadingDedupCleanupJobTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 8, 28, 12, 0);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T04:00:00Z"), ZoneOffset.UTC);
    private final ReadingEngagementKafkaProperties properties = new ReadingEngagementKafkaProperties();
    private final ReadingDedupCleanupBatch batch = mock(ReadingDedupCleanupBatch.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void repeatsFullBatchesWithOneFixedShanghaiCutoffUntilThePartialBatch() {
        when(batch.deleteBefore(CUTOFF, 5000)).thenReturn(5000, 5000, 27);
        job().cleanup();
        verify(batch, times(3)).deleteBefore(CUTOFF, 5000);
        assertThat(deleted()).isEqualTo(10027);
        assertThat(failures()).isZero();
    }

    @Test
    void stopsAfterTheFirstPartialBatch() {
        when(batch.deleteBefore(CUTOFF, 5000)).thenReturn(4999);
        job().cleanup();
        verify(batch, times(1)).deleteBefore(CUTOFF, 5000);
        assertThat(deleted()).isEqualTo(4999);
    }

    @Test
    void zeroDeletedRowsEndsTheRunWithoutFailure() {
        job().cleanup();
        verify(batch, times(1)).deleteBefore(CUTOFF, 5000);
        assertThat(deleted()).isZero();
        assertThat(failures()).isZero();
    }

    @Test
    void secondBatchFailurePreservesCommittedCountsAndDoesNotEscapeTheScheduledMethod() {
        when(batch.deleteBefore(CUTOFF, 5000)).thenReturn(5000)
            .thenThrow(new IllegalStateException("database offline"));
        assertThatCode(() -> job().cleanup()).doesNotThrowAnyException();
        verify(batch, times(2)).deleteBefore(CUTOFF, 5000);
        assertThat(deleted()).isEqualTo(5000);
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void firstBatchFailureDoesNotRecordAnySuccessfulDeletions() {
        when(batch.deleteBefore(CUTOFF, 5000)).thenThrow(new IllegalStateException("database offline"));
        assertThatCode(() -> job().cleanup()).doesNotThrowAnyException();
        assertThat(deleted()).isZero();
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void usesConfiguredRetentionAndBatchSizeRatherThanHardCodedDefaults() {
        properties.setDedupRetention(Duration.ofDays(2));
        properties.setCleanupBatchSize(3);
        LocalDateTime cutoff = LocalDateTime.of(2026, 9, 9, 12, 0);
        when(batch.deleteBefore(cutoff, 3)).thenReturn(3, 1);
        job().cleanup();
        verify(batch, times(2)).deleteBefore(cutoff, 3);
        assertThat(deleted()).isEqualTo(4);
    }

    @Test
    void registersConfigurableCronInShanghaiZone() throws Exception {
        Scheduled scheduled = ReadingDedupCleanupJob.class.getMethod("cleanup").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${novel.kafka.reading-engagement.cleanup-cron:0 15 3 * * *}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void failureLogContainsOnlyExceptionTypeAndCompletedBatchCount() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReadingDedupCleanupJob.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        when(batch.deleteBefore(CUTOFF, 5000)).thenReturn(5000)
            .thenThrow(new IllegalStateException("private reader_session=secret IP=1.2.3.4"));
        try {
            job().cleanup();
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getFormattedMessage()).contains("IllegalStateException", "completedBatches=1")
                .doesNotContain("reader_session", "secret", "1.2.3.4");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private ReadingDedupCleanupJob job() {
        return new ReadingDedupCleanupJob(clock, properties, batch, registry);
    }
    private double deleted() { return registry.counter("novel.reading.dedup.cleanup.deleted").count(); }
    private double failures() { return registry.counter("novel.reading.dedup.cleanup.failures").count(); }
}
