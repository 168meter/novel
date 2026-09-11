package com.java2nb.novel.config;

import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingEngagementKafkaPropertiesTest {

    @Test
    void providesBoundedOperationalDefaults() {
        ReadingEngagementKafkaProperties properties = new ReadingEngagementKafkaProperties();

        assertThat(properties.topic()).isEqualTo("novel-reading-engagement-v1");
        assertThat(properties.dltTopic()).isEqualTo("novel-reading-engagement-dlt");
        assertThat(properties.groupId()).isEqualTo("novel-reading-engagement-writer-v1");
        assertThat(properties.maxPollRecords()).isEqualTo(500);
        assertThat(properties.retryInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.maxRetries()).isEqualTo(12L);
        assertThat(properties.dedupRetention()).isEqualTo(Duration.ofDays(14));
        assertThat(properties.cleanupBatchSize()).isEqualTo(5000);
        assertThat(properties.cleanupCron()).isEqualTo("0 15 3 * * *");
    }

    @Test
    void rejectsNonPositiveOperationalDurations() {
        ReadingEngagementKafkaProperties properties = new ReadingEngagementKafkaProperties();
        properties.setRetryInterval(Duration.ZERO);
        properties.setDedupRetention(Duration.ofSeconds(-1));

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("retryInterval", "dedupRetention");
        }
    }

    @Test
    void acceptsAnyStrictlyPositiveOperationalDuration() {
        ReadingEngagementKafkaProperties properties = new ReadingEngagementKafkaProperties();
        properties.setRetryInterval(Duration.ofNanos(1));
        properties.setDedupRetention(Duration.ofHours(12));

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }

    @Test
    void rejectsUnsafeBatchAndRetryBounds() {
        ReadingEngagementKafkaProperties properties = new ReadingEngagementKafkaProperties();
        properties.setMaxPollRecords(501);
        properties.setMaxRetries(0);
        properties.setCleanupBatchSize(5001);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("maxPollRecords", "maxRetries", "cleanupBatchSize");
        }
    }
}
