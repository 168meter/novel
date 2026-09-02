package com.java2nb.novel.mapper;

import com.java2nb.novel.vo.BookIndexNavigationVO;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class FrontBookIndexMapperTest {

    @Test
    void queryNavigationFindsNearestChaptersAcrossGapsAndBookBoundaries() throws Exception {
        DataSource dataSource = new PooledDataSource(
            "org.h2.Driver", "jdbc:h2:mem:chapter_navigation;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        prepareChapters(dataSource);
        SqlSessionFactory sessionFactory = sessionFactory(dataSource);

        try (SqlSession session = sessionFactory.openSession()) {
            FrontBookIndexMapper mapper = session.getMapper(FrontBookIndexMapper.class);

            BookIndexNavigationVO middle = mapper.queryNavigation(10L, 4);
            BookIndexNavigationVO first = mapper.queryNavigation(10L, 1);
            BookIndexNavigationVO last = mapper.queryNavigation(10L, 9);

            assertThat(middle.getPreBookIndexId()).isEqualTo(101L);
            assertThat(middle.getNextBookIndexId()).isEqualTo(109L);
            assertThat(first.getPreBookIndexId()).isZero();
            assertThat(first.getNextBookIndexId()).isEqualTo(104L);
            assertThat(last.getPreBookIndexId()).isEqualTo(104L);
            assertThat(last.getNextBookIndexId()).isZero();
        }
    }

    private void prepareChapters(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS book_index");
            statement.execute("CREATE TABLE book_index (id BIGINT PRIMARY KEY, book_id BIGINT, index_num INT)");
            statement.execute("INSERT INTO book_index(id, book_id, index_num) VALUES "
                + "(101, 10, 1), (104, 10, 4), (109, 10, 9), (999, 20, 5)");
        }
    }

    private SqlSessionFactory sessionFactory(DataSource dataSource) {
        Environment environment = new Environment(
            "test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(FrontBookIndexMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
