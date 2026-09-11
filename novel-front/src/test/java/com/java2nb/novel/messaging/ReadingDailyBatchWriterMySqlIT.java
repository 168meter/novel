package com.java2nb.novel.messaging;

import com.java2nb.novel.engagement.ReadingBatchWriteResult;
import com.java2nb.novel.engagement.ReadingDailyAggregate;
import com.java2nb.novel.engagement.ReadingDailyBatchAggregator;
import com.java2nb.novel.engagement.ReadingDedupRecord;
import com.java2nb.novel.engagement.ReadingDedupState;
import com.java2nb.novel.engagement.ReadingEventFingerprint;
import com.java2nb.novel.engagement.ReadingEventValidator;
import com.java2nb.novel.event.ReadingEngagementEvent;
import com.java2nb.novel.mapper.ReadingAggregationMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "novel.mysql.it", matches = "true")
@SpringJUnitConfig(ReadingDailyBatchWriterMySqlIT.TestConfig.class)
class ReadingDailyBatchWriterMySqlIT {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate FIRST_DATE = LocalDate.of(2099, 12, 20);
    private static final LocalDate SECOND_DATE = FIRST_DATE.plusDays(1);
    private static final long FIRST_BOOK = 8_800_000_000_000_001L;
    private static final long SECOND_BOOK = 8_800_000_000_000_002L;
    private static final long ROLLBACK_BOOK = 8_800_000_000_000_003L;
    private static final List<String> EVENT_IDS = List.of(
        "91000000-0000-0000-0000-000000000001",
        "91000000-0000-0000-0000-000000000002",
        "91000000-0000-0000-0000-000000000003",
        "91000000-0000-0000-0000-000000000004",
        "91000000-0000-0000-0000-000000000010",
        "91000000-0000-0000-0000-000000000020");

    @Autowired
    @Qualifier("readingDailyBatchWriter")
    private ReadingDailyBatchWriter writer;
    @Autowired
    @Qualifier("rollbackWriter")
    private ReadingDailyBatchWriter rollbackWriter;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS book_reading_daily ("
            + "stat_date date NOT NULL, book_id bigint NOT NULL, "
            + "credited_seconds bigint unsigned NOT NULL DEFAULT 0, "
            + "heartbeat_count bigint unsigned NOT NULL DEFAULT 0, "
            + "first_event_at datetime(3) NOT NULL, last_event_at datetime(3) NOT NULL, "
            + "create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
            + "update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) "
            + "ON UPDATE CURRENT_TIMESTAMP(3), PRIMARY KEY (stat_date, book_id), "
            + "KEY idx_book_reading_daily_book_date (book_id, stat_date)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS reading_event_dedup ("
            + "event_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, "
            + "event_fingerprint binary(32) NOT NULL, batch_token char(36) "
            + "CHARACTER SET ascii COLLATE ascii_bin NOT NULL, stat_date date NOT NULL, "
            + "created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
            + "PRIMARY KEY (event_id), KEY idx_reading_event_dedup_batch (batch_token), "
            + "KEY idx_reading_event_dedup_created (created_at)) ENGINE=InnoDB");
    }

    @BeforeEach
    @AfterEach
    void cleanReservedRows() {
        String eventPlaceholders = String.join(",", java.util.Collections.nCopies(
            EVENT_IDS.size(), "?"));
        jdbc.update("DELETE FROM reading_event_dedup WHERE event_id IN ("
            + eventPlaceholders + ")", EVENT_IDS.toArray());
        jdbc.update("DELETE FROM book_reading_daily WHERE book_id IN (?, ?, ?) "
                + "AND stat_date IN (?, ?)",
            FIRST_BOOK, SECOND_BOOK, ROLLBACK_BOOK, FIRST_DATE, SECOND_DATE);
    }

    @Test
    void persistsDateAndBookSplitsAndMakesReplayIdempotent() {
        List<ReadingEngagementEvent> events = List.of(
            event(EVENT_IDS.get(0), FIRST_BOOK, FIRST_DATE, 1),
            event(EVENT_IDS.get(1), FIRST_BOOK, FIRST_DATE, 2),
            event(EVENT_IDS.get(2), SECOND_BOOK, FIRST_DATE, 3),
            event(EVENT_IDS.get(3), FIRST_BOOK, SECOND_DATE, 4));

        ReadingBatchWriteResult first = writer.write(events);
        ReadingBatchWriteResult replay = writer.write(events);

        assertThat(first).isEqualTo(new ReadingBatchWriteResult(4, 4, 0, 3, 120L));
        assertThat(replay).isEqualTo(new ReadingBatchWriteResult(4, 0, 4, 0, 0L));
        assertCounters(FIRST_DATE, FIRST_BOOK, 60L, 2L);
        assertCounters(FIRST_DATE, SECOND_BOOK, 30L, 1L);
        assertCounters(SECOND_DATE, FIRST_BOOK, 30L, 1L);
        assertThat(countDedup(EVENT_IDS.subList(0, 4))).isEqualTo(4);
    }

    @Test
    void concurrentSameEventIdIsCreditedExactlyOnce() throws Exception {
        ReadingEngagementEvent event = event(EVENT_IDS.get(4), FIRST_BOOK, FIRST_DATE, 5);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<ReadingBatchWriteResult> write = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return writer.write(List.of(event));
            };
            Future<ReadingBatchWriteResult> first = executor.submit(write);
            Future<ReadingBatchWriteResult> second = executor.submit(write);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(20, TimeUnit.SECONDS).newEvents(),
                second.get(20, TimeUnit.SECONDS).newEvents()))
                .containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertCounters(FIRST_DATE, FIRST_BOOK, 30L, 1L);
        assertThat(countDedup(List.of(EVENT_IDS.get(4)))).isEqualTo(1);
    }

    @Test
    void exceptionAfterDedupInsertRollsBackRegistrationAndAggregate() {
        ReadingEngagementEvent event = event(
            EVENT_IDS.get(5), ROLLBACK_BOOK, FIRST_DATE, 6);

        assertThatThrownBy(() -> rollbackWriter.write(List.of(event)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("forced failure after dedup insert");

        assertThat(countDedup(List.of(EVENT_IDS.get(5)))).isZero();
        Integer dailyRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM book_reading_daily WHERE stat_date = ? AND book_id = ?",
            Integer.class, FIRST_DATE, ROLLBACK_BOOK);
        assertThat(dailyRows).isZero();
    }

    private void assertCounters(LocalDate date, long bookId, long seconds, long heartbeats) {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT credited_seconds, heartbeat_count FROM book_reading_daily "
                + "WHERE stat_date = ? AND book_id = ?", date, bookId);
        assertThat(((Number) row.get("credited_seconds")).longValue()).isEqualTo(seconds);
        assertThat(((Number) row.get("heartbeat_count")).longValue()).isEqualTo(heartbeats);
    }

    private int countDedup(List<String> eventIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(
            eventIds.size(), "?"));
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM reading_event_dedup WHERE event_id IN ("
                + placeholders + ")",
            Integer.class,
            eventIds.toArray());
        return count == null ? 0 : count;
    }

    private static ReadingEngagementEvent event(
        String eventId, long bookId, LocalDate date, int secondOffset
    ) {
        Instant occurredAt = date.atTime(12, 0).atZone(SHANGHAI).toInstant()
            .plusSeconds(secondOffset);
        return new ReadingEngagementEvent(
            eventId, bookId, 7L, 30, occurredAt, date, 1);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfig {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl());
            config.setUsername(System.getProperty("novel.mysql.username", "root"));
            config.setPassword(System.getProperty("novel.mysql.password", "123456"));
            config.setMaximumPoolSize(6);
            config.setPoolName("reading-writer-it");
            return new HikariDataSource(config);
        }

        static String jdbcUrl() {
            return "jdbc:mysql://127.0.0.1:3307/novel_plus_reading_it"
                + "?createDatabaseIfNotExist=true&useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource(
                "mybatis/mapping/ReadingAggregationMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        ReadingAggregationMapper readingAggregationMapper(SqlSessionTemplate template) {
            return template.getMapper(ReadingAggregationMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ReadingEventValidator readingEventValidator() {
            return new ReadingEventValidator();
        }

        @Bean
        ReadingEventFingerprint readingEventFingerprint() {
            return new ReadingEventFingerprint();
        }

        @Bean
        ReadingDailyBatchAggregator readingDailyBatchAggregator() {
            return new ReadingDailyBatchAggregator();
        }

        @Bean("readingDailyBatchWriter")
        ReadingDailyBatchWriter readingDailyBatchWriter(
            ReadingAggregationMapper mapper,
            ReadingEventValidator validator,
            ReadingEventFingerprint fingerprint,
            ReadingDailyBatchAggregator aggregator
        ) {
            return new ReadingDailyBatchWriter(mapper, validator, fingerprint, aggregator);
        }

        @Bean("rollbackWriter")
        ReadingDailyBatchWriter rollbackWriter(
            ReadingAggregationMapper mapper,
            ReadingEventValidator validator,
            ReadingEventFingerprint fingerprint,
            ReadingDailyBatchAggregator aggregator
        ) {
            return new ReadingDailyBatchWriter(
                new FailingAfterInsertMapper(mapper), validator, fingerprint, aggregator);
        }
    }

    private static final class FailingAfterInsertMapper implements ReadingAggregationMapper {
        private final ReadingAggregationMapper delegate;

        private FailingAfterInsertMapper(ReadingAggregationMapper delegate) {
            this.delegate = delegate;
        }

        @Override
        public int insertDedupRecords(List<ReadingDedupRecord> records) {
            delegate.insertDedupRecords(records);
            throw new IllegalStateException("forced failure after dedup insert");
        }

        @Override
        public List<ReadingDedupState> findDedupStates(List<String> eventIds) {
            return delegate.findDedupStates(eventIds);
        }

        @Override
        public int upsertDailyAggregates(List<ReadingDailyAggregate> aggregates) {
            return delegate.upsertDailyAggregates(aggregates);
        }

        @Override
        public int deleteDedupBefore(java.time.LocalDateTime cutoff, int limit) {
            return delegate.deleteDedupBefore(cutoff, limit);
        }
    }
}
