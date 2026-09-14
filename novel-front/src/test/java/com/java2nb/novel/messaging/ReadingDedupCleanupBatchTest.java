package com.java2nb.novel.messaging;

import com.java2nb.novel.mapper.ReadingAggregationMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ReadingDedupCleanupBatchTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 8, 28, 12, 0);

    @Test
    void passesTheExactCutoffAndLimitToOneDeleteStatement() {
        ReadingAggregationMapper mapper = mock(ReadingAggregationMapper.class);
        when(mapper.deleteDedupBefore(CUTOFF, 5000)).thenReturn(27);
        assertThat(new ReadingDedupCleanupBatch(mapper).deleteBefore(CUTOFF, 5000)).isEqualTo(27);
        verify(mapper).deleteDedupBefore(CUTOFF, 5000);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void requiresNewTransactionAndRollbackForEveryException() throws Exception {
        Transactional annotation = ReadingDedupCleanupBatch.class
            .getMethod("deleteBefore", LocalDateTime.class, int.class).getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(annotation.rollbackFor()).containsExactly(Exception.class);
    }

    @Test
    void rejectsUnsafeDeleteLimitsBeforeCallingTheDatabase() {
        ReadingAggregationMapper mapper = mock(ReadingAggregationMapper.class);
        ReadingDedupCleanupBatch batch = new ReadingDedupCleanupBatch(mapper);
        for (int limit : new int[] {-1, 0, 5001}) {
            assertThatThrownBy(() -> batch.deleteBefore(CUTOFF, limit))
                .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void completedDeleteSurvivesAnOuterTransactionRollback() {
        try (var context = new AnnotationConfigApplicationContext(TransactionConfig.class)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE reading_event_dedup (event_id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP(3))");
            jdbc.update("INSERT INTO reading_event_dedup VALUES ('old-event', ?)", CUTOFF.minusDays(1));
            jdbc.update("INSERT INTO reading_event_dedup VALUES ('recent-event', ?)", CUTOFF);
            ReadingDedupCleanupBatch batch = context.getBean(ReadingDedupCleanupBatch.class);
            var outer = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            outer.executeWithoutResult(status -> {
                assertThat(batch.deleteBefore(CUTOFF, 1)).isEqualTo(1);
                status.setRollbackOnly();
            });
            assertThat(jdbc.queryForList("SELECT event_id FROM reading_event_dedup", String.class))
                .containsExactly("recent-event");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig {
        @Bean DriverManagerDataSource dataSource() {
            return new DriverManagerDataSource(
                "jdbc:h2:mem:cleanup_tx_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean JdbcTemplate jdbcTemplate(DriverManagerDataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DriverManagerDataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DriverManagerDataSource source) throws Exception {
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            var configuration = new org.apache.ibatis.session.Configuration();
            configuration.setDatabaseId("h2");
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mybatis/mapping/ReadingAggregationMapper.xml"));
            return factory.getObject();
        }
        @Bean ReadingAggregationMapper mapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(ReadingAggregationMapper.class);
        }
        @Bean ReadingDedupCleanupBatch batch(ReadingAggregationMapper mapper) {
            return new ReadingDedupCleanupBatch(mapper);
        }
    }
}
