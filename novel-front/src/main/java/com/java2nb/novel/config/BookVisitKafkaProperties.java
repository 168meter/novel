package com.java2nb.novel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka settings for asynchronous book visit aggregation.
 */
@ConfigurationProperties(prefix = "novel.kafka.book-visit")
public record BookVisitKafkaProperties(
    String topic,
    String dltTopic,
    String groupId,
    int maxPollRecords
) {
}
