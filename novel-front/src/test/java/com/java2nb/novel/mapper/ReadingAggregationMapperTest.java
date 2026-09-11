package com.java2nb.novel.mapper;

import com.java2nb.novel.engagement.ReadingDailyAggregate;
import com.java2nb.novel.engagement.ReadingDedupRecord;
import com.java2nb.novel.engagement.ReadingDedupState;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingAggregationMapperTest {

    private static final LocalDate STAT_DATE = LocalDate.of(2026, 9, 11);
    private static final String EVENT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String BATCH_TOKEN = "10000000-0000-0000-0000-000000000001";

    private PooledDataSource dataSource;
    private SqlSessionFactory sessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PooledDataSource(
            "org.h2.Driver",
            "jdbc:h2:mem:reading_aggregation;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            "");
        prepareTables();
        sessionFactory = sessionFactory();
    }

    @Test
    void duplicateEventRegistrationKeepsTheFirstBatchOwnership() {
        ReadingDedupRecord first = dedup(EVENT_ID, BATCH_TOKEN, (byte) 1, STAT_DATE);
        ReadingDedupRecord duplicate = dedup(
            EVENT_ID, "20000000-0000-0000-0000-000000000002", (byte) 2, STAT_DATE);

        try (SqlSession session = sessionFactory.openSession(true)) {
            ReadingAggregationMapper mapper = session.getMapper(ReadingAggregationMapper.class);
            assertThat(mapper.insertDedupRecords(List.of(first))).isEqualTo(1);
            assertThat(mapper.insertDedupRecords(List.of(duplicate))).isZero();
            List<ReadingDedupState> states = mapper.findDedupStates(List.of(EVENT_ID));

            assertThat(states).hasSize(1);
            assertThat(states.get(0).batchToken()).isEqualTo(BATCH_TOKEN);
            assertThat(states.get(0).eventFingerprint()).containsExactly(first.eventFingerprint());
        }
    }

    @Test
    void bulkStatementsPersistAndReadMultipleRowsInOneCall() throws Exception {
        String secondEventId = "00000000-0000-0000-0000-000000000002";
        ReadingDedupRecord first = dedup(EVENT_ID, BATCH_TOKEN, (byte) 1, STAT_DATE);
        ReadingDedupRecord second = dedup(secondEventId, BATCH_TOKEN, (byte) 2, STAT_DATE);
        Instant firstTime = Instant.parse("2026-09-11T01:00:00.123Z");
        Instant secondTime = Instant.parse("2026-09-11T02:00:00.456Z");

        try (SqlSession session = sessionFactory.openSession(true)) {
            ReadingAggregationMapper mapper = session.getMapper(ReadingAggregationMapper.class);

            assertThat(mapper.insertDedupRecords(List.of(first, second))).isEqualTo(2);
            assertThat(mapper.findDedupStates(List.of(EVENT_ID, secondEventId)))
                .extracting(ReadingDedupState::eventId)
                .containsExactlyInAnyOrder(EVENT_ID, secondEventId);
            assertThat(mapper.upsertDailyAggregates(List.of(
                daily(42L, 30L, 1L, firstTime, firstTime),
                daily(43L, 60L, 2L, secondTime, secondTime))))
                .isEqualTo(2);
        }

        assertThat(readDailyCounters(42L)).containsExactly(30L, 1L);
        assertThat(readDailyCounters(43L)).containsExactly(60L, 2L);
    }

    @Test
    void dailyUpsertAddsCountersAndKeepsEventTimeBounds() throws Exception {
        Instant middle = Instant.parse("2026-09-11T01:00:00.456Z");
        Instant earlier = Instant.parse("2026-09-11T00:30:00.123Z");
        Instant later = Instant.parse("2026-09-11T01:30:00.789Z");

        try (SqlSession session = sessionFactory.openSession(true)) {
            ReadingAggregationMapper mapper = session.getMapper(ReadingAggregationMapper.class);
            mapper.upsertDailyAggregates(List.of(daily(42L, 30L, 1L, middle, middle)));
            mapper.upsertDailyAggregates(List.of(daily(42L, 60L, 2L, earlier, later)));
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT credited_seconds, heartbeat_count, first_event_at, last_event_at "
                     + "FROM book_reading_daily WHERE stat_date = ? AND book_id = ?")) {
            statement.setObject(1, STAT_DATE);
            statement.setLong(2, 42L);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(90L);
                assertThat(result.getLong(2)).isEqualTo(3L);
                assertThat(result.getTimestamp(3).toInstant()).isEqualTo(earlier);
                assertThat(result.getTimestamp(4).toInstant()).isEqualTo(later);
            }
        }
    }

    @Test
    void cleanupDeletesOnlyRowsOlderThanCutoffAndHonorsLimit() throws Exception {
        insertDedupDirectly("00000000-0000-0000-0000-000000000011", LocalDateTime.of(2026, 8, 1, 0, 0));
        insertDedupDirectly("00000000-0000-0000-0000-000000000012", LocalDateTime.of(2026, 8, 2, 0, 0));
        insertDedupDirectly("00000000-0000-0000-0000-000000000013", LocalDateTime.of(2026, 9, 1, 0, 0));

        try (SqlSession session = sessionFactory.openSession(true)) {
            ReadingAggregationMapper mapper = session.getMapper(ReadingAggregationMapper.class);
            assertThat(mapper.deleteDedupBefore(LocalDateTime.of(2026, 8, 15, 0, 0), 1))
                .isEqualTo(1);
        }

        assertThat(allEventIds()).containsExactly(
            "00000000-0000-0000-0000-000000000012",
            "00000000-0000-0000-0000-000000000013");
    }

    private ReadingDedupRecord dedup(
        String eventId, String batchToken, byte marker, LocalDate date
    ) {
        byte[] fingerprint = new byte[32];
        fingerprint[0] = marker;
        return new ReadingDedupRecord(eventId, fingerprint, batchToken, date);
    }

    private ReadingDailyAggregate daily(
        long bookId,
        long seconds,
        long heartbeats,
        Instant first,
        Instant last
    ) {
        return new ReadingDailyAggregate(STAT_DATE, bookId, seconds, heartbeats, first, last);
    }

    private void prepareTables() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS reading_event_dedup");
            statement.execute("DROP TABLE IF EXISTS book_reading_daily");
            statement.execute("CREATE TABLE reading_event_dedup ("
                + "event_id CHAR(36) PRIMARY KEY, event_fingerprint BINARY(32) NOT NULL, "
                + "batch_token CHAR(36) NOT NULL, stat_date DATE NOT NULL, "
                + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))");
            statement.execute("CREATE TABLE book_reading_daily ("
                + "stat_date DATE NOT NULL, book_id BIGINT NOT NULL, "
                + "credited_seconds BIGINT NOT NULL DEFAULT 0, heartbeat_count BIGINT NOT NULL DEFAULT 0, "
                + "first_event_at TIMESTAMP(3) NOT NULL, last_event_at TIMESTAMP(3) NOT NULL, "
                + "create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "PRIMARY KEY (stat_date, book_id))");
        }
    }

    private SqlSessionFactory sessionFactory() throws Exception {
        Environment environment = new Environment(
            "test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        VendorDatabaseIdProvider databaseIdProvider = new VendorDatabaseIdProvider();
        Properties databaseIds = new Properties();
        databaseIds.setProperty("H2", "h2");
        databaseIdProvider.setProperties(databaseIds);
        configuration.setDatabaseId(databaseIdProvider.getDatabaseId(dataSource));
        String resource = "mybatis/mapping/ReadingAggregationMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void insertDedupDirectly(String eventId, LocalDateTime createdAt) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO reading_event_dedup "
                     + "(event_id, event_fingerprint, batch_token, stat_date, created_at) "
                     + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, eventId);
            statement.setBytes(2, new byte[32]);
            statement.setString(3, BATCH_TOKEN);
            statement.setObject(4, STAT_DATE);
            statement.setObject(5, createdAt);
            statement.executeUpdate();
        }
    }

    private List<String> allEventIds() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT event_id FROM reading_event_dedup ORDER BY created_at")) {
            java.util.ArrayList<String> ids = new java.util.ArrayList<>();
            while (result.next()) {
                ids.add(result.getString(1));
            }
            return ids;
        }
    }

    private List<Long> readDailyCounters(long bookId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT credited_seconds, heartbeat_count FROM book_reading_daily "
                     + "WHERE stat_date = ? AND book_id = ?")) {
            statement.setObject(1, STAT_DATE);
            statement.setLong(2, bookId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return List.of(result.getLong(1), result.getLong(2));
            }
        }
    }
}
