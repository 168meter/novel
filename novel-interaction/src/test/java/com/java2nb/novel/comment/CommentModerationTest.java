package com.java2nb.novel.comment;



import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class CommentModerationTest {
    JdbcTemplate jdbc; CommentModerationService service; CommentModerationMapper mapper;
    @BeforeEach void setup(TestInfo info) throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        if(System.getenv("CORRECTNESS_MYSQL_PREFIX")!=null && !System.getenv("CORRECTNESS_MYSQL_PREFIX").isBlank()) {
            String prefix=System.getenv("CORRECTNESS_MYSQL_PREFIX");
            if(!prefix.matches("cv_it_[a-z0-9_]{1,25}")) throw new IllegalArgumentException("Unsafe schema prefix");
            String schema=prefix+"_mod_"+Long.toUnsignedString(System.nanoTime(),36);
            String base="jdbc:mysql://127.0.0.1:23307/",options="?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
            String user=System.getenv("CORRECTNESS_MYSQL_USER"),password=System.getenv("CORRECTNESS_MYSQL_PASSWORD");
            new JdbcTemplate(new DriverManagerDataSource(base+options,user,password)).execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            ds=new DriverManagerDataSource(base+schema+options,user,password);
            var real=new JdbcTemplate(ds);
            assertThat(real.queryForObject("SELECT VERSION()",String.class)).startsWith("8.");
            assertThat(real.queryForObject("SELECT @@default_storage_engine",String.class)).isEqualToIgnoringCase("InnoDB");
        }
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE book(id BIGINT PRIMARY KEY,comment_count INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE book_comment(id BIGINT AUTO_INCREMENT PRIMARY KEY,book_id BIGINT NOT NULL,comment_content VARCHAR(512),location VARCHAR(50),reply_count INT DEFAULT 0,audit_status TINYINT NOT NULL DEFAULT 0,create_time TIMESTAMP,create_user_id BIGINT NOT NULL,moderation_version BIGINT NOT NULL DEFAULT 0,UNIQUE(book_id,create_user_id))");
        jdbc.execute("CREATE TABLE book_comment_reply(id BIGINT AUTO_INCREMENT PRIMARY KEY,comment_id BIGINT NOT NULL,reply_content VARCHAR(512),location VARCHAR(50),audit_status TINYINT NOT NULL DEFAULT 0,create_time TIMESTAMP,create_user_id BIGINT NOT NULL,moderation_version BIGINT NOT NULL DEFAULT 0)");
        jdbc.update("INSERT INTO book VALUES(1,0),(2,0)");
        var cfg=new Configuration(new Environment("test",new SpringManagedTransactionFactory(),ds));
        cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(CommentModerationMapper.class);
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg)).getMapper(CommentModerationMapper.class);
        var proxy=new ProxyFactory(new CommentModerationService(mapper));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(CommentModerationService)proxy.getProxy();
    }
    long count(String sql) {return jdbc.queryForObject(sql,Long.class);}
    CommentRecord comment() {return service.createComment(7L,1L,"review",null);}
    CommentRecord published() {var c=comment();return service.auditComment(c.getId(),c.getModerationVersion(),1);}
    ReplyRecord reply(CommentRecord c) {return service.createReply(8L,c.getId(),"reply",null);}
    @AfterEach void evidence(TestInfo info) throws Exception {
        if((System.getenv("CORRECTNESS_MYSQL_PREFIX")==null || System.getenv("CORRECTNESS_MYSQL_PREFIX").isBlank()) || jdbc==null) return;
        Path dir=Path.of(System.getenv("CORRECTNESS_EVIDENCE"));Files.createDirectories(dir);
        String schema=jdbc.queryForObject("SELECT DATABASE()",String.class);
        Files.writeString(dir.resolve("mysql-"+schema+".txt"),"test="+info.getDisplayName()+"\nschema="+schema+"\nbook="+jdbc.queryForList("SELECT * FROM book ORDER BY id")+"\ncomments="+jdbc.queryForList("SELECT * FROM book_comment ORDER BY id")+"\nreplies="+jdbc.queryForList("SELECT * FROM book_comment_reply ORDER BY id"));
    }
    @Test void createsPendingWithoutPublicCounters() {
        var c=comment();assertThat(c.getAuditStatus()).isZero();assertThat(c.getModerationVersion()).isZero();
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isZero();
        assertThat(service.publicComment(c.getId())).isNull();
        assertThatThrownBy(()->reply(c)).isInstanceOf(ModerationException.class);
    }
    @ParameterizedTest @CsvSource({"0,0,0","0,1,1","0,2,0","1,0,0","1,1,1","1,2,0","2,0,0","2,1,1","2,2,0"})
    void commentTransitionMatrix(int before,int after,int expected) {
        var c=comment();jdbc.update("UPDATE book_comment SET audit_status=? WHERE id=?",before,c.getId());
        jdbc.update("UPDATE book SET comment_count=? WHERE id=1",before==1?1:0);
        var result=service.auditComment(c.getId(),0L,after);
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isEqualTo(expected);
        service.auditComment(c.getId(),result.getModerationVersion(),after);
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isEqualTo(expected);
    }
    @ParameterizedTest @CsvSource({"0,0,0","0,1,1","0,2,0","1,0,0","1,1,1","1,2,0","2,0,0","2,1,1","2,2,0"})
    void replyTransitionMatrix(int before,int after,int expected) {
        var c=published();var r=reply(c);jdbc.update("UPDATE book_comment_reply SET audit_status=? WHERE id=?",before,r.getId());
        jdbc.update("UPDATE book_comment SET reply_count=? WHERE id=?",before==1?1:0,c.getId());
        var result=service.auditReply(r.getId(),0L,after);service.auditReply(r.getId(),result.getModerationVersion(),after);
        assertThat(count("SELECT reply_count FROM book_comment WHERE id="+c.getId())).isEqualTo(expected);
    }
    @Test void hidingParentPreservesApprovedReplyCountAndStates() {
        var c=published();var r=reply(c);service.auditReply(r.getId(),0L,1);
        var hidden=service.auditComment(c.getId(),c.getModerationVersion(),2);
        assertThat(mapper.findComment(c.getId()).getReplyCount()).isEqualTo(1);
        assertThat(mapper.findReply(r.getId()).getAuditStatus()).isEqualTo((byte)1);
        assertThat(service.publicComment(c.getId())).isNull();
        service.auditComment(c.getId(),hidden.getModerationVersion(),1);
        assertThat(service.publicComment(c.getId()).getReplyCount()).isEqualTo(1);
    }
    @Test void ownerEditUnpublishesAndStaleApprovalCannotApproveChangedBody() {
        var c=published();var edited=service.editComment(c.getId(),7L,c.getModerationVersion(),"edited");
        assertThat(edited.getAuditStatus()).isZero();assertThat(count("SELECT comment_count FROM book WHERE id=1")).isZero();
        assertThatThrownBy(()->service.auditComment(c.getId(),c.getModerationVersion(),1)).isInstanceOf(ModerationException.class);
        assertThat(mapper.findComment(c.getId()).getCommentContent()).isEqualTo("edited");
        assertThatThrownBy(()->service.editComment(c.getId(),8L,edited.getModerationVersion(),"forged")).isInstanceOf(ModerationException.class);
    }
    @Test void rejectedOwnerResubmitsSameRowAndUniqueConstraintSurvives() {
        var c=comment();var rejected=service.auditComment(c.getId(),0L,2);
        var edited=service.editComment(c.getId(),7L,rejected.getModerationVersion(),"revised");
        assertThat(edited.getId()).isEqualTo(c.getId());assertThat(edited.getAuditStatus()).isZero();
        assertThatThrownBy(this::comment).isInstanceOf(ModerationException.class);
        assertThat(count("SELECT COUNT(*) FROM book_comment")).isEqualTo(1);
    }
    @Test void replyEditUnpublishesEvenWhenParentHidden() {
        var c=published();var r=service.auditReply(reply(c).getId(),0L,1);
        service.auditComment(c.getId(),c.getModerationVersion(),2);
        var edited=service.editReply(r.getId(),8L,r.getModerationVersion(),"edited reply");
        assertThat(edited.getAuditStatus()).isZero();assertThat(mapper.findComment(c.getId()).getReplyCount()).isZero();
        assertThatThrownBy(()->service.auditReply(r.getId(),r.getModerationVersion(),1)).isInstanceOf(ModerationException.class);
    }
    @Test void deleteUsesOldStateAndIsRepeatable() {
        var c=published();var r=service.auditReply(reply(c).getId(),0L,1);
        assertThat(service.removeReply(r.getId())).isEqualTo(1);assertThat(service.removeReply(r.getId())).isZero();
        assertThat(mapper.findComment(c.getId()).getReplyCount()).isZero();
        assertThat(service.removeComment(c.getId())).isEqualTo(1);assertThat(service.removeComment(c.getId())).isZero();
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isZero();
        var pending=comment();service.removeComment(pending.getId());assertThat(count("SELECT comment_count FROM book WHERE id=1")).isZero();
    }
    @Test void auditCounterFailureRollsBackStateAndVersion() {
        var c=comment();jdbc.execute("ALTER TABLE book ADD CONSTRAINT fail_count CHECK(comment_count=0)");
        assertThatThrownBy(()->service.auditComment(c.getId(),0L,1)).isInstanceOf(RuntimeException.class);
        assertThat(mapper.findComment(c.getId()).getAuditStatus()).isZero();assertThat(mapper.findComment(c.getId()).getModerationVersion()).isZero();
    }
    @Test void editCounterFailureRollsBackBodyAndVersion() {
        var c=published();jdbc.execute("ALTER TABLE book ADD CONSTRAINT fail_count CHECK(id<>1 OR comment_count=1)");
        assertThatThrownBy(()->service.editComment(c.getId(),7L,c.getModerationVersion(),"edited")).isInstanceOf(RuntimeException.class);
        assertThat(mapper.findComment(c.getId()).getCommentContent()).isEqualTo("review");
        assertThat(mapper.findComment(c.getId()).getModerationVersion()).isEqualTo(c.getModerationVersion());
    }
    @Test void deleteCounterFailureRollsBackChildren() {
        var c=published();var r=reply(c);jdbc.execute("ALTER TABLE book ADD CONSTRAINT fail_count CHECK(id<>1 OR comment_count=1)");
        assertThatThrownBy(()->service.removeComment(c.getId())).isInstanceOf(RuntimeException.class);
        assertThat(mapper.findComment(c.getId())).isNotNull();assertThat(mapper.findReply(r.getId())).isNotNull();
    }
    @Test void concurrentApprovalHasOnlyOneIncrement() throws Exception {
        var c=comment();var outcomes=concurrent(12,i->{try{service.auditComment(c.getId(),0L,1);return "ok";}catch(ModerationException e){return "stale";}});
        assertThat(outcomes).contains("ok");assertThat(count("SELECT comment_count FROM book WHERE id=1")).isEqualTo(1);
        assertThat(mapper.findComment(c.getId()).getModerationVersion()).isEqualTo(1L);
    }
    @Test void concurrentRepliesArePendingAndConcurrentReplyApprovalCountsOnce() throws Exception {
        var c=published();concurrent(12,i->{service.createReply(100L+i,c.getId(),"reply",null);return "ok";});
        assertThat(count("SELECT COUNT(*) FROM book_comment_reply")).isEqualTo(12);assertThat(mapper.findComment(c.getId()).getReplyCount()).isZero();
        long id=count("SELECT MIN(id) FROM book_comment_reply");concurrent(12,i->{try{service.auditReply(id,0L,1);}catch(ModerationException e){}return "ok";});
        assertThat(mapper.findComment(c.getId()).getReplyCount()).isEqualTo(1);
    }
    @Test void concurrentDeleteAndReplyCannotLeaveOrphans() throws Exception {
        var c=published();concurrent(12,i->{if(i==0)service.removeComment(c.getId());else try{service.createReply(100L+i,c.getId(),"reply",null);}catch(ModerationException e){}return "ok";});
        assertThat(count("SELECT COUNT(*) FROM book_comment_reply")).isZero();assertThat(count("SELECT comment_count FROM book WHERE id=1")).isZero();
    }
    @Test void concurrentEditAndAuditNeverPublishesNewBodyUnderOldVersion() throws Exception {
        var c=comment();concurrent(2,i->{try{if(i==0)service.editComment(c.getId(),7L,0L,"new body");else service.auditComment(c.getId(),0L,1);}catch(ModerationException e){}return "ok";});
        var current=mapper.findComment(c.getId());
        if(current.getCommentContent().equals("new body")) assertThat(current.getAuditStatus()).isZero();
        else assertThat(current.getAuditStatus()).isEqualTo((byte)1);
    }
    @Test void batchDeleteDuplicateIdsAcrossBooksCountsOnce() {
        var a=published();var b=service.createComment(8L,2L,"second",null);service.auditComment(b.getId(),0L,1);
        service.batchRemove(new Long[]{b.getId(),a.getId(),a.getId()});
        assertThat(count("SELECT SUM(comment_count) FROM book")).isZero();assertThat(count("SELECT COUNT(*) FROM book_comment")).isZero();
    }
    @Test void invalidStatusAndMissingVersionCannotMutate() {
        var c=comment();assertThatThrownBy(()->service.auditComment(c.getId(),0L,3)).isInstanceOf(ModerationException.class);
        assertThatThrownBy(()->service.auditComment(c.getId(),null,1)).isInstanceOf(ModerationException.class);
        assertThat(mapper.findComment(c.getId()).getAuditStatus()).isZero();
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CORRECTNESS_MYSQL_PREFIX",matches=".+")
    void innodbAuditWaitsForBookLockAndCountsAfterRelease() throws Exception {
        var c=comment();var ds=jdbc.getDataSource();
        try(var owner=ds.getConnection();var pool=Executors.newSingleThreadExecutor()) {
            owner.setAutoCommit(false);try(var st=owner.createStatement()){st.executeQuery("SELECT id FROM book WHERE id=1 FOR UPDATE").close();}
            var entered=new CountDownLatch(1);long started=System.nanoTime();
            var f=pool.submit(()->{entered.countDown();service.auditComment(c.getId(),0L,1);});
            try {assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();Thread.sleep(600);assertThat(f.isDone()).isFalse();}finally{owner.rollback();}
            f.get(10,TimeUnit.SECONDS);long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            assertThat(elapsed).isGreaterThanOrEqualTo(600);
            Files.writeString(Path.of(System.getenv("CORRECTNESS_EVIDENCE"),"controlled-moderation-lock-wait.txt"),"elapsedMs="+elapsed);
        }
        assertThat(count("SELECT comment_count FROM book WHERE id=1")).isEqualTo(1);
    }
    List<String> concurrent(int n,java.util.function.IntFunction<String> task) throws Exception {
        try(var pool=Executors.newFixedThreadPool(6)){var start=new CountDownLatch(1);var futures=new ArrayList<Future<String>>();
            for(int i=0;i<n;i++){int k=i;futures.add(pool.submit(()->{start.await();return task.apply(k);}));}start.countDown();
            var out=new ArrayList<String>();for(var f:futures)out.add(f.get(20,TimeUnit.SECONDS));return out;}
    }
}
