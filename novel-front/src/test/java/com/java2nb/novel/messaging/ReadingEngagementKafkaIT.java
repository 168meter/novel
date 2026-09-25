package com.java2nb.novel.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.config.BookVisitKafkaConfig;
import com.java2nb.novel.config.ReadingEngagementKafkaConfig;
import com.java2nb.novel.engagement.ReadingDailyBatchAggregator;
import com.java2nb.novel.engagement.ReadingEventFingerprint;
import com.java2nb.novel.engagement.ReadingEventValidator;
import com.java2nb.novel.event.BookVisitEvent;
import com.java2nb.novel.event.ReadingEngagementEvent;
import com.java2nb.novel.mapper.ReadingAggregationMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ReadingEngagementKafkaIT.TestConfig.class)
@EmbeddedKafka(partitions = 3, topics = {"reading-it", "reading-it-dlt", "visit-it", "visit-it-dlt"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers",
    brokerProperties = "group.initial.rebalance.delay.ms=0")
@TestPropertySource(properties = {
    "novel.kafka.reading-engagement.topic=reading-it",
    "novel.kafka.reading-engagement.dlt-topic=reading-it-dlt",
    "novel.kafka.reading-engagement.group-id=reading-it-group",
    "novel.kafka.book-visit.topic=visit-it",
    "novel.kafka.book-visit.dlt-topic=visit-it-dlt",
    "novel.kafka.book-visit.group-id=visit-it-group",
    "novel.kafka.book-visit.max-poll-records=500",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "spring.kafka.consumer.enable-auto-commit=false"
})
@DirtiesContext
class ReadingEngagementKafkaIT {

    private static final AtomicReference<List<Long>> FIRST_POLL = new AtomicReference<>();
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private KafkaListenerEndpointRegistry endpoints;
    @Autowired private ReadingDailyBatchWriter readingWriter;
    @Autowired private BookVisitBatchWriter visitWriter;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry registry;

    @Test
    void commitsValidPrefixesAndSuffixesWhileKeepingReadingAndVisitDltsIsolated() throws Exception {
        assertThat(AopUtils.isAopProxy(readingWriter)).isTrue();
        FIRST_POLL.set(null);
        ObjectMapper json = JacksonUtils.enhancedObjectMapper();
        // Seed a historical fingerprint outside the consumer. Later changed payload must be rejected.
        readingWriter.write(List.of(event(8, 107)));
        List<String> payloads = List.of(
            json.writeValueAsString(event(1, 101)), "{malformed",
            json.writeValueAsString(event(2, 101)), json.writeValueAsString(event(3, 102)),
            json.writeValueAsString(event(20, 0)), json.writeValueAsString(event(4, 102)),
            json.writeValueAsString(event(5, 103)), json.writeValueAsString(event(5, 104)),
            json.writeValueAsString(event(6, 103)), json.writeValueAsString(event(7, 105)),
            json.writeValueAsString(event(8, 106)), json.writeValueAsString(event(9, 105)));

        Map<String, Object> producerProperties = Map.of(
            "bootstrap.servers", broker.getBrokersAsString());
        DefaultKafkaProducerFactory<byte[], String> producerFactory =
            new DefaultKafkaProducerFactory<>(producerProperties,
                new ByteArraySerializer(), new StringSerializer());
        byte[] invalidKey = new byte[] {1, 2, 3};
        String invalidKeyValue = json.writeValueAsString(event(10, 108));
        try (Consumer<byte[], byte[]> dlts = dltConsumer();
             Admin admin = Admin.create(producerProperties)) {
            KafkaTemplate<byte[], String> producer = new KafkaTemplate<>(producerFactory);
            // Queue everything before listener startup: index 1 must have an uncommitted prefix.
            for (String payload : payloads) {
                producer.send("reading-it", 0, longKey(1L), payload).get(10, TimeUnit.SECONDS);
            }
            producer.send("reading-it", 0, invalidKey, invalidKeyValue).get(10, TimeUnit.SECONDS);
            producer.send("reading-it", 0, longKey(1L), json.writeValueAsString(event(11, 109)))
                .get(10, TimeUnit.SECONDS);
            BookVisitEvent visit = new BookVisitEvent(
                "visit-isolation", 9001L, 1L, Instant.parse("2026-09-14T04:00:00Z"), 1);
            producer.send("visit-it", 0, longKey(9001L), json.writeValueAsString(visit))
                .get(10, TimeUnit.SECONDS);

            endpoints.getListenerContainers().forEach(MessageListenerContainer::start);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                for (long book : List.of(101L, 102L, 103L, 105L)) {
                    assertThat(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(credited_seconds),0) FROM book_reading_daily WHERE book_id = ?",
                        Long.class, book)).isEqualTo(20L);
                }
                assertThat(registry.counter("novel.book.visit.kafka.consumed").count())
                    .isEqualTo(1);
            });
            assertThat(FIRST_POLL.get()).containsExactly(0L, 1L, 2L, 3L, 4L, 5L,
                6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L);

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var readingOffsets = admin.listConsumerGroupOffsets("reading-it-group")
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertThat(readingOffsets.get(new TopicPartition("reading-it", 0)).offset())
                    .isEqualTo(14L);
                var visitOffsets = admin.listConsumerGroupOffsets("visit-it-group")
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertThat(visitOffsets.get(new TopicPartition("visit-it", 0)).offset())
                    .isEqualTo(1L);
            });
            endpoints.getListenerContainers().forEach(MessageListenerContainer::stop);

            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM book_reading_daily WHERE book_id=108", Integer.class))
                .isZero();

            List<org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]>> recovered =
                new ArrayList<>();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                dlts.poll(Duration.ofMillis(100)).forEach(recovered::add);
                assertThat(recovered).hasSize(5);
            });
            assertThat(recovered).extracting(record -> record.topic())
                .containsOnly("reading-it-dlt");
            assertThat(recovered).extracting(record ->
                new String(record.value(), StandardCharsets.UTF_8))
                .containsExactlyInAnyOrder(payloads.get(1), payloads.get(4),
                    payloads.get(7), payloads.get(10), invalidKeyValue);
            assertThat(recovered.stream().filter(record ->
                new String(record.value(), StandardCharsets.UTF_8).equals(invalidKeyValue))
                .findFirst().orElseThrow().key()).containsExactly(invalidKey);
            assertThat(registry.counter("novel.reading.kafka.dlt").count()).isEqualTo(5);
            assertThat(registry.counter("novel.reading.kafka.persisted_seconds").count())
                .isEqualTo(90);
            assertThat(registry.counter("novel.reading.kafka.consumed").count()).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reading_event_dedup", Integer.class))
                .isEqualTo(10);
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM book_reading_daily WHERE book_id IN (104,106)", Integer.class))
                .isZero();
            assertThat(jdbc.queryForObject(
                "SELECT credited_seconds FROM book_reading_daily WHERE book_id=107", Long.class))
                .isEqualTo(10);
            verify(visitWriter).write(Map.of(9001L, 1L));
            verifyNoMoreInteractions(visitWriter);
        } finally {
            endpoints.getListenerContainers().forEach(MessageListenerContainer::stop);
            producerFactory.destroy();
        }
    }

    private Consumer<byte[], byte[]> dltConsumer() {
        Consumer<byte[], byte[]> consumer = new DefaultKafkaConsumerFactory<>(Map.<String, Object>of(
            "bootstrap.servers", broker.getBrokersAsString(), "group.id", "dlt-observer",
            "enable.auto.commit", false), new ByteArrayDeserializer(), new ByteArrayDeserializer())
            .createConsumer();
        List<TopicPartition> partitions = new ArrayList<>();
        for (String topic : List.of("reading-it-dlt", "visit-it-dlt")) {
            for (int partition = 0; partition < 3; partition++) {
                partitions.add(new TopicPartition(topic, partition));
            }
        }
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
        return consumer;
    }

    private static ReadingEngagementEvent event(int id, long book) {
        return ReadingEngagementConsumerTest.event(id, book);
    }

    private static byte[] longKey(long key) {
        return ByteBuffer.allocate(Long.BYTES).putLong(key).array();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    @EnableTransactionManagement
    @EnableConfigurationProperties(KafkaProperties.class)
    @Import({ReadingEngagementKafkaConfig.class, BookVisitKafkaConfig.class,
        ReadingEngagementConsumer.class, ReadingDailyBatchWriter.class,
        ReadingEventValidator.class, ReadingEventFingerprint.class,
        ReadingDailyBatchAggregator.class, BookVisitEventConsumer.class, BookVisitBatchAggregator.class})
    static class TestConfig {
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
        @Bean BookVisitBatchWriter visitWriter() { return mock(BookVisitBatchWriter.class); }

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:h2:mem:reading_kafka_it;MODE=MySQL;DB_CLOSE_DELAY=-1");
            config.setUsername("sa");
            HikariDataSource dataSource = new HikariDataSource(config);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE reading_event_dedup (event_id VARCHAR(36) PRIMARY KEY, "
                + "event_fingerprint BINARY(32) NOT NULL, batch_token VARCHAR(36) NOT NULL, "
                + "stat_date DATE NOT NULL, created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3))");
            jdbc.execute("CREATE TABLE book_reading_daily (stat_date DATE NOT NULL, book_id BIGINT NOT NULL, "
                + "credited_seconds BIGINT NOT NULL, heartbeat_count BIGINT NOT NULL, "
                + "first_event_at TIMESTAMP(3) NOT NULL, last_event_at TIMESTAMP(3) NOT NULL, "
                + "update_time TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(stat_date,book_id))");
            return dataSource;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setDatabaseId("h2");
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mybatis/mapping/ReadingAggregationMapper.xml"));
            return factory.getObject();
        }
        @Bean ReadingAggregationMapper readingAggregationMapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(ReadingAggregationMapper.class);
        }
        @Bean(destroyMethod = "destroy")
        DefaultKafkaProducerFactory<Object, Object> producerFactory(KafkaProperties properties) {
            @SuppressWarnings("unchecked")
            Serializer<Object> keySerializer = (Serializer<Object>) (Serializer<?>) new LongSerializer();
            return new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(null), keySerializer, new JsonSerializer<>());
        }
        @Bean KafkaTemplate<Object, Object> kafkaTemplate(
            @Qualifier("producerFactory") DefaultKafkaProducerFactory<Object, Object> producerFactory
        ) {
            return new KafkaTemplate<>(producerFactory);
        }
        @Bean
        ConcurrentKafkaListenerContainerFactory<Long, BookVisitEvent> kafkaListenerContainerFactory(
            KafkaProperties properties,
            @Qualifier("bookVisitErrorHandler") DefaultErrorHandler handler
        ) {
            Map<String, Object> props = properties.buildConsumerProperties(null);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
            props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, LongDeserializer.class);
            props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
            props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, BookVisitEvent.class.getName());
            props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
            props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.java2nb.novel.event");
            var factory = new ConcurrentKafkaListenerContainerFactory<Long, BookVisitEvent>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            factory.setBatchListener(true);
            factory.setAutoStartup(false);
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
            factory.setCommonErrorHandler(handler);
            return factory;
        }
        @Bean static BeanPostProcessor readingFactoryControl() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (name.equals("readingEngagementKafkaListenerContainerFactory")) {
                        @SuppressWarnings("unchecked")
                        var factory = (ConcurrentKafkaListenerContainerFactory<Long, ReadingEngagementEvent>) bean;
                        factory.setAutoStartup(false);
                        factory.setBatchInterceptor((records, consumer) -> {
                            FIRST_POLL.compareAndSet(null, StreamSupport.stream(records.spliterator(), false)
                                .map(record -> record.offset()).toList());
                            return records;
                        });
                    }
                    return bean;
                }
            };
        }
    }
}
