package com.java2nb.novel.config;

import com.java2nb.novel.engagement.ReadingEngagementProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReadingEngagementProperties.class)
public class ReadingEngagementConfig {

    @Bean
    NewTopic readingEngagementTopic(ReadingEngagementProperties properties) {
        return TopicBuilder.name(properties.topic())
            .partitions(3)
            .replicas(1)
            .build();
    }
}
