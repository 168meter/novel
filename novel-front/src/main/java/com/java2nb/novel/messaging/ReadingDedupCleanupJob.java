package com.java2nb.novel.messaging;

import com.java2nb.novel.config.ReadingEngagementKafkaProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single-instance cleanup; multi-instance deployments need a distributed scheduling lock. */
@Component
public class ReadingDedupCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(ReadingDedupCleanupJob.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final Clock clock;
    private final ReadingEngagementKafkaProperties properties;
    private final ReadingDedupCleanupBatch batch;
    private final Counter deleted;
    private final Counter failures;

    public ReadingDedupCleanupJob(
        Clock clock, ReadingEngagementKafkaProperties properties,
        ReadingDedupCleanupBatch batch, MeterRegistry registry
    ) {
        this.clock = clock;
        this.properties = properties;
        this.batch = batch;
        deleted = registry.counter("novel.reading.dedup.cleanup.deleted");
        failures = registry.counter("novel.reading.dedup.cleanup.failures");
    }

    @Scheduled(cron = "${novel.kafka.reading-engagement.cleanup-cron:0 15 3 * * *}", zone = "Asia/Shanghai")
    public void cleanup() {
        long completedBatches = 0;
        try {
            LocalDateTime cutoff = LocalDateTime.ofInstant(
                clock.instant().minus(properties.dedupRetention()), SHANGHAI);
            int limit = properties.cleanupBatchSize();
            int removed;
            do {
                // The injected batch bean commits before this return; no self-invocation.
                removed = batch.deleteBefore(cutoff, limit);
                deleted.increment(removed);
                completedBatches = Math.incrementExact(completedBatches);
            } while (removed == limit);
        } catch (RuntimeException failure) {
            failures.increment();
            LOG.warn("Reading dedup cleanup failed: exceptionType={}, completedBatches={}",
                failure.getClass().getSimpleName(), completedBatches);
        }
    }
}
