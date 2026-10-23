package com.java2nb.novel.messaging;

import com.java2nb.novel.mapper.FrontBookMapper;
import com.java2nb.novel.event.BookVisitEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.kafka.listener.BatchListenerFailedException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Reuses existing H2 dependency; real JDBC transactions, mapper is a test adapter (not MySQL evidence). */
class BookVisitBatchWriterTransactionTest {
    @Test
    void rollsBackAllIncrementsAndReportsIndexZeroOnDatabaseFailure() {
        withWriter((writer, jdbc) -> {
            var totals = new LinkedHashMap<Long, Long>();
            totals.put(1L, 1L);totals.put(2L, 1L);
            assertThatThrownBy(() -> writer.write(totals)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count(jdbc, 1L)).isZero();
            assertThat(count(jdbc, 2L)).isEqualTo(100L);
            var registry = new SimpleMeterRegistry();
            var consumer = new BookVisitEventConsumer(new BookVisitBatchAggregator(), writer, registry);
            assertThatThrownBy(() -> consumer.consume(List.of(event(1L, 1L), event(2L, 1L), event(3L, 0L))))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, failure -> assertThat(failure.getIndex()).isZero());
            assertThat(count(jdbc, 1L)).isZero();
            assertThat(registry.counter("novel.book.visit.kafka.consumed").count()).isZero();
        });
    }

    @Test
    void prefixIsCommittedBeforeInvalidIndexEscapesAndSuffixRemainsUnwritten() {
        withWriter((writer, jdbc) -> {
            var consumer = new BookVisitEventConsumer(new BookVisitBatchAggregator(), writer, new SimpleMeterRegistry());
            assertThatThrownBy(() -> consumer.consume(List.of(event(1L, 1L), event(2L, 0L), event(3L, 1L))))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, failure -> {
                    assertThat(failure.getIndex()).isEqualTo(1);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(count(jdbc, 1L)).isEqualTo(1L);
                    assertThat(count(jdbc, 3L)).isZero();
                });
        });
    }

    private void withWriter(java.util.function.BiConsumer<BookVisitBatchWriter, JdbcTemplate> test) {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:visit_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE visit_fixture(id BIGINT PRIMARY KEY, visits BIGINT CHECK(visits <= 100))");
        jdbc.update("INSERT INTO visit_fixture VALUES(1,0),(2,100),(3,0)");
        var mapper = mock(FrontBookMapper.class);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.update("UPDATE visit_fixture SET visits=visits+? WHERE id=?", new Object[]{call.getArgument(1), call.getArgument(0)});
            return null;
        }).when(mapper).addVisitCount(anyLong(), anyLong());
        var proxy = new ProxyFactory(new BookVisitBatchWriter(mapper));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        try {test.accept((BookVisitBatchWriter)proxy.getProxy(), jdbc);}
        finally {jdbc.execute("SHUTDOWN");}
    }
    private long count(JdbcTemplate jdbc, long id) {return jdbc.queryForObject("SELECT visits FROM visit_fixture WHERE id=?", Long.class, id);}
    private BookVisitEvent event(long id, long delta) {return new BookVisitEvent("e", id, delta, Instant.now(), 1);}
}
