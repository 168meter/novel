package com.java2nb.novel.config;

import com.java2nb.novel.engagement.ReadingEngagementProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingEngagementConfigTest {

    @Test
    void createsThreePartitionSingleReplicaReadingEngagementTopic() {
        ReadingEngagementProperties properties = new ReadingEngagementProperties();
        ReadingEngagementConfig config = new ReadingEngagementConfig();

        NewTopic topic = config.readingEngagementTopic(properties);

        assertThat(topic.name()).isEqualTo("novel-reading-engagement-v1");
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }
}
