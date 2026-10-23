package com.java2nb.novel.service.cache;

import com.java2nb.novel.acceptance.ChapterFinalSupport;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.mapper.*;
import com.java2nb.novel.service.impl.*;
import com.java2nb.novel.core.config.BookPriceProperties;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.springframework.test.util.ReflectionTestUtils;
import static com.java2nb.novel.acceptance.ChapterFinalSupport.*;

/** Actual Front cache/Lua + MySQL mapper, no application server or Kafka producer. */
public final class ChapterFinalFront {
    final ChapterFinalSupport s;
    final ChapterContentCache cache;
    final DbBookContentServiceImpl reader;
    final org.mybatis.spring.SqlSessionTemplate sessions;
    final Gate gate;
    ChapterFinalFront(ChapterFinalSupport support,Gate g) throws Exception {
        s=support;gate=g;sessions=s.sessions(new String[]{"mybatis/mapping/BookMapper.xml"},g);
        cache=new ChapterContentCache(s.redis,JSON,s.metrics);
        s.context.registerBean(ChapterContentCache.class,()->cache);
        reader=new DbBookContentServiceImpl(s.mapper(sessions,BookContentMapper.class),cache,s.mapper(sessions,BookIndexMapper.class));
        s.context.registerBean(DbBookContentServiceImpl.class,()->reader);
        Files.writeString(s.out.resolve("production-fill.lua"),ChapterContentCache.FILL_IF_OWNER_SCRIPT.getScriptAsString());
    }
    public static void main(String[] args) throws Exception {
        if(args.length==1 && args[0].equals("probe")){ChapterFinalSupport.probe();return;}
        try(var s=new ChapterFinalSupport(false)) {
            var f=new ChapterFinalFront(s,new Gate());
            if(args[0].equals("race"))f.race();
            else if(args[0].equals("author"))f.author();
            else if(args[0].equals("txt"))f.txt();
            else {check(args.length==3,"Expected phase/slot/value");f.read(args[0],Integer.parseInt(args[1]),args[2]);}
        }
    }
    BookContent load(int slot){return reader.queryBookContent(s.book,s.index(slot));}
    void read(String label,int slot,String expected) throws Exception {
        s.save(label+"-before.json",s.snapshot(slot));
        check(expected.equals(load(slot).getContent()),"Initial real cache/SQL result wrong");
        check(expected.equals(load(slot).getContent()),"Cached result wrong");
        check(s.metrics.counter("novel.chapter.cache.lookup","result","hit").count()>=1,"No actual Redis hit");
        check(s.redis.opsForValue().get(s.key(slot)).contains(expected),"Redis value wrong");
        s.save(label+"-after.json",s.snapshot(slot));s.pass(label,Map.of("expected",expected,"hitCount",s.metrics.counter("novel.chapter.cache.lookup","result","hit").count()));
    }
    void race() throws Exception {
        check(!Boolean.TRUE.equals(s.redis.hasKey(s.key(3))),"C must start with cache miss");
        gate.armed.set(true);var pool=Executors.newSingleThreadExecutor();
        try {
            var old=pool.submit(()->load(3));
            check(gate.loaded.await(12,TimeUnit.SECONDS),"Did not enter real MySQL loader");
            check("old".equals(((BookContent)((List<?>)gate.value).get(0)).getContent()),"SQL loader did not capture old");
            check(Boolean.TRUE.equals(s.redis.hasKey(s.lock(3))),"No real loader token");
            s.save("C-loader-held.json",s.snapshot(3));s.signal("C-loader-held.signal");
            s.waitFor("C-committed.signal",35);
            gate.resume.countDown();check("old".equals(old.get(10,TimeUnit.SECONDS).getContent()),"Unexpected in-flight return");
            check(!Boolean.TRUE.equals(s.redis.hasKey(s.key(3))),"Old body re-resided in Redis");
            check(s.metrics.counter("novel.chapter.cache.write","result","rejected").count()==1,"Production Lua did not reject late fill");
            s.save("C-after-late-fill.json",s.snapshot(3));
            read("C-read",3,"new");s.pass("C",Map.of("captured","old","afterCommit","new","lateFillRejected",true));
        } finally {gate.resume.countDown();pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
    }
    void author() throws Exception {
        read("author-baseline",4,"old");
        var price=new BookPriceProperties();price.setWordCount(BigDecimal.valueOf(1000));price.setValue(BigDecimal.ONE);
        var dependencies=new HashMap<Class<?>,Object>();
        dependencies.put(FrontBookMapper.class,s.mapper(sessions,FrontBookMapper.class));
        dependencies.put(FrontBookIndexMapper.class,s.mapper(sessions,FrontBookIndexMapper.class));
        dependencies.put(BookContentMapper.class,s.mapper(sessions,BookContentMapper.class));
        dependencies.put(ChapterContentCache.class,cache);dependencies.put(BookPriceProperties.class,price);
        var constructor=com.java2nb.novel.service.impl.BookServiceImpl.class.getDeclaredConstructors()[0];
        Object[] values=Arrays.stream(constructor.getParameterTypes()).map(dependencies::get).toArray();
        // Unused recommendation/AI/comment/vote collaborators remain unstarted, never mocked as data writers.
        var target=(com.java2nb.novel.service.impl.BookServiceImpl)constructor.newInstance(values);
        var author=s.bean(com.java2nb.novel.service.impl.BookServiceImpl.class,target);
        author.updateBookContent(s.index(4),"author title","author new",s.book+9);
        check("author new".equals(s.bodyText(4)),"Actual author service failed");
        check(!Boolean.TRUE.equals(s.redis.hasKey(s.key(4))),"Author afterCommit missed Redis");
        read("author-read",4,"author new");s.pass("author-regression",Map.of("realAuthorId",s.book+9,"unusedCollaborators","not started; scoped method only"));
    }
    void txt() throws Exception {
        var r=new FileBookContentServiceImpl(s.mapper(sessions,BookIndexMapper.class));
        ReflectionTestUtils.setField(r,"fileSavePath",s.out.resolve("txt-root").toString());
        check("txt new".equals(r.queryBookContent(s.book,s.index(8)).getContent()),"TXT production reader failed");
        check(!Boolean.TRUE.equals(s.redis.hasKey(s.key(8))),"TXT path unexpectedly used DB body cache");
        s.pass("txt-regression",Map.of("storageType","txt","body","txt new","reader",r.getClass().getName()));
    }
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    public static final class Gate implements Interceptor {
        final AtomicBoolean armed=new AtomicBoolean();
        final CountDownLatch loaded=new CountDownLatch(1),resume=new CountDownLatch(1);
        volatile Object value;
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Object result=invocation.proceed();var statement=(MappedStatement)invocation.getArgs()[0];
            if(statement.getId().endsWith("BookContentMapper.selectMany") && armed.compareAndSet(true,false)) {
                value=result;loaded.countDown();
                if(!resume.await(55,TimeUnit.SECONDS))throw new AssertionError("Timed out waiting for committed update");
            }
            return result;
        }
    }
}

