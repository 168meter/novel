package com.java2nb.novel.recommendation;

import com.java2nb.novel.vo.BookSettingVO;
import java.sql.Connection;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import static org.assertj.core.api.Assertions.assertThat;

class HomeRecommendationMapperTest {
    private PooledDataSource source;
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        source = new PooledDataSource("org.h2.Driver",
            "jdbc:h2:mem:home_recommendation;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        source.setDefaultAutoCommit(true);
        sql("DROP ALL OBJECTS",
            "CREATE TABLE book(id BIGINT PRIMARY KEY,book_name VARCHAR(50),author_name VARCHAR(50),"
                + "pic_url VARCHAR(100),book_desc VARCHAR(100),score REAL,cat_id INT,cat_name VARCHAR(50),"
                + "book_status TINYINT,is_vip TINYINT,word_count INT,visit_count BIGINT)",
            "CREATE TABLE book_index(id BIGINT PRIMARY KEY,book_id BIGINT,is_vip TINYINT)",
            "CREATE TABLE book_content(index_id BIGINT PRIMARY KEY,content TEXT)",
            "CREATE TABLE book_setting(id BIGINT PRIMARY KEY,book_id BIGINT,type TINYINT,sort TINYINT)",
            "CREATE TABLE book_reading_daily(book_id BIGINT,stat_date DATE,credited_seconds DECIMAL(30,0),"
                + "PRIMARY KEY(stat_date,book_id))");
        factory = mapperFactory(source);
    }

    private SqlSessionFactory mapperFactory(DataSource dataSource) throws Exception {
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        try (var stream = Resources.getResourceAsStream("mybatis/mapping/HomeRecommendationMapper.xml")) {
            new XMLMapperBuilder(stream, config, "mybatis/mapping/HomeRecommendationMapper.xml",
                config.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(config);
    }

    @AfterEach
    void close() { if (source != null) source.forceCloseAll(); }

    @Test
    void sumsBothWindowsWithoutMultiplyingMultipleChapters() throws Exception {
        book(1, 0); chapter(101, 1);
        credit(1, "2026-09-15", 10); credit(1, "2026-09-09", 20);
        credit(1, "2026-09-08", 40); credit(1, "2026-09-01", 80);
        credit(1, "2026-08-31", 1000); credit(1, "2026-09-16", 2000);
        var rows = candidates();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getWeekSeconds()).isEqualTo(new java.math.BigInteger("30"));
        assertThat(rows.get(0).getHotSeconds()).isEqualTo(new java.math.BigInteger("150"));
        assertThat(rows.get(0).getBookName()).isEqualTo("book1");
        assertThat(rows.get(0).getCatName()).isEqualTo("fiction");
    }

    @Test
    void filtersIneligibleBooksBeforeRanking() throws Exception {
        for (int id = 1; id <= 7; id++) { book(id, 100); credit(id, "2026-09-15", 100); }
        sql("UPDATE book SET is_vip=1 WHERE id=2", "UPDATE book SET word_count=0 WHERE id=3",
            "UPDATE book_index SET is_vip=1 WHERE book_id=4",
            "UPDATE book_content SET content='' WHERE index_id=5",
            "DELETE FROM book_content WHERE index_id=6", "DELETE FROM book_index WHERE book_id=7");
        credit(999, "2026-09-15", 9000);
        assertThat(candidates()).extracting(BookSettingVO::getBookId).containsExactly(1L);
    }

    @Test
    void boundsUnionAndBreaksReadingTiesByClicksThenId() throws Exception {
        for (int id = 1; id <= 30; id++) {
            book(id, id <= 2 ? 500 : 0);
            credit(id, id <= 5 ? "2026-09-15" : "2026-09-01", id <= 5 ? 10 : 100);
        }
        var rows = candidates();
        assertThat(rows).extracting(BookSettingVO::getBookId)
            .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L);
    }

    @Test
    void configuredCapsPreserveManualSlotsAndFilterOnlyAlgorithmSlots() throws Exception {
        for (int type = 0; type <= 4; type++) {
            for (int slot = 1; slot <= 12; slot++) {
                int id = type * 100 + slot;
                book(id, 0);
                sql("INSERT INTO book_setting VALUES(" + id + "," + id + "," + type + "," + slot + ")");
            }
            sql("UPDATE book SET is_vip=1 WHERE id=" + (type * 100 + 1));
        }
        try (var session = factory.openSession()) {
            var rows = session.getMapper(HomeRecommendationMapper.class).listConfigured();
            assertThat(rows).hasSize(31);
            assertThat(group(rows, 0)).containsExactly(1L, 2L, 3L, 4L);
            assertThat(group(rows, 1)).containsExactly(101L,102L,103L,104L,105L,106L,107L,108L,109L,110L);
            assertThat(group(rows, 2)).containsExactly(202L,203L,204L,205L,206L);
            assertThat(group(rows, 3)).containsExactly(302L,303L,304L,305L,306L,307L);
            assertThat(group(rows, 4)).containsExactly(401L,402L,403L,404L,405L,406L);
        }
    }

    @Test
    void queriesExecuteThroughShardingSphereMetadataLayer() throws Exception {
        book(1, 10);
        credit(1, "2026-09-15", 30);
        sql("INSERT INTO book_setting VALUES(1,1,0,1)");
        String yaml = """
            mode:
              type: Memory
            rules:
              - !SINGLE
                tables:
                  - "ds_1.*"
            props:
              sql-show: false
            """;
        DataSource wrapped = YamlShardingSphereDataSourceFactory.createDataSource(
            Map.of("ds_1", source), yaml.getBytes(StandardCharsets.UTF_8));
        try {
            SqlSessionFactory shardingFactory = mapperFactory(wrapped);
            try (var session = shardingFactory.openSession()) {
                HomeRecommendationMapper mapper = session.getMapper(HomeRecommendationMapper.class);
                assertThat(mapper.listConfigured()).extracting(BookSettingVO::getBookId).containsExactly(1L);
                assertThat(mapper.listCandidates(LocalDate.of(2026,9,9), LocalDate.of(2026,9,1),
                    LocalDate.of(2026,9,16))).extracting(BookSettingVO::getBookId).containsExactly(1L);
            }
        } finally {
            if (wrapped instanceof AutoCloseable closeable) closeable.close();
        }
    }

    private List<Long> group(List<BookSettingVO> rows, int type) {
        return rows.stream().filter(row -> row.getType() == type).map(BookSettingVO::getBookId).toList();
    }

    @Test
    void clickTieBreakAffectsBothTopListsRatherThanOnlyOutputOrder() throws Exception {
        for (int id = 1; id <= 16; id++) {
            book(id, id >= 15 ? 100 : 0);
            credit(id, "2026-09-15", 30);
        }
        assertThat(candidates()).extracting(BookSettingVO::getBookId)
            .containsExactly(1L,2L,3L,4L,5L,6L,7L,8L,9L,15L,16L);
    }

    @Test
    void emptyZeroAndOutOfWindowStatisticsDoNotCreateCandidates() throws Exception {
        assertThat(candidates()).isEmpty();
        book(1, 0); book(2, 0); book(3, 0);
        credit(1, "2026-09-15", 0);
        credit(2, "2026-08-31", 100);
        credit(3, "2026-09-16", 100);
        assertThat(candidates()).isEmpty();
    }

    @Test
    void nullableVipAndClicksRemainReadableButWhitespaceContentDoesNot() throws Exception {
        book(1, 0); book(2, 0);
        sql("UPDATE book SET is_vip=NULL,visit_count=NULL WHERE id=1",
            "UPDATE book_index SET is_vip=NULL WHERE id=1",
            "UPDATE book_content SET content='   ' WHERE index_id=2");
        credit(1, "2026-09-15", 30); credit(2, "2026-09-15", 90);
        var rows = candidates();
        assertThat(rows).extracting(BookSettingVO::getBookId).containsExactly(1L);
        assertThat(rows.get(0).getVisitCount()).isZero();
    }

    @Test
    void equalConfiguredSortUsesSettingIdAndIgnoresUnknownTypes() throws Exception {
        book(1,0); book(2,0);
        sql("INSERT INTO book_setting VALUES(20,1,0,1)",
            "INSERT INTO book_setting VALUES(10,2,0,1)",
            "INSERT INTO book_setting VALUES(30,1,5,1)");
        try (var session = factory.openSession()) {
            assertThat(session.getMapper(HomeRecommendationMapper.class).listConfigured())
                .extracting(BookSettingVO::getBookId).containsExactly(2L,1L);
        }
    }

    private List<PopularBookCandidate> candidates() {
        try (var session = factory.openSession()) {
            return session.getMapper(HomeRecommendationMapper.class).listCandidates(
                LocalDate.of(2026,9,9), LocalDate.of(2026,9,1), LocalDate.of(2026,9,16));
        }
    }

    private void book(int id, long clicks) throws Exception {
        sql("INSERT INTO book VALUES(" + id + ",'book" + id + "','author','cover','description',"
            + "4.5,1,'fiction',1,0,1000," + clicks + ")");
        chapter(id, id);
    }

    private void chapter(int chapterId, int bookId) throws Exception {
        sql("INSERT INTO book_index VALUES(" + chapterId + "," + bookId + ",0)",
            "INSERT INTO book_content VALUES(" + chapterId + ",'chapter content')");
    }

    private void credit(int id, String date, long seconds) throws Exception {
        sql("INSERT INTO book_reading_daily VALUES(" + id + ",'" + date + "'," + seconds + ")");
    }

    private void sql(String... statements) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }
}
