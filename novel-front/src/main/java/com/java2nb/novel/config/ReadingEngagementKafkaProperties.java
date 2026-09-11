package com.java2nb.novel.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "novel.kafka.reading-engagement")
public class ReadingEngagementKafkaProperties {

    @NotBlank
    private String topic = "novel-reading-engagement-v1";
    @NotBlank
    private String dltTopic = "novel-reading-engagement-dlt";
    @NotBlank
    private String groupId = "novel-reading-engagement-writer-v1";
    @Min(1)
    @Max(500)
    private int maxPollRecords = 500;
    @NotNull
    @DurationMin(nanos = 1)
    private Duration retryInterval = Duration.ofSeconds(5);
    @Positive
    private long maxRetries = 12L;
    @NotNull
    @DurationMin(nanos = 1)
    private Duration dedupRetention = Duration.ofDays(14);
    @Min(1)
    @Max(5000)
    private int cleanupBatchSize = 5000;
    @NotBlank
    private String cleanupCron = "0 15 3 * * *";

    public String topic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String dltTopic() { return dltTopic; }
    public void setDltTopic(String dltTopic) { this.dltTopic = dltTopic; }
    public String groupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public int maxPollRecords() { return maxPollRecords; }
    public void setMaxPollRecords(int maxPollRecords) { this.maxPollRecords = maxPollRecords; }
    public Duration retryInterval() { return retryInterval; }
    public void setRetryInterval(Duration retryInterval) { this.retryInterval = retryInterval; }
    public long maxRetries() { return maxRetries; }
    public void setMaxRetries(long maxRetries) { this.maxRetries = maxRetries; }
    public Duration dedupRetention() { return dedupRetention; }
    public void setDedupRetention(Duration dedupRetention) { this.dedupRetention = dedupRetention; }
    public int cleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int cleanupBatchSize) { this.cleanupBatchSize = cleanupBatchSize; }
    public String cleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
}
