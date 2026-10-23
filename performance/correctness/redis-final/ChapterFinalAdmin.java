package com.java2nb.novel.acceptance;

import com.java2nb.common.exception.CommonExceptionHandler;
import com.java2nb.novel.cache.AdminChapterCacheInvalidator;
import com.java2nb.novel.controller.*;
import com.java2nb.novel.dao.*;
import com.java2nb.novel.domain.*;
import com.java2nb.novel.service.impl.*;
import java.util.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static com.java2nb.novel.acceptance.ChapterFinalSupport.*;

/** Real Admin services/XML/Spring transactions. MVC is in-process; no claim of deployed Shiro. */
public final class ChapterFinalAdmin {
    final ChapterFinalSupport s;
    BookContentServiceImpl bodies;BookIndexServiceImpl indexes;
    public ChapterFinalAdmin(ChapterFinalSupport support) throws Exception {
        s=support;
        var sessions=s.sessions(new String[]{"mybatis/novel/BookMapper.xml","mybatis/novel/BookIndexMapper.xml","mybatis/novel/BookContentMapper.xml"});
        var invalidator=new AdminChapterCacheInvalidator(s.redis,s.metrics);
        indexes=s.bean(BookIndexServiceImpl.class,new BookIndexServiceImpl(s.mapper(sessions,BookIndexDao.class),invalidator,s.mapper(sessions,BookDao.class)));
        bodies=s.bean(BookContentServiceImpl.class,new BookContentServiceImpl(s.mapper(sessions,BookContentDao.class),s.mapper(sessions,BookIndexDao.class),invalidator));
    }
    public static void main(String[] args) throws Exception {
        if(args.length==1 && args[0].equals("probe")){ChapterFinalSupport.probe();return;}
        check(args.length==1,"Specify one phase");
        try(var s=new ChapterFinalSupport(args[0].equals("setup"))) {
            if(args[0].equals("setup")){setup(s);return;}
            var a=new ChapterFinalAdmin(s);
            switch(args[0]) {
                case "A" -> a.update("A",1,"new",false);
                case "B" -> a.update("B",2,"rolled-back",true);
                case "C" -> {
                    check(!Boolean.TRUE.equals(s.redis.hasKey(s.key(3))),"Race must not start with cached body");
                    check(Boolean.TRUE.equals(s.redis.hasKey(s.lock(3))),"Race token already expired before update");
                    a.update("C",3,"new",false);
                    check(s.redis.deletes.size()==1,"Expected one actual production DEL");
                    var deletion=s.redis.deletes.get(0);
                    check(Long.valueOf(1).equals(deletion.get("removed")),"Token expired or was not actually revoked by DEL");
                    check(new HashSet<>((List<?>)deletion.get("keys")).equals(Set.of(s.key(3),s.lock(3))),"Unexpected DEL key scope");
                    s.signal("C-committed.signal");
                }
                case "D" -> a.identities();
                case "create" -> a.create();
                default -> throw new IllegalArgumentException("Unknown phase");
            }
        }
    }
    static void setup(ChapterFinalSupport s) throws Exception {
        var server=new org.springframework.jdbc.core.JdbcTemplate(s.dataSource(""));server.setQueryTimeout(5);
        check(server.queryForObject("SELECT VERSION()",String.class).startsWith("8."),"Real MySQL 8 required");
        check(server.queryForObject("SELECT CURRENT_USER()",String.class).startsWith(env("RCF_USER")+"@"),"MySQL account mismatch");
        check(server.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,s.schema)==0,"Schema already exists");
        for(int n=1;n<=8;n++){check(!Boolean.TRUE.equals(s.redis.hasKey(s.key(n))),"Preexisting content key");check(!Boolean.TRUE.equals(s.redis.hasKey(s.lock(n))),"Preexisting token key");}
        s.save("ownership.json",Map.of("run",s.run,"schema",s.schema,"bookId",s.book,"keysInitiallyAbsent",true));
        server.execute("CREATE DATABASE `"+s.schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        s.jdbc.execute("CREATE TABLE chapter_final_owner(run_id VARCHAR(64) PRIMARY KEY) ENGINE=InnoDB");s.jdbc.update("INSERT INTO chapter_final_owner VALUES(?)",s.run);
        s.jdbc.execute("CREATE TABLE book(id BIGINT PRIMARY KEY,work_direction TINYINT,cat_id INT,cat_name VARCHAR(100),pic_url VARCHAR(255),book_name VARCHAR(100),author_id BIGINT,author_name VARCHAR(100),book_desc TEXT,score FLOAT,book_status TINYINT,visit_count BIGINT DEFAULT 0,word_count INT DEFAULT 0,comment_count INT DEFAULT 0,yesterday_buy INT,last_index_id BIGINT,last_index_name VARCHAR(100),last_index_update_time DATETIME,is_vip TINYINT,status TINYINT,update_time DATETIME,create_time DATETIME,crawl_source_id INT,crawl_book_id VARCHAR(100),crawl_last_time DATETIME,crawl_is_stop TINYINT) ENGINE=InnoDB");
        s.jdbc.execute("CREATE TABLE book_index(id BIGINT PRIMARY KEY,book_id BIGINT,index_num INT,index_name VARCHAR(100),word_count INT,is_vip TINYINT,book_price INT,storage_type VARCHAR(10) DEFAULT 'db',create_time DATETIME,update_time DATETIME) ENGINE=InnoDB");
        s.jdbc.execute("CREATE TABLE book_content(id BIGINT PRIMARY KEY,index_id BIGINT UNIQUE,content LONGTEXT) ENGINE=InnoDB");
        s.jdbc.update("INSERT INTO book(id,book_name,author_id,word_count,visit_count) VALUES(?,'acceptance',?,30,0),(?,'second',?,0,0)",s.book,s.book+9,s.book+1,s.book+9);
        for(int n=1;n<=6;n++){
            s.jdbc.update("INSERT INTO book_index(id,book_id,index_num,index_name,word_count,is_vip,book_price,storage_type) VALUES(?,?,?,'old title',3,0,0,'db')",s.index(n),s.book,n);
            s.jdbc.update("INSERT INTO book_content VALUES(?,?,'old')",s.body(n),s.index(n));
        }
        try(var c=s.factory.getConnection()){
            s.save("versions.json",Map.of("mysql",s.jdbc.queryForMap("SELECT VERSION() AS version,@@version_comment AS edition,@@server_uuid AS server_uuid"),"redis",c.serverCommands().info("server").getProperty("redis_version"),"java",System.getProperty("java.runtime.version"),"mysqlEndpoint","127.0.0.1:23307","redisEndpoint","127.0.0.1:26381"));
        }
        var ddl=new ArrayList<Object>();for(String t:List.of("book","book_index","book_content"))ddl.add(s.jdbc.queryForList("SHOW CREATE TABLE "+t));
        s.save("schema.json",ddl);s.pass("setup",Map.of("engine",s.jdbc.queryForList("SELECT table_name,engine FROM information_schema.tables WHERE table_schema=?",s.schema)));
    }
    BookContentDO patch(int slot,String text){var p=new BookContentDO();p.setId(s.body(slot));p.setContent(text);return p;}
    double evictions(){return s.metrics.counter("novel.chapter.cache.eviction","source","admin","result","success").count();}
    void update(String name,int slot,String text,boolean rollback) throws Exception {
        s.save(name+"-before.json",s.snapshot(slot));String cached=s.redis.opsForValue().get(s.key(slot));double before=evictions();
        if(rollback) {
            check(cached!=null && cached.contains("old"),"Rollback baseline must already be cached");
            new TransactionTemplate(s.tx).execute(status->{
                check(TransactionSynchronizationManager.isActualTransactionActive(),"Actual transaction required");
                check(bodies.update(patch(slot,text))==1,"Service update failed");
                check(text.equals(s.bodyText(slot)),"Update not visible inside transaction");
                check(evictions()==before,"Invalidated before commit");
                check(cached.equals(s.redis.opsForValue().get(s.key(slot))),"Cache changed before rollback");
                try{s.save("B-inside-transaction.json",s.snapshot(slot));}catch(Exception e){throw new IllegalStateException(e);}
                status.setRollbackOnly();return null;
            });
            check("old".equals(s.bodyText(slot)),"MySQL rollback failed");
            check(cached.equals(s.redis.opsForValue().get(s.key(slot))),"Rollback evicted original cache");
            check(evictions()==before,"Rollback triggered afterCommit eviction");
            check(s.redis.deletes.isEmpty(),"Rollback executed a Redis DEL");
        } else {
            check(bodies.update(patch(slot,text))==1,"Service update failed");
            check(text.equals(s.bodyText(slot)),"Committed MySQL text incorrect");
            check(evictions()==before+1,"Production invalidator did not complete successfully");
            check(!Boolean.TRUE.equals(s.redis.hasKey(s.key(slot))) && !Boolean.TRUE.equals(s.redis.hasKey(s.lock(slot))),"Committed update left cache/token");
        }
        s.save(name+"-after.json",s.snapshot(slot));s.save(name+"-production-del.json",s.redis.deletes);
        s.pass(name+"-write",Map.of("rolledBack",rollback,"evictionDelta",evictions()-before));
    }
    void identities() throws Exception {
        var ic=new BookIndexController();var bc=new BookContentController();
        ReflectionTestUtils.setField(ic,"bookIndexService",indexes);ReflectionTestUtils.setField(bc,"bookContentService",bodies);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(ic,bc).defaultRequest(get("/").accept("application/json")).setControllerAdvice(new CommonExceptionHandler()).build();
        var evidence=new ArrayList<Object>();
        for(String field:List.of("bookId","storageType","indexId")) {
            var before=s.snapshot(5).get("mysql");
            var request=post(field.equals("indexId")?"/novel/bookContent/update":"/novel/bookIndex/update")
                .param("id",Long.toString(field.equals("indexId")?s.body(5):s.index(5)))
                .param(field,field.equals("bookId")?Long.toString(s.book+1):field.equals("indexId")?Long.toString(s.index(6)):"txt")
                .param("indexName","must-not-write").param("content","must-not-write");
            var response=mvc.perform(request).andReturn().getResponse();
            check(response.getStatus()==200 && JSON.readTree(response.getContentAsString()).path("code").asInt()==400,"Explicit HTTP200/business400 required");
            check(before.equals(s.snapshot(5).get("mysql")),"Rejected identity request changed MySQL");
            evidence.add(Map.of("field",field,"http",response.getStatus(),"body",response.getContentAsString(),"mysqlUnchanged",true));
        }
        for(boolean same:List.of(false,true)) {
            var p=new BookIndexDO();p.setId(s.index(5));p.setIndexName(same?"same identity":"omitted identity");
            if(same){p.setBookId(s.book);p.setStorageType("db");}check(indexes.update(p)==1,"Normal title edit failed");
            var b=patch(5,same?"same body":"omitted body");if(same)b.setIndexId(s.index(5));
            check(bodies.update(b)==1,"Normal body edit failed");
        }
        check("same body".equals(s.bodyText(5)),"Normal content result incorrect");s.save("D-http.json",evidence);
        s.save("D-after.json",s.snapshot(5));s.pass("D",Map.of("rejections",3,"normalEdits",4,"httpScope","in-process MVC, real services/MySQL; not deployed Shiro"));
    }
    void create() throws Exception {
        for(int n:List.of(7,8)){
            var p=new BookIndexDO();p.setId(s.index(n));p.setBookId(s.book);p.setIndexNum(n);p.setIndexName("created");p.setStorageType(n==7?"db":"txt");
            check(indexes.save(p)==1,"Create chapter failed");
        }
        var b=new BookContentDO();b.setId(s.body(7));b.setIndexId(s.index(7));b.setContent("created db");check(bodies.save(b)==1,"Create body failed");
        var p=new BookIndexDO();p.setId(s.index(7));p.setIndexName("edited after create");check(indexes.update(p)==1,"Edit created chapter failed");
        b.setContent("edited after create");check(bodies.update(b)==1,"Edit created body failed");
        var bad=new BookContentDO();bad.setId(s.body(8));bad.setIndexId(s.index(99));bad.setContent("orphan");
        try{bodies.save(bad);throw new AssertionError("Nonexistent directory accepted");}
        catch(com.java2nb.common.exception.BusinessException expected){check(expected.getCode()==400,"Wrong invalid directory code");}
        s.save("create-db-after.json",s.snapshot(7));s.save("create-txt-after.json",s.snapshot(8));s.pass("admin-regression",Map.of("createdStorageChoices",List.of("db","txt"),"invalidDirectoryRejected",true));
    }
}

