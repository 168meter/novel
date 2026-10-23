package com.java2nb.novel.service.impl;

import com.java2nb.novel.entity.UserBuyRecord;
import com.java2nb.novel.mapper.FrontUserMapper;
import com.java2nb.novel.mapper.UserBuyRecordMapper;
import com.java2nb.novel.service.UserService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.assertj.core.api.Assertions.*;

/** Real Mapper SQL and Spring transaction proxy; MySQL subclass uses the same contract. */
class UserPurchaseTransactionTest {
    static final long USER_ID = 8_810_000_000_000_001L;
    HikariDataSource source;
    JdbcTemplate jdbc;
    UserService service;
    FrontUserMapper users;
    final ReadBarrier readBarrier = new ReadBarrier();

    HikariDataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:purchase_" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=USER");
        config.setUsername("sa");
        config.setMaximumPoolSize(4);
        return new HikariDataSource(config);
    }

    @BeforeEach
    void setup() throws Exception {
        source = dataSource();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE IF NOT EXISTS `user` (id BIGINT PRIMARY KEY, username VARCHAR(50),"
            + "nick_name VARCHAR(50),user_photo VARCHAR(100),user_sex TINYINT,account_balance BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_buy_record (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
            + "user_id BIGINT NOT NULL,book_id BIGINT,book_name VARCHAR(50),book_index_id BIGINT,"
            + "book_index_name VARCHAR(100),buy_amount INT,create_time TIMESTAMP,"
            + "UNIQUE KEY key_userId_indexId(user_id,book_index_id)) ENGINE=InnoDB");
        cleanRows();
        jdbc.update("INSERT INTO `user`(id,account_balance) VALUES(?,100)", USER_ID);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setMapperLocations(new ClassPathResource("mybatis/mapping/UserMapper.xml"));
        bean.setPlugins(readBarrier);
        var factory = bean.getObject();
        factory.getConfiguration().addMapper(UserBuyRecordMapper.class);
        var sessions = new SqlSessionTemplate(factory);
        users = sessions.getMapper(FrontUserMapper.class);
        var target = new UserServiceImpl(users, null, null, null, sessions.getMapper(UserBuyRecordMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
            new AnnotationTransactionAttributeSource()));
        service = (UserService) proxy.getProxy();
    }

    @AfterEach
    void close() {
        if (jdbc != null) cleanRows();
        if (source != null) source.close();
    }

    void cleanRows() {
        jdbc.update("DELETE FROM user_buy_record WHERE user_id=?", USER_ID);
        jdbc.update("DELETE FROM `user` WHERE id=?", USER_ID);
    }

    @Test
    void differentChaptersDebitBothPricesWithoutLostUpdate() throws Exception {
        // On the old implementation both completed balance reads are held here before either write.
        readBarrier.reads = new CountDownLatch(2);
        assertThat(concurrently(101L, 102L, 30)).containsExactlyInAnyOrder(true, true);
        assertThat(balance()).isEqualTo(40);
        assertThat(records()).isEqualTo(2);
    }

    @Test
    void concurrentDuplicateChapterDebitsExactlyOnce() throws Exception {
        assertThat(concurrently(101L, 101L, 30)).containsExactlyInAnyOrder(true, false);
        assertThat(balance()).isEqualTo(70);
        assertThat(records()).isEqualTo(1);
    }

    @Test
    void insufficientBalanceDoesNotInsertRecord() {
        assertThatThrownBy(() -> service.buyBookIndex(USER_ID, purchase(101L, 101)))
            .isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class)
            .hasMessageContaining("余额不足");
        assertThat(balance()).isEqualTo(100);
        assertThat(records()).isZero();
    }

    @Test
    void concurrentPurchasesCannotOverdraw() throws Exception {
        assertThat(concurrently(101L, 102L, 60)).containsExactlyInAnyOrder(true, false);
        assertThat(balance()).isEqualTo(40);
        assertThat(records()).isEqualTo(1);
    }

    @Test
    void duplicateInsertRollsBackAnyDebit() {
        service.buyBookIndex(USER_ID, purchase(101L, 30));
        assertThatThrownBy(() -> service.buyBookIndex(USER_ID, purchase(101L, 30)))
            .isInstanceOf(DuplicateKeyException.class);
        assertThat(balance()).isEqualTo(70);
        assertThat(records()).isEqualTo(1);
    }

    @Test
    void zeroPriceStillCreatesExactlyOneRecord() {
        service.buyBookIndex(USER_ID, purchase(101L, 0));
        assertThat(balance()).isEqualTo(100);
        assertThat(records()).isEqualTo(1);
    }

    @Test
    void negativePriceCannotCreditTheBalance() {
        assertThatThrownBy(() -> service.buyBookIndex(USER_ID, purchase(101L, -1)))
            .isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
        assertThat(balance()).isEqualTo(100);
        assertThat(records()).isZero();
    }

    private List<Boolean> concurrently(long first, long second, int price) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> buyAfterStart(first, price, ready, start));
            var b = executor.submit(() -> buyAfterStart(second, price, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean buyAfterStart(long chapter, int price, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            service.buyBookIndex(USER_ID, purchase(chapter, price));
            return true;
        } catch (DuplicateKeyException | io.github.xxyopen.web.exception.BusinessException expected) {
            return false;
        }
    }

    private UserBuyRecord purchase(long chapter, int price) {
        UserBuyRecord record = new UserBuyRecord();
        record.setBookId(1L);
        record.setBookIndexId(chapter);
        record.setBuyAmount(price);
        return record;
    }

    private long balance() {
        return jdbc.queryForObject("SELECT account_balance FROM `user` WHERE id=?", Long.class, USER_ID);
    }

    private int records() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_buy_record WHERE user_id=?", Integer.class, USER_ID);
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class ReadBarrier implements Interceptor {
        volatile CountDownLatch reads;
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            CountDownLatch latch = reads;
            if (latch != null && statement.getId().endsWith(".selectMany")
                && statement.getBoundSql(invocation.getArgs()[1]).getSql().contains("account_balance")) {
                latch.countDown();
                assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }
    }
}
