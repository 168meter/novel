package com.java2nb.novel.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.cache.ChapterContentCacheKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.session.*;
import org.apache.ibatis.plugin.Interceptor;
import org.mybatis.spring.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.connection.jedis.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Test-only shared plumbing, compiled separately on each module's fresh test classpath. */
public final class ChapterFinalSupport implements AutoCloseable {
    public static final ObjectMapper JSON=new ObjectMapper();
    public final String run,identity,schema;
    public final Path out;
    public final long book;
    public final DriverManagerDataSource ds;
    public final JdbcTemplate jdbc;
    public final DataSourceTransactionManager tx;
    public final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    public final RedisConnectionFactory factory;
    public final ObservedRedisTemplate redis;
    public final GenericApplicationContext context=new GenericApplicationContext();

    public static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static String env(String name){String v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing "+name);return v;}
    public static void validate(String run,String identity,String schema,String user) {
        if(!run.matches("[0-9]{17}_[a-f0-9]{8}") || !identity.matches("[0-9]{17}")
            || !schema.equals("cv_it_"+identity+"_rc_"+run) || !user.equals("cv_"+identity))
            throw new IllegalArgumentException("Refusing non-owned acceptance namespace");
    }
    public static void probe() {
        String r="20261023120000000_abcdef12",i="20261017231415443",s="cv_it_"+i+"_rc_"+r;
        validate(r,i,s,"cv_"+i);
        try{validate(r,i,"novel_ab","cv_"+i);throw new AssertionError("Unsafe schema accepted");}
        catch(IllegalArgumentException expected){}
        try {
            Class.forName(jedis()?"redis.clients.jedis.Jedis":"io.lettuce.core.AbstractRedisClient");
        }catch(ClassNotFoundException e){throw new IllegalStateException("Module Redis driver unavailable",e);}
        System.out.println("NAMESPACE_GUARD_PASSED");
    }
    static boolean jedis(){return "jedis".equals(System.getProperty("rcf.redis.client"));}
    public ChapterFinalSupport(boolean create) throws Exception {
        run=env("RCF_RUN");identity=env("RCF_IDENTITY");schema=env("RCF_SCHEMA");
        validate(run,identity,schema,env("RCF_USER"));
        out=Path.of(env("RCF_OUT"));check(out.isAbsolute(),"Absolute evidence path required");
        book=8_100_000_000_000_000_000L+Long.parseUnsignedLong(run.substring(18),16)*100L;
        ds=dataSource(schema);jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(5);tx=new DataSourceTransactionManager(ds);
        var cfg=new RedisStandaloneConfiguration("127.0.0.1",26381);cfg.setPassword(env("RCF_REDIS_PASSWORD"));
        factory=jedis()
            ?new JedisConnectionFactory(cfg,JedisClientConfiguration.builder().connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(2)).build())
            :new LettuceConnectionFactory(cfg,LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build());
        ((org.springframework.beans.factory.InitializingBean)factory).afterPropertiesSet();
        if((Object)factory instanceof org.springframework.context.Lifecycle lifecycle)lifecycle.start();
        redis=new ObservedRedisTemplate(factory);redis.afterPropertiesSet();
        if(!create) {
            check(schema.equals(jdbc.queryForObject("SELECT DATABASE()",String.class)),"Schema mismatch");
            check(run.equals(jdbc.queryForObject("SELECT run_id FROM chapter_final_owner",String.class)),"Schema ownership mismatch");
        }
        context.registerBean("transactionManager",DataSourceTransactionManager.class,()->tx);
        context.refresh();
    }
    public DriverManagerDataSource dataSource(String database) {
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:23307/"+database
            +"?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8&connectTimeout=3000&socketTimeout=5000",
            env("RCF_USER"),env("RCF_PASSWORD"));
    }
    public SqlSessionTemplate sessions(String[] xml,Interceptor...plugins) throws Exception {
        var f=new SqlSessionFactoryBean();f.setDataSource(ds);
        var c=new org.apache.ibatis.session.Configuration();c.setMapUnderscoreToCamelCase(true);
        c.setLogImpl(org.apache.ibatis.logging.stdout.StdOutImpl.class);f.setConfiguration(c);
        if(xml.length>0) f.setMapperLocations(Arrays.stream(xml).map(ClassPathResource::new).toArray(ClassPathResource[]::new));
        if(plugins.length>0)f.setPlugins(plugins);
        return new SqlSessionTemplate(Objects.requireNonNull(f.getObject()));
    }
    public <T>T mapper(SqlSessionTemplate s,Class<T> type) {
        if(!s.getConfiguration().hasMapper(type))s.getConfiguration().addMapper(type);
        return s.getMapper(type);
    }
    @SuppressWarnings("unchecked") public <T>T bean(Class<T> type,T target) {
        var p=new ProxyFactory(target);p.setProxyTargetClass(true);
        p.addAdvice(new TransactionInterceptor(tx,new AnnotationTransactionAttributeSource()));
        context.registerBean(type,()->(T)p.getProxy());return context.getBean(type);
    }
    public long index(int slot){return book+100+slot;}
    public long body(int slot){return book+200+slot;}
    public String key(int slot){return ChapterContentCacheKey.of(book,index(slot));}
    public String lock(int slot){return ChapterContentCacheKey.lockOf(book,index(slot));}
    public void save(String name,Object value) throws Exception {
        Path target=out.resolve(name),tmp=out.resolve(name+".tmp");
        Files.writeString(tmp,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);
    }
    public Map<String,Object> snapshot(int slot) {
        var v=new LinkedHashMap<String,Object>();v.put("time",Instant.now().toString());
        v.put("schema",schema);v.put("bookId",book);v.put("indexId",index(slot));
        v.put("mysql",jdbc.queryForList("SELECT b.id,b.book_id,b.storage_type,b.index_name,c.index_id,c.content FROM book_index b LEFT JOIN book_content c ON b.id=c.index_id WHERE b.id=?",index(slot)));
        var keys=new ArrayList<Map<String,Object>>();
        for(String k:List.of(key(slot),lock(slot))) {
            var row=new LinkedHashMap<String,Object>();row.put("key",k);row.put("type",redis.type(k));
            row.put("ttlMillis",redis.getExpire(k,java.util.concurrent.TimeUnit.MILLISECONDS));row.put("value",redis.opsForValue().get(k));keys.add(row);
        }
        v.put("redis",keys);return v;
    }
    public String bodyText(int slot){return jdbc.queryForObject("SELECT content FROM book_content WHERE index_id=?",String.class,index(slot));}
    public void signal(String name) throws Exception {Files.writeString(out.resolve(name),Instant.now().toString());}
    public void waitFor(String name,int seconds) throws Exception {
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
        while(!Files.exists(out.resolve(name))){if(System.nanoTime()>end)throw new AssertionError("Timed out waiting for "+name);Thread.sleep(25);}
    }
    public void pass(String name,Object detail) throws Exception {save(name+".json",Map.of("status","PASS","time",Instant.now().toString(),"detail",detail));}
    /** Observation only: every command delegates to the actual Redis implementation unchanged. */
    public static final class ObservedRedisTemplate extends StringRedisTemplate {
        public final List<Map<String,Object>> deletes=new java.util.concurrent.CopyOnWriteArrayList<>();
        ObservedRedisTemplate(RedisConnectionFactory connection){super(connection);}
        @Override public Long delete(Collection<String> keys) {
            Long removed=super.delete(keys);
            var row=new LinkedHashMap<String,Object>();row.put("time",Instant.now().toString());
            row.put("keys",List.copyOf(keys));row.put("removed",removed);deletes.add(row);
            return removed;
        }
    }
    @Override public void close() throws Exception {context.close();((org.springframework.beans.factory.DisposableBean)factory).destroy();metrics.close();}
}

