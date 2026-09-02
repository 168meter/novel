package com.java2nb.novel.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class BookVisitKafkaPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class)
        .withPropertyValues(
            "novel.kafka.book-visit.topic=visit-topic",
            "novel.kafka.book-visit.dlt-topic=visit-dlt",
            "novel.kafka.book-visit.group-id=visit-group",
            "novel.kafka.book-visit.max-poll-records=500");

    @Test
    void bindsBookVisitSettings() {
        contextRunner.run(context -> {
            BookVisitKafkaProperties properties = context.getBean(BookVisitKafkaProperties.class);

            assertThat(properties.topic()).isEqualTo("visit-topic");
            assertThat(properties.dltTopic()).isEqualTo("visit-dlt");
            assertThat(properties.groupId()).isEqualTo("visit-group");
            assertThat(properties.maxPollRecords()).isEqualTo(500);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BookVisitKafkaProperties.class)
    static class TestConfig {
    }
}
