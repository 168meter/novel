package com.java2nb.novel.config;

import com.java2nb.novel.engagement.ReadingEngagementProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingEngagementConfigTest {

    @Test
    void registersGeneralEngagementPropertiesWithoutOwningKafkaTopics() {
        new ApplicationContextRunner()
            .withUserConfiguration(ReadingEngagementConfig.class)
            .withPropertyValues("novel.reading-engagement.ip-hmac-secret=test-secret")
            .run(context -> {
                assertThat(context).hasSingleBean(ReadingEngagementProperties.class);
                assertThat(context).doesNotHaveBean(org.apache.kafka.clients.admin.NewTopic.class);
            });
    }
}
