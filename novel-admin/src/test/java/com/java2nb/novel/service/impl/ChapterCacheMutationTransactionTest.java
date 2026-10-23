package com.java2nb.novel.service.impl;

import com.java2nb.novel.cache.AdminChapterCacheInvalidator;
import com.java2nb.novel.dao.*;
import com.java2nb.novel.domain.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual H2/Spring transactions; DAO adapters are not production MySQL integration evidence. */
class ChapterCacheMutationTransactionTest {
    final JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:chapter_tx_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
    final DataSourceTransactionManager tx=new DataSourceTransactionManager(jdbc.getDataSource());
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final BookIndexDao indexes=mock(BookIndexDao.class);
    final BookContentDao contents=mock(BookContentDao.class);
    final BookDao books=mock(BookDao.class);
    final AdminChapterCacheInvalidator invalidator=new AdminChapterCacheInvalidator(redis,new SimpleMeterRegistry());
    ChapterCacheMutationTransactionTest(){
        jdbc.execute("CREATE TABLE book(id BIGINT PRIMARY KEY)");jdbc.update("INSERT INTO book VALUES(101)");
        jdbc.execute("CREATE TABLE book_index(id BIGINT PRIMARY KEY,book_id BIGINT,storage_type VARCHAR(12))");
        jdbc.update("INSERT INTO book_index VALUES(201,101,'db')");
        jdbc.execute("CREATE TABLE book_content(id BIGINT PRIMARY KEY,index_id BIGINT,content VARCHAR(100))");
        jdbc.update("INSERT INTO book_content VALUES(301,201,'old')");
        when(indexes.get(201L)).thenAnswer(i -> {
            var row=new BookIndexDO();row.setId(201L);row.setBookId(jdbc.queryForObject("SELECT book_id FROM book_index WHERE id=201",Long.class));row.setStorageType("db");return row;
        });
        when(contents.get(301L)).thenAnswer(i -> {var row=new BookContentDO();row.setId(301L);row.setIndexId(201L);return row;});
        when(contents.update(any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();verifyNoInteractions(redis);
            BookContentDO patch=i.getArgument(0);return jdbc.update("UPDATE book_content SET content=? WHERE id=?",patch.getContent(),patch.getId());
        });
        when(indexes.getIdsByBookId(101L)).thenAnswer(i -> jdbc.queryForList("SELECT id FROM book_index WHERE book_id=101",Long.class));
        when(books.batchRemove(any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            verifyNoInteractions(redis);return jdbc.update("DELETE FROM book WHERE id=101");
        });
    }
    @Test void bodyEditCommitsBeforeItsImmutableIdentityIsInvalidated(){
        var service=proxy(new BookContentServiceImpl(contents,indexes,invalidator));service.update(patch());
        assertThat(jdbc.queryForObject("SELECT content FROM book_content WHERE id=301",String.class)).isEqualTo("new");
        assertThat(jdbc.queryForObject("SELECT index_id FROM book_content WHERE id=301",Long.class)).isEqualTo(201L);
        verify(redis).delete(Set.of("novel:chapter:v1:101:201","novel:chapter:lock:v1:101:201"));
    }
    @Test void rolledBackBodyEditDoesNotInvalidate(){
        var service=proxy(new BookContentServiceImpl(contents,indexes,invalidator));
        new TransactionTemplate(tx).execute(status -> {service.update(patch());verifyNoInteractions(redis);status.setRollbackOnly();return null;});
        assertThat(jdbc.queryForObject("SELECT content FROM book_content WHERE id=301",String.class)).isEqualTo("old");verifyNoInteractions(redis);
    }
    @Test void rejectedIdentityChangeRollsBackWithoutInvalidation(){
        var service=proxy(new BookContentServiceImpl(contents,indexes,invalidator));var patch=patch();patch.setIndexId(202L);
        assertThatThrownBy(()->service.update(patch)).isInstanceOf(com.java2nb.common.exception.BusinessException.class);
        verify(contents,never()).update(any());verifyNoInteractions(redis);
        assertThat(jdbc.queryForObject("SELECT index_id FROM book_content WHERE id=301",Long.class)).isEqualTo(201L);
    }
    @Test void rolledBackBodyInsertDoesNotInvalidate(){
        when(contents.save(any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            BookContentDO row=i.getArgument(0);return jdbc.update("INSERT INTO book_content VALUES(?,?,?)",row.getId(),row.getIndexId(),row.getContent());
        });
        var service=proxy(new BookContentServiceImpl(contents,indexes,invalidator));
        new TransactionTemplate(tx).execute(status -> {var row=patch();row.setId(302L);row.setIndexId(201L);service.save(row);verifyNoInteractions(redis);status.setRollbackOnly();return null;});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_content WHERE id=302",Integer.class)).isZero();verifyNoInteractions(redis);
    }
    @Test void rolledBackBatchBookDeleteDoesNotInvalidateOrDeleteDirectories(){
        var service=proxy(new BookServiceImpl(books,indexes,contents,invalidator));
        new TransactionTemplate(tx).execute(status -> {service.batchRemove(new Long[]{101L});verifyNoInteractions(redis);status.setRollbackOnly();return null;});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_index",Integer.class)).isEqualTo(1);verifyNoInteractions(redis);
    }
    @Test void committedBatchBookDeleteOnlyAddsInvalidationToExistingDeleteSemantics(){
        var service=proxy(new BookServiceImpl(books,indexes,contents,invalidator));service.batchRemove(new Long[]{101L});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_index",Integer.class)).isEqualTo(1);
        verify(redis).delete(Set.of("novel:chapter:v1:101:201","novel:chapter:lock:v1:101:201"));
    }
    BookContentDO patch(){var p=new BookContentDO();p.setId(301L);p.setContent("new");return p;}
    @SuppressWarnings("unchecked") <T>T proxy(T target){var p=new ProxyFactory(target);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(tx,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
}