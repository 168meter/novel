package com.java2nb.novel.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.ZoneOffset;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BookVisitKafkaConfigTest {

    @Test
    void createsVersionedTopicsAndFiniteRetryHandler() {
        BookVisitKafkaProperties properties = new BookVisitKafkaProperties(
            "visit-topic", "visit-dlt", "visit-group", 500);
        BookVisitKafkaConfig config = new BookVisitKafkaConfig(properties);
        @SuppressWarnings("unchecked")
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);

        NewTopic visitTopic = config.visitTopic();
        NewTopic dltTopic = config.visitDltTopic();
        DefaultErrorHandler errorHandler = config.bookVisitErrorHandler(
            kafkaTemplate, new SimpleMeterRegistry());

        assertThat(visitTopic.name()).isEqualTo("visit-topic");
        assertThat(visitTopic.numPartitions()).isEqualTo(3);
        assertThat(visitTopic.replicationFactor()).isEqualTo((short) 1);
        assertThat(dltTopic.name()).isEqualTo("visit-dlt");
        assertThat(dltTopic.numPartitions()).isEqualTo(3);
        assertThat(config.clock().getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(errorHandler).isNotNull();
        assertThat(errorHandler.isAckAfterHandle()).isTrue();
    }
}
