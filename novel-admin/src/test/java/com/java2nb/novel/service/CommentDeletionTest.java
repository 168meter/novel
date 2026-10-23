package com.java2nb.novel.service;

import com.java2nb.novel.dao.*;
import com.java2nb.novel.service.impl.BookCommentServiceImpl;
import com.java2nb.novel.controller.BookCommentController;
import java.util.*;
import org.junit.jupiter.api.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommentDeletionTest {
    JdbcTemplate jdbc; BookCommentService service;
    BookCommentDao dao;
    BookCommentServiceImpl target;
    @BeforeEach void setup(TestInfo info) throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        if(System.getenv("CORRECTNESS_MYSQL_PREFIX")!=null && !System.getenv("CORRECTNESS_MYSQL_PREFIX").isBlank()){
            String prefix=System.getenv("CORRECTNESS_MYSQL_PREFIX");
            if(!prefix.matches("cv_it_[a-z0-9_]{1,25}")){throw new IllegalArgumentException("Unsafe test schema prefix");}
            String schema=prefix+"_ma_"+Long.toUnsignedString(System.nanoTime(),36);
            String base="jdbc:mysql://127.0.0.1:23307/",options="?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
            String user=System.getenv("CORRECTNESS_MYSQL_USER"),password=System.getenv("CORRECTNESS_MYSQL_PASSWORD");
            new JdbcTemplate(new DriverManagerDataSource(base+options,user,password)).execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            ds=new DriverManagerDataSource(base+schema+options,user,password);
            assertThat(new JdbcTemplate(ds).queryForObject("SELECT @@default_storage_engine",String.class)).isEqualToIgnoringCase("InnoDB");
        }
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE book(id BIGINT PRIMARY KEY,comment_count INT)");
        jdbc.execute("CREATE TABLE book_comment(id BIGINT AUTO_INCREMENT PRIMARY KEY,book_id BIGINT,comment_content VARCHAR(512),reply_count INT,audit_status TINYINT DEFAULT 1,moderation_version BIGINT NOT NULL DEFAULT 0,location VARCHAR(50),create_time TIMESTAMP,create_user_id BIGINT)");
        jdbc.execute("CREATE TABLE book_comment_reply(id BIGINT AUTO_INCREMENT PRIMARY KEY,comment_id BIGINT,reply_content VARCHAR(512),location VARCHAR(50),audit_status TINYINT DEFAULT 0,moderation_version BIGINT NOT NULL DEFAULT 0,create_time TIMESTAMP,create_user_id BIGINT)");
        jdbc.update("INSERT INTO book VALUES(1,2)");
        jdbc.update("INSERT INTO book_comment(id,book_id) VALUES(10,1),(11,1)");
        jdbc.update("INSERT INTO book_comment_reply(id,comment_id) VALUES(20,10),(21,11)");
        var cfg=new Configuration(new Environment("test",new SpringManagedTransactionFactory(),ds));cfg.setMapUnderscoreToCamelCase(true);
        String path="mybatis/novel/BookCommentMapper.xml";
        try(var in=getClass().getClassLoader().getResourceAsStream(path)){new XMLMapperBuilder(in,cfg,path,cfg.getSqlFragments()).parse();}
        cfg.addMapper(CommentModerationDao.class);
        var sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
        dao=sessions.getMapper(BookCommentDao.class);
        var moderationProxy=new ProxyFactory(new com.java2nb.novel.comment.CommentModerationService(sessions.getMapper(CommentModerationDao.class)));
        moderationProxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        var moderation=(com.java2nb.novel.comment.CommentModerationService)moderationProxy.getProxy();
        target=new BookCommentServiceImpl();ReflectionTestUtils.setField(target,"moderation",moderation);ReflectionTestUtils.setField(target,"bookCommentDao",dao);
        ReflectionTestUtils.setField(target,"bookDao",mock(BookDao.class));ReflectionTestUtils.setField(target,"userDao",mock(UserDao.class));
        var proxy=new ProxyFactory(target);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));service=(BookCommentService)proxy.getProxy();
    }
    long count(String sql){return jdbc.queryForObject(sql,Long.class);}
    @AfterEach void saveMysqlEvidence(TestInfo info) throws Exception {
        if((System.getenv("CORRECTNESS_MYSQL_PREFIX")==null || System.getenv("CORRECTNESS_MYSQL_PREFIX").isBlank()) || jdbc==null){return;}
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("CORRECTNESS_EVIDENCE"),"mysql-admin-"+info.getTestMethod().orElseThrow().getName()+".txt"),"schema="+jdbc.queryForObject("SELECT DATABASE()",String.class)+"\nbook="+jdbc.queryForList("SELECT * FROM book ORDER BY id")+"\ncomments="+jdbc.queryForList("SELECT * FROM book_comment ORDER BY id")+"\nreplies="+jdbc.queryForList("SELECT * FROM book_comment_reply ORDER BY id"));
    }
    @Test void deletingTwiceRemovesRepliesAndDecrementsBookOnlyOnce(){
        assertThat(service.remove(10L)).isEqualTo(1);assertThat(service.remove(10L)).isZero();
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM book_comment_reply WHERE comment_id=10")).isZero();
    }
    @Test void batchUsesSameCleanupAndIgnoresDuplicateIds(){
        service.batchRemove(new Long[]{10L,10L,11L});
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM book_comment_reply")).isZero();
    }
    @Test void parentCountFailureRollsBackCommentAndReplies(){
        jdbc.execute("ALTER TABLE book ADD CONSTRAINT fail_count CHECK(comment_count=2)");
        assertThatThrownBy(()->service.remove(10L)).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT COUNT(*) FROM book_comment WHERE id=10")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM book_comment_reply WHERE comment_id=10")).isEqualTo(1);
    }
    @Test void deleteRoutesRequireDistinctAdminPermissions() throws Exception {
        assertThat(BookCommentController.class.getMethod("remove",Long.class).getAnnotation(RequiresPermissions.class).value()).containsExactly("novel:bookComment:remove");
        assertThat(BookCommentController.class.getMethod("remove",Long[].class).getAnnotation(RequiresPermissions.class).value()).containsExactly("novel:bookComment:batchRemove");
    }
    @Test void auditAndReplyRoutesUseExistingPermissions() throws Exception {
        for(String name:List.of("audit","replyAudit")) assertThat(BookCommentController.class.getMethod(name,Long.class,Long.class,int.class).getAnnotation(RequiresPermissions.class).value()).containsExactly("novel:bookComment:edit");
        assertThat(BookCommentController.class.getMethod("replyRemove",Long.class).getAnnotation(RequiresPermissions.class).value()).containsExactly("novel:bookComment:remove");
    }
    @Test void generalEditCannotActAsAnAuditRouteAndStaleEditIsRejected() {
        var c=new com.java2nb.novel.domain.BookCommentDO();c.setId(10L);c.setAuditStatus(2);c.setModerationVersion(0L);
        assertThatThrownBy(()->service.update(c)).isInstanceOf(com.java2nb.novel.comment.ModerationException.class);
        c.setAuditStatus(null);c.setCommentContent("edited");c.setModerationVersion(9L);
        assertThatThrownBy(()->service.update(c)).isInstanceOf(com.java2nb.novel.comment.ModerationException.class);
        assertThat(count("SELECT audit_status FROM book_comment WHERE id=10")).isEqualTo(1);
    }
    @Test void adminCreationIncrementsCountAndCannotInjectReplyCount() {
        var c=new com.java2nb.novel.domain.BookCommentDO();c.setBookId(1L);c.setCreateUserId(7L);c.setCommentContent("review");c.setReplyCount(999);
        service.save(c);
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isEqualTo(2);
        assertThat(count("SELECT reply_count FROM book_comment WHERE create_user_id=7")).isZero();
    }
    @Test void adminEditCannotReparentOrForgeReplyCounter() {
        jdbc.update("UPDATE book_comment SET create_user_id=7,reply_count=1 WHERE id=10");
        var c=new com.java2nb.novel.domain.BookCommentDO();c.setId(10L);c.setModerationVersion(0L);c.setBookId(99L);c.setCreateUserId(99L);c.setReplyCount(999);c.setCommentContent("edited");
        service.update(c);
        assertThat(count("SELECT book_id FROM book_comment WHERE id=10")).isEqualTo(1);
        assertThat(count("SELECT create_user_id FROM book_comment WHERE id=10")).isEqualTo(7);
        assertThat(count("SELECT reply_count FROM book_comment WHERE id=10")).isEqualTo(1);
    }
    @Test void editWithOnlyProtectedFieldsIsANoop() {
        var c=new com.java2nb.novel.domain.BookCommentDO();c.setId(10L);c.setBookId(99L);c.setReplyCount(999);
        assertThat(service.update(c)).isZero();
        assertThat(count("SELECT book_id FROM book_comment WHERE id=10")).isEqualTo(1);
    }
    @Test void replyCounterIsNotOverwrittenByVersionedAdminEdit() {
        jdbc.update("UPDATE book_comment SET reply_count=2 WHERE id=10");
        var edit=new com.java2nb.novel.domain.BookCommentDO();edit.setId(10L);edit.setModerationVersion(0L);edit.setCommentContent("edited");
        service.update(edit);
        assertThat(count("SELECT reply_count FROM book_comment WHERE id=10")).isEqualTo(2);
        assertThat(count("SELECT audit_status FROM book_comment WHERE id=10")).isZero();
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isEqualTo(1);
    }
}
