package com.java2nb.novel.service.impl;

import com.java2nb.novel.entity.*;
import com.java2nb.novel.mapper.*;
import com.java2nb.novel.service.BookService;
import io.github.xxyopen.web.exception.BusinessException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class CommentCorrectnessTest {
    JdbcTemplate jdbc;
    BookService service;
    FrontBookCommentMapper comments;
    FrontBookCommentReplyMapper replies;

    @BeforeEach
    void setup(TestInfo info) throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER", "sa", "");
        if (System.getenv("CORRECTNESS_MYSQL_PREFIX") != null && !System.getenv("CORRECTNESS_MYSQL_PREFIX").isBlank()) {
            String prefix=System.getenv("CORRECTNESS_MYSQL_PREFIX");
            if(!prefix.matches("cv_it_[a-z0-9_]{1,25}")){throw new IllegalArgumentException("Unsafe test schema prefix");}
            String schema=prefix+"_mf_"+Long.toUnsignedString(System.nanoTime(),36);
            String base="jdbc:mysql://127.0.0.1:23307/";
            String options="?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
            String user=System.getenv("CORRECTNESS_MYSQL_USER"),password=System.getenv("CORRECTNESS_MYSQL_PASSWORD");
            new JdbcTemplate(new DriverManagerDataSource(base+options,user,password)).execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            ds=new DriverManagerDataSource(base+schema+options,user,password);
            var real=new JdbcTemplate(ds);
            assertThat(real.queryForObject("SELECT VERSION()",String.class)).startsWith("8.");
            assertThat(real.queryForObject("SELECT @@default_storage_engine",String.class)).isEqualToIgnoringCase("InnoDB");
        }
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE book(id BIGINT PRIMARY KEY, comment_count INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE book_comment(id BIGINT AUTO_INCREMENT PRIMARY KEY,book_id BIGINT,comment_content VARCHAR(512),location VARCHAR(50),reply_count INT DEFAULT 0,audit_status TINYINT DEFAULT 0,moderation_version BIGINT NOT NULL DEFAULT 0,create_time TIMESTAMP,create_user_id BIGINT,UNIQUE(book_id,create_user_id))");
        jdbc.execute("CREATE TABLE book_comment_reply(id BIGINT AUTO_INCREMENT PRIMARY KEY,comment_id BIGINT,reply_content VARCHAR(512),location VARCHAR(50),audit_status TINYINT DEFAULT 0,moderation_version BIGINT NOT NULL DEFAULT 0,create_time TIMESTAMP,create_user_id BIGINT)");
        jdbc.execute("CREATE TABLE user(id BIGINT PRIMARY KEY,username VARCHAR(50),user_photo VARCHAR(50))");
        jdbc.update("INSERT INTO book(id) VALUES (1)");
        jdbc.update("INSERT INTO user VALUES (7,'reader','photo')");
        Configuration cfg = new Configuration(new Environment("test",new SpringManagedTransactionFactory(),ds));
        cfg.setMapUnderscoreToCamelCase(true);
        var pageInterceptor=new com.github.pagehelper.PageInterceptor();var pageProperties=new java.util.Properties();pageProperties.setProperty("helperDialect","mysql");pageInterceptor.setProperties(pageProperties);cfg.addInterceptor(pageInterceptor);
        for(String name : List.of("BookMapper.xml","BookCommentMapper.xml","BookCommentReplyMapper.xml")) {
            String path="mybatis/mapping/"+name;
            try(InputStream in=getClass().getClassLoader().getResourceAsStream(path)) {
                new XMLMapperBuilder(in,cfg,path,cfg.getSqlFragments()).parse();
            }
        }
        cfg.addMapper(FrontCommentModerationMapper.class);
        var sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
        comments = sessions.getMapper(FrontBookCommentMapper.class);
        replies = sessions.getMapper(FrontBookCommentReplyMapper.class);
        var constructor=BookServiceImpl.class.getDeclaredConstructors()[0];
        Object[] args=new Object[constructor.getParameterCount()];
        for(int i=0;i<args.length;i++){
            Class<?> type=constructor.getParameterTypes()[i];
            args[i]=type==com.java2nb.novel.comment.CommentModerationService.class?new com.java2nb.novel.comment.CommentModerationService(sessions.getMapper(FrontCommentModerationMapper.class)):type==FrontBookMapper.class?sessions.getMapper(FrontBookMapper.class):type==FrontBookCommentMapper.class?comments:type==FrontBookCommentReplyMapper.class?replies:mock(type);
        }
        var target=(BookServiceImpl)constructor.newInstance(args);
        var proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(BookService)proxy.getProxy();
    }

    BookComment comment(long book) { var c=new BookComment();c.setBookId(book);c.setCommentContent("review");return c; }
    BookCommentReply reply(long parent) {var r=new BookCommentReply();r.setCommentId(parent);r.setReplyContent("reply");return r;}
    long count(String sql) {return jdbc.queryForObject(sql,Long.class);}

    @AfterEach void saveMysqlEvidence(TestInfo info) throws Exception {
        if((System.getenv("CORRECTNESS_MYSQL_PREFIX")==null || System.getenv("CORRECTNESS_MYSQL_PREFIX").isBlank()) || jdbc==null){return;}
        var file=java.nio.file.Path.of(System.getenv("CORRECTNESS_EVIDENCE"),"mysql-front-"+info.getTestMethod().orElseThrow().getName()+".txt");
        java.nio.file.Files.writeString(file,"schema="+jdbc.queryForObject("SELECT DATABASE()",String.class)+"\nbook="+jdbc.queryForList("SELECT * FROM book ORDER BY id")+"\ncomments="+jdbc.queryForList("SELECT * FROM book_comment ORDER BY id")+"\nreplies="+jdbc.queryForList("SELECT * FROM book_comment_reply ORDER BY id"));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CORRECTNESS_MYSQL_PREFIX",matches=".+")
    void innodbBlockedWriterCompletesAfterParentLockReleased() throws Exception {
        var ds=((org.springframework.jdbc.datasource.DriverManagerDataSource)jdbc.getDataSource());
        try(var owner=ds.getConnection();var pool=Executors.newSingleThreadExecutor()){
            owner.setAutoCommit(false);
            try(var statement=owner.createStatement()){statement.executeQuery("SELECT id FROM book WHERE id=1 FOR UPDATE").close();}
            var started=new CountDownLatch(1);long start=System.nanoTime();
            var waiting=pool.submit(()->{started.countDown();service.addBookComment(7L,comment(1));});
            try{assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();Thread.sleep(1500);assertThat(waiting.isDone()).isFalse();}finally{owner.rollback();}
            waiting.get(10,TimeUnit.SECONDS);
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("CORRECTNESS_EVIDENCE"),"controlled-lock-wait.txt"),"writer elapsed ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        }
        assertThat(count("SELECT COUNT(*) FROM book_comment")).isEqualTo(1);
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isZero();
    }

    @Test void pendingCreationDoesNotIncreasePublicCountAndIsPrivate() {
        service.addBookComment(7L,comment(1));
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isZero();
        assertThat(comments.listCommentByPage(null,1L)).isEmpty();
        assertThat(comments.listCommentByPage(7L,null)).hasSize(1);
    }
    @Test void pendingParentRejectsReplyCreation() {
        service.addBookComment(7L,comment(1));long parent=count("SELECT id FROM book_comment");
        assertThatThrownBy(()->service.addBookCommentReply(7L,reply(parent))).isInstanceOf(BusinessException.class);
        assertThat(count("SELECT COUNT(*) FROM book_comment_reply")).isZero();
    }
    @Test void cannotForgeCommentOwnerAuditOrCounters() {
        var c=comment(1);c.setId(999L);c.setCreateUserId(99L);c.setReplyCount(999);c.setAuditStatus((byte)1);
        service.addBookComment(7L,c);
        assertThat(count("SELECT create_user_id FROM book_comment")).isEqualTo(7);
        assertThat(count("SELECT reply_count FROM book_comment")).isZero();
        assertThat(count("SELECT audit_status FROM book_comment")).isZero();
        assertThat(count("SELECT id FROM book_comment")).isNotEqualTo(999);
    }
    @Test void rejectsAnonymousAndNonexistentBook() {
        assertThatThrownBy(()->service.addBookComment(null,comment(1))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.addBookComment(7L,comment(99))).isInstanceOf(BusinessException.class);
        assertThat(count("SELECT COUNT(*) FROM book_comment")).isZero();
    }
    @Test void rejectsBlankAndOversizedContent() {
        var c=comment(1);c.setCommentContent(" ");assertThatThrownBy(()->service.addBookComment(7L,c)).isInstanceOf(BusinessException.class);
        c.setCommentContent("x".repeat(513));assertThatThrownBy(()->service.addBookComment(7L,c)).isInstanceOf(BusinessException.class);
    }
    @Test void concurrentDuplicateReviewHasOneRowAndOneBookIncrement() throws Exception {
        var results=concurrently(12,i->{try{service.addBookComment(7L,comment(1));return "ok";}catch(BusinessException expected){return "duplicate";}});
        assertThat(results.stream().filter("ok"::equals).count()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM book_comment")).isEqualTo(1);
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isZero();
    }
    @Test void concurrentDifferentUsersIncrementExactly() throws Exception {
        concurrently(12,i->{service.addBookComment(100L+i,comment(1));return "ok";});
        assertThat(count("SELECT COUNT(*) FROM book_comment")).isEqualTo(12);
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isZero();
    }
    @Test void repliesValidateParentAndIgnoreForgedOwnerAuditAndId() {
        service.addBookComment(7L,comment(1));long parent=count("SELECT id FROM book_comment");
        jdbc.update("UPDATE book_comment SET audit_status=1");
        var r=reply(parent);r.setCreateUserId(99L);r.setAuditStatus((byte)1);r.setId(999L);
        service.addBookCommentReply(7L,r);
        assertThat(count("SELECT create_user_id FROM book_comment_reply")).isEqualTo(7);
        assertThat(count("SELECT audit_status FROM book_comment_reply")).isZero();
        assertThat(count("SELECT id FROM book_comment_reply")).isNotEqualTo(999);
        assertThatThrownBy(()->service.addBookCommentReply(7L,reply(99))).isInstanceOf(BusinessException.class);
    }
    @Test void concurrentRepliesAndFailedInsertKeepParentCountCorrect() throws Exception {
        service.addBookComment(7L,comment(1));long parent=count("SELECT id FROM book_comment");
        jdbc.update("UPDATE book_comment SET audit_status=1");
        concurrently(12,i->{service.addBookCommentReply(100L+i,reply(parent));return "ok";});
        assertThat(count("SELECT reply_count FROM book_comment")).isZero();
        jdbc.execute("ALTER TABLE book_comment_reply ADD CONSTRAINT fail_test CHECK (create_user_id<>999)");
        assertThatThrownBy(()->service.addBookCommentReply(999L,reply(parent))).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT reply_count FROM book_comment")).isZero();
    }
    @Test void pageFiltersNeverLeakOtherBookOrUserAndTiesAreOrderedById() {
        service.addBookComment(7L,comment(1));
        jdbc.update("INSERT INTO user VALUES(8,'second','photo')");
        jdbc.update("INSERT INTO book(id) VALUES(2)");service.addBookComment(8L,comment(2));service.addBookComment(8L,comment(1));
        jdbc.update("UPDATE book_comment SET audit_status=1");
        jdbc.update("UPDATE book_comment SET create_time='2026-10-17 12:00:00'");
        assertThat(comments.listCommentByPage(null,1L)).extracting(v->v.getBookId()).containsExactly(1L,1L);
        assertThat(comments.listCommentByPage(8L,null)).extracting(v->v.getBookId()).containsExactly(1L,2L);
        assertThat(comments.listCommentByPage(null,1L)).extracting(v->v.getId()).isSortedAccordingTo(Comparator.reverseOrder());
    }
    @Test void failedCommentInsertRollsBackBookCounter() {
        jdbc.execute("ALTER TABLE book_comment ADD CONSTRAINT fail_comment CHECK(create_user_id<>999)");
        assertThatThrownBy(()->service.addBookComment(999L,comment(1))).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isZero();
    }
    @Test void pendingRepliesDoNotChangeExistingApprovedReplyCount() {
        service.addBookComment(7L,comment(1));long parent=count("SELECT id FROM book_comment");
        jdbc.update("UPDATE book_comment SET audit_status=1,reply_count=5");
        service.addBookCommentReply(7L,reply(parent));
        assertThat(count("SELECT reply_count FROM book_comment")).isEqualTo(5);
    }
    @Test void publicAndOwnerPaginationUseDifferentTotalsAndReturnVersion() {
        service.addBookComment(7L,comment(1));service.addBookComment(8L,comment(1));service.addBookComment(9L,comment(1));
        jdbc.update("UPDATE book_comment SET audit_status=1 WHERE create_user_id=8");
        jdbc.update("UPDATE book_comment SET audit_status=2 WHERE create_user_id=9");
        var visible=service.listCommentByPage(null,1L,1,1);
        assertThat(visible.getTotal()).isEqualTo(1);assertThat(visible.getList()).hasSize(1);
        assertThat(visible.getList().get(0).getAuditStatus()).isEqualTo((byte)1);
        var own=service.listCommentByPage(7L,null,1,1);
        assertThat(own.getTotal()).isEqualTo(1);assertThat(own.getList().get(0).getAuditStatus()).isZero();
        assertThat(own.getList().get(0).getModerationVersion()).isZero();
    }
    @Test void publicRepliesRequireApprovedParentButOwnerCanSeeAllStates() {
        service.addBookComment(7L,comment(1));long parent=count("SELECT id FROM book_comment");
        jdbc.update("UPDATE book_comment SET audit_status=1");
        service.addBookCommentReply(7L,reply(parent));service.addBookCommentReply(8L,reply(parent));
        jdbc.update("UPDATE book_comment_reply SET audit_status=1 WHERE create_user_id=7");
        assertThat(service.listCommentReplyByPage(null,parent,1,10).getTotal()).isEqualTo(1);
        jdbc.update("UPDATE book_comment SET audit_status=2");
        assertThat(service.listCommentReplyByPage(null,parent,1,10).getTotal()).isZero();
        var own=service.listCommentReplyByPage(7L,null,1,10);
        assertThat(own.getTotal()).isEqualTo(1);assertThat(own.getList().get(0).getParentAuditStatus()).isEqualTo((byte)2);
        assertThat(service.getBookComment(parent)).isNull();assertThat(service.getBookComment(999L)).isNull();
    }
    List<String> concurrently(int n,java.util.function.IntFunction<String> task) throws Exception {
        try(var pool=Executors.newFixedThreadPool(6)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<String>>();
            for(int i=0;i<n;i++){int id=i;futures.add(pool.submit(()->{start.await();return task.apply(id);}));}
            start.countDown();var result=new ArrayList<String>();for(var f:futures)result.add(f.get(20,TimeUnit.SECONDS));return result;
        }
    }
}
