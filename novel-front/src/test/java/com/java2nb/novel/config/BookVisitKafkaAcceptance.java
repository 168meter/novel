package com.java2nb.novel.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.event.BookVisitEvent;
import com.java2nb.novel.mapper.FrontBookMapper;
import com.java2nb.novel.messaging.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.common.utils.AppInfoParser;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Host entry runs this TEST class, never the deployed application JAR. No new production beans. */
public final class BookVisitKafkaAcceptance {
    // Kafka's test classpath also contains Scala's Jackson module. Auto-discovery turns
    // untyped JSON arrays into Scala lists, breaking Java ownership-ledger comparisons.
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    static final Duration WAIT = Duration.ofSeconds(35);
    static final String MYSQL = "novel-cache-ab-20261016-mysql-1:3306";
    static final String BROKER = "novel-cache-ab-20261016-kafka-1:19092";

    record Names(String run, String identity) {
        Names {
            if (run == null || !run.matches("[0-9]{17}_[a-f0-9]{8}")
                || identity == null || !identity.matches("[0-9]{17}"))
                throw new IllegalArgumentException("Not an independent acceptance namespace");
        }
        String schema() { return "cv_it_" + identity + "_kv_" + run; }
        String topic(String s) { return "cv-visit-" + run.replace('_','-') + "-" + s.toLowerCase(Locale.ROOT); }
        String dlt(String s) { return topic(s) + "-dlt"; }
        String group(String s) { return topic(s) + "-writer"; }
        List<String> topics() { return List.of(topic("a"),dlt("a"),topic("b"),dlt("b"),topic("c"),dlt("c")); }
        List<String> groups() { return List.of(group("a"),group("b"),group("c")); }
        void checkLedger(Map<String,Object> ledger) {
            if (!run.equals(ledger.get("run")) || !schema().equals(ledger.get("schema"))
                || !topics().equals(ledger.get("topics")) || !groups().equals(ledger.get("groups"))
                || !Boolean.TRUE.equals(ledger.get("preexistingAbsent")))
                throw new IllegalArgumentException("Cleanup ownership ledger mismatch");
        }
    }

    final Names names;
    final Path out;
    final JdbcTemplate server;
    final Map<String,Object> result = new LinkedHashMap<>();
    Admin admin;
    JdbcTemplate jdbc;
    String clusterId;
    final Map<String,Object> ledger = new LinkedHashMap<>();

    BookVisitKafkaAcceptance(Map<String,String> env) {
        this(env,null);
    }
    BookVisitKafkaAcceptance(Map<String,String> env,JdbcTemplate testServer) {
        names = new Names(required(env,"KVI_RUN"), required(env,"KVI_IDENTITY"));
        if (!required(env,"KVI_USER").equals("cv_" + names.identity()))
            throw new IllegalArgumentException("Restricted MySQL identity required");
        out = Path.of(required(env,"KVI_EVIDENCE"));
        if (!out.isAbsolute()) throw new IllegalArgumentException("Absolute evidence path required");
        server = testServer==null ? new JdbcTemplate(dataSource("", env)) : testServer;
        server.setQueryTimeout(5);
        for (String s : List.of("A","B","C")) result.put(s, new LinkedHashMap<>(Map.of("status","BLOCKED")));
    }

    static String required(Map<String,String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
    DriverManagerDataSource dataSource(String schema, Map<String,String> env) {
        return new DriverManagerDataSource("jdbc:mysql://" + MYSQL + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000&socketTimeout=5000",
            required(env,"KVI_USER"), required(env,"KVI_PASSWORD"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length==2 && args[0].equals("probe")) {
            probe(Path.of(args[1]));return;
        }
        var acceptance = new BookVisitKafkaAcceptance(System.getenv());
        if (args.length != 1 || !Set.of("run","cleanup").contains(args[0]))
            throw new IllegalArgumentException("Specify run or cleanup");
        Files.createDirectories(acceptance.out);
        int exit = 0;
        try {
            if (args[0].equals("run")) {
                acceptance.connect();
                acceptance.create();
                acceptance.scenario("A",11L,false,false);
                acceptance.scenario("B",21L,true,false);
                acceptance.scenario("C",31L,false,true);
                acceptance.result.put("status","ABC_PASSED");
            }
        } catch (Throwable failed) {
            exit = 1;
            acceptance.result.put("status","FAILED");
            acceptance.result.put("failure",failed.toString());
            failed.printStackTrace();
        } finally {
            try { acceptance.cleanup(); }
            catch (Throwable failedCleanup) {
                exit = 1;
                acceptance.result.put("cleanup","FAILED");
                acceptance.result.put("cleanupFailure",failedCleanup.toString());
                failedCleanup.printStackTrace();
            }
            if (acceptance.admin != null) acceptance.admin.close(Duration.ofSeconds(5));
            acceptance.save(args[0].equals("run") ? "acceptance.json" : "cleanup-retry.json", acceptance.result);
        }
        if (exit != 0) throw new IllegalStateException("Acceptance/cleanup failed; inspect preserved evidence");
    }

    /** No network access: exercise the exact bundled classpath before creating any resources. */
    static void probe(Path file) throws Exception {
        var checks = new LinkedHashMap<String,Object>();
        checks.put("java",System.getProperty("java.version"));
        checks.put("springKafka",KafkaTemplate.class.getPackage().getImplementationVersion());
        checks.put("kafkaClients",AppInfoParser.getVersion());
        checks.put("mysqlDriver",Class.forName("com.mysql.cj.jdbc.Driver").getName());
        check("3.3.0".equals(checks.get("springKafka")),"Bundled Spring Kafka mismatch");
        check("3.8.1".equals(checks.get("kafkaClients")),"Bundled Kafka client mismatch");
        var digests = new LinkedHashMap<String,String>();
        for (String resource:List.of("com/java2nb/novel/messaging/BookVisitEventConsumer.class",
            "com/java2nb/novel/messaging/BookVisitBatchWriter.class","com/java2nb/novel/config/BookVisitKafkaConfig.class",
            "mybatis/mapping/BookMapper.xml")) {
            try (var input=BookVisitKafkaAcceptance.class.getClassLoader().getResourceAsStream(resource)) {
                check(input!=null,"Bundled input missing: "+resource);
                digests.put(resource,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
            }
        }
        checks.put("loadedInputs",digests);
        Files.writeString(file,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(checks));
    }

    void connect() throws Exception {
        connectBroker();
        String mysqlVersion = server.queryForObject("SELECT VERSION()",String.class);
        check(mysqlVersion != null && mysqlVersion.startsWith("8."), "MySQL 8 required");
        var versions = Map.of("clusterId",clusterId,"mysql",mysqlVersion,
            "springKafka", Objects.toString(KafkaTemplate.class.getPackage().getImplementationVersion()),
            "kafkaClients",AppInfoParser.getVersion(),"java",System.getProperty("java.version"));
        save("versions.json",versions);
        check("3.3.0".equals(versions.get("springKafka")),"Spring Kafka dependency drift");
        check("3.8.1".equals(versions.get("kafkaClients")),"Kafka client dependency drift");
    }

    void connectBroker() throws Exception {
        var props = Map.<String,Object>of("bootstrap.servers",BROKER,"client.id","cv-acceptance-" + names.run(),
            "request.timeout.ms",5000,"default.api.timeout.ms",10000);
        admin = Admin.create(props);
        clusterId = admin.describeCluster().clusterId().get(10, TimeUnit.SECONDS);
    }

    void create() throws Exception {
        check(!Files.exists(out.resolve("ownership.json")),"Cannot adopt an existing run");
        var existingTopics = admin.listTopics().names().get(10, TimeUnit.SECONDS);
        var existingGroups = admin.listConsumerGroups().all().get(10, TimeUnit.SECONDS).stream()
            .map(ConsumerGroupListing::groupId).toList();
        check(Collections.disjoint(existingTopics,names.topics()),"Topic collision");
        check(Collections.disjoint(existingGroups,names.groups()),"Group collision");
        check(server.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
            Integer.class,names.schema()) == 0,"Schema collision");
        ledger.putAll(Map.of("run",names.run(),"schema",names.schema(),"topics",names.topics(),
            "groups",names.groups(),"clusterId",clusterId,"preexistingAbsent",true));
        // Write the intent BEFORE mutation: an outer timeout can still clean this unique namespace.
        save("ownership.json",ledger);
        server.execute("CREATE DATABASE `" + names.schema() + "`");
        jdbc = new JdbcTemplate(dataSource(names.schema(),System.getenv()));
        jdbc.setQueryTimeout(5);
        jdbc.execute("CREATE TABLE acceptance_owner (run_id varchar(64) PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO acceptance_owner VALUES (?)",names.run());
        jdbc.execute("CREATE TABLE book (id bigint PRIMARY KEY,visit_count bigint NOT NULL) ENGINE=InnoDB");
        jdbc.update("INSERT INTO book VALUES (11,0),(12,0),(21,0),(22,0),(31,0),(32,0)");
        save("schema-before.json",jdbc.queryForList("SHOW CREATE TABLE book"));
        var topics = names.topics().stream().map(n -> new NewTopic(n,1,(short)1)
            .configs(Map.of("retention.ms","3600000","segment.bytes","1048576"))).toList();
        admin.createTopics(topics).all().get(15, TimeUnit.SECONDS);
    }

    void scenario(String s,long id,boolean invalid,boolean databaseFault) throws Exception {
        var report = new LinkedHashMap<String,Object>();
        report.put("status","RUNNING");result.put(s,report);
        report.put("topic",names.topic(s));report.put("group",names.group(s));
        report.put("started",Instant.now().toString());
        var deliveries = new CopyOnWriteArrayList<Map<String,Object>>();
        var fault = new WriteFault(databaseFault,jdbc);
        var metrics = new SimpleMeterRegistry();
        var producerProps = new HashMap<String,Object>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,BROKER);
        producerProps.put(ProducerConfig.ACKS_CONFIG,"all");
        producerProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,10000);
        producerProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,5000);
        producerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,10000);
        var producerFactory = new DefaultKafkaProducerFactory<Long,BookVisitEvent>(producerProps,
            new LongSerializer(),new JsonSerializer<>());
        var kafkaProps = new KafkaProperties();
        kafkaProps.setBootstrapServers(List.of(BROKER));
        kafkaProps.getProducer().getProperties().putAll(Map.of("delivery.timeout.ms","10000",
            "request.timeout.ms","5000","max.block.ms","10000"));
        var config = new BookVisitKafkaConfig(new BookVisitKafkaProperties(names.topic(s),names.dlt(s),names.group(s),50));
        var dltFactory = config.bookVisitDltProducerFactory(kafkaProps);
        KafkaMessageListenerContainer<Long,BookVisitEvent> container = null;
        try {
            var writer = writer(jdbc,fault);
            var productionConsumer = new BookVisitEventConsumer(new BookVisitBatchAggregator(),writer,metrics);
            var events = List.of(event(s+"-A",id,1),event(s+"-B",invalid ? id+1 : id,invalid ? 0 : 1),event(s+"-C",invalid ? id : id+1,1));
            report.put("before",snapshot(s,id));
            var template = new KafkaTemplate<>(producerFactory);
            var published = new ArrayList<Map<String,Object>>();
            for (var e:events) {
                var metadata = template.send(names.topic(s),0,e.bookId(),e).get(12,TimeUnit.SECONDS).getRecordMetadata();
                published.add(Map.of("partition",metadata.partition(),"offset",metadata.offset(),"event",e));
            }
            save(s+"-published.json",published);
            report.put("publishedCount",published.size());
            var props = new HashMap<String,Object>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,BROKER);
            props.put(ConsumerConfig.GROUP_ID_CONFIG,names.group(s));
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,50);
            props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,10000);
            props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG,10000);
            props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,false);
            var consumerFactory = new DefaultKafkaConsumerFactory<Long,BookVisitEvent>(props,
                new LongDeserializer(),new JsonDeserializer<>(BookVisitEvent.class,false));
            var cp = new ContainerProperties(names.topic(s));
            cp.setAckMode(ContainerProperties.AckMode.BATCH);
            cp.setSyncCommits(true);cp.setSyncCommitTimeout(Duration.ofSeconds(5));
            cp.setPollTimeout(300);cp.setShutdownTimeout(10000);
            cp.setMessageListener((BatchMessageListener<Long,BookVisitEvent>) records -> {
                var delivery = new LinkedHashMap<String,Object>();
                delivery.put("time",Instant.now().toString());
                delivery.put("records",records.stream().map(r -> Map.of("partition",r.partition(),"offset",r.offset(),"event",r.value())).toList());
                deliveries.add(delivery);
                try { productionConsumer.consume(records.stream().map(ConsumerRecord::value).toList());
                    delivery.put("outcome","WRITER_COMMITTED");
                } catch (RuntimeException failure) {
                    delivery.put("outcome",failure.toString());
                    if (failure instanceof BatchListenerFailedException batch) delivery.put("failedIndex",batch.getIndex());
                    if (databaseFault && fault.executed.get()) fault.rolledBack.countDown();
                    throw failure;
                } finally { save(s+"-deliveries.json",deliveries); }
            });
            container = new KafkaMessageListenerContainer<>(consumerFactory,cp);
            container.setCommonErrorHandler(config.bookVisitErrorHandler(config.bookVisitDltKafkaTemplate(dltFactory),metrics));
            container.setBeanName("cv-it-"+s);
            container.start();
            if (databaseFault) {
                check(fault.rolledBack.await(25,TimeUnit.SECONDS),"Did not observe a failed transaction");
                check(fault.executed.get(),"No real SQL update executed before rollback");
                check(fault.sawTransaction.get(),"Writer was not transactional");
                var expectedTotals = new BookVisitBatchAggregator().aggregate(events);
                var failed = snapshot(s,id);
                save(s+"-rollback.json",Map.of("database",failed,"realUpdateBeforeFailure",fault.afterUpdate,
                    "injectedUpdate",fault.injectedUpdate,"expectedAggregates",expectedTotals,
                    "faultEvents",fault.failures));
                // One UPDATE can apply multiple visits. The production Map has no promised order.
                // Save evidence before checking it, including on a failed assertion.
                fault.verifyObservedUpdate(expectedTotals);
                check(count(id)==0 && count(id+1)==0,"Failed transaction was not fully rolled back");
                check(offset(s)==0,"Database failure advanced committed offset");
                fault.release.set(true);
            }
            await(() -> count(id)==2 && count(id+1)==(invalid ? 0 : 1) && offset(s)==3,
                "DB increments / committed offsets did not converge");
            // Stop first: final snapshots cannot race a late retry or commit.
            stop(container);container = null;
            var dlt = readDlt(s);
            report.put("after",snapshot(s,id));report.put("dlt",dlt);
            report.put("metrics",Map.of("consumed",metrics.counter("novel.book.visit.kafka.consumed").count(),
                "dbUpdates",metrics.counter("novel.book.visit.kafka.db_updates").count(),"successfulMapperUpdates",fault.successes));
            check(!deliveries.isEmpty(),"No actual listener delivery");
            report.put("listenerDeliveryCount",deliveries.size());
            @SuppressWarnings("unchecked")
            var first = (List<Map<String,Object>>)deliveries.getFirst().get("records");
            check(first.size()==3,"First actual batch was not all three preloaded records");
            report.put("firstActualBatchSize",first.size());
            for (int i=0;i<3;i++) {
                check(((Number)first.get(i).get("offset")).longValue()==i,"Unexpected initial batch offsets");
                check(((BookVisitEvent)first.get(i).get("event")).eventId().equals(events.get(i).eventId()),"Initial batch event order mismatch");
            }
            check(dlt.size()==(invalid ? 1 : 0),"Wrong DLT record count");
            if (invalid) check(dlt.getFirst().get("eventId").equals(events.get(1).eventId()),"DLT did not contain only B");
            check(metrics.counter("novel.book.visit.kafka.consumed").count()==(invalid ? 2 : 3),"Legal records missed or processed twice");
            check(fault.successes==2,"Normal aggregation did not perform exactly two committed book updates");
            check(count(id)==2 && count(id+1)==(invalid ? 0 : 1) && offset(s)==3,"Final values changed");
            report.put("status","PASS");
        } catch (Throwable failure) {
            report.put("status","FAIL");report.put("failure",failure.toString());
            try { report.put("failureSnapshot",snapshot(s,id)); } catch (Throwable unavailable) {report.put("snapshotError",unavailable.toString());}
            try { report.put("failureDlt",readDlt(s)); } catch (Throwable unavailable) {report.put("dltError",unavailable.toString());}
            throw failure;
        } finally {
            fault.release.set(true);
            if (container != null) stop(container);
            producerFactory.destroy();dltFactory.destroy();metrics.close();
            report.put("finished",Instant.now().toString());
            save(s+"-deliveries.json",deliveries);save(s+"-result.json",report);
            save("acceptance.json",result);
        }
    }

    static BookVisitBatchWriter writer(JdbcTemplate jdbc,WriteFault fault) throws Exception {
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        factory.setMapperLocations(new ClassPathResource("mybatis/mapping/BookMapper.xml"));
        factory.setPlugins(fault);
        var sessions = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var proxy = new ProxyFactory(new BookVisitBatchWriter(sessions.getMapper(FrontBookMapper.class)));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(jdbc.getDataSource()),
            new AnnotationTransactionAttributeSource()));
        return (BookVisitBatchWriter) proxy.getProxy();
    }

    Map<String,Object> snapshot(String s,long id) throws Exception {
        var tp = new TopicPartition(names.topic(s),0);
        var offsets = admin.listConsumerGroupOffsets(names.group(s)).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS);
        var committed = offsets.get(tp);
        var end = admin.listOffsets(Map.of(tp,OffsetSpec.latest())).all().get(10,TimeUnit.SECONDS).get(tp).offset();
        return Map.of("time",Instant.now().toString(),"rows",jdbc.queryForList("SELECT * FROM book WHERE id IN (?,?) ORDER BY id",id,id+1),
            "committedOffset",committed==null ? "ABSENT" : committed.offset(),"sourceEndOffset",end);
    }
    long offset(String s) {
        try {
            var value = admin.listConsumerGroupOffsets(names.group(s)).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS)
                .get(new TopicPartition(names.topic(s),0));
            return value==null ? 0 : value.offset();
        } catch (Exception unavailable) { throw new IllegalStateException("Committed offset unavailable",unavailable); }
    }
    long count(long id) { return jdbc.queryForObject("SELECT visit_count FROM book WHERE id=?",Long.class,id); }
    List<Map<String,Object>> readDlt(String s) throws Exception {
        var tp = new TopicPartition(names.dlt(s),0);
        long end = admin.listOffsets(Map.of(tp,OffsetSpec.latest())).all().get(5,TimeUnit.SECONDS).get(tp).offset();
        var records = new ArrayList<Map<String,Object>>();
        try (var reader = new KafkaConsumer<Long,byte[]>(Map.of("bootstrap.servers",BROKER,"enable.auto.commit",false,
            "default.api.timeout.ms",10000,"allow.auto.create.topics",false),new LongDeserializer(),new ByteArrayDeserializer())) {
            reader.assign(List.of(tp));reader.seekToBeginning(List.of(tp));
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (reader.position(tp)<end && System.nanoTime()<deadline) {
                for (var r:reader.poll(Duration.ofMillis(300))) {
                    var event = JSON.readValue(r.value(),BookVisitEvent.class);
                    var headers = new LinkedHashMap<String,String>();
                    r.headers().forEach(h -> headers.put(h.key(),Base64.getEncoder().encodeToString(h.value())));
                    records.add(Map.of("offset",r.offset(),"partition",r.partition(),"key",r.key(),
                        "eventId",event.eventId(),"event",event,"headersBase64",headers,
                        "valueBase64",Base64.getEncoder().encodeToString(r.value())));
                }
            }
            check(reader.position(tp)==end,"DLT read incomplete");
        }
        save(s+"-dlt.json",Map.of("endOffset",end,"records",records));
        return records;
    }

    void cleanup() throws Exception {
        Path file = out.resolve("ownership.json");
        if (!Files.exists(file)) { result.put("cleanup","NOT_NEEDED");return; }
        var owned = JSON.readValue(Files.readString(file),new TypeReference<Map<String,Object>>(){});
        names.checkLedger(owned);
        var errors = new ArrayList<String>();
        // A Broker failure must not suppress cleanup of the independently owned MySQL schema.
        try {
        if (admin==null) connectBroker();
        check(clusterId!=null && clusterId.equals(owned.get("clusterId")),"Cleanup cluster mismatch");
        for (String group:names.groups()) {
            try {
                var groups = admin.listConsumerGroups().all().get(10,TimeUnit.SECONDS);
                if (groups.stream().anyMatch(g -> group.equals(g.groupId())))
                    admin.deleteConsumerGroups(List.of(group)).all().get(10,TimeUnit.SECONDS);
            } catch (Exception e) {errors.add("group "+group+": "+e);}
        }
        try {
            var present = admin.listTopics().names().get(10,TimeUnit.SECONDS);
            var selected = names.topics().stream().filter(present::contains).toList();
            if (!selected.isEmpty()) admin.deleteTopics(selected).all().get(15,TimeUnit.SECONDS);
            await(() -> {
                try {return Collections.disjoint(admin.listTopics().names().get(5,TimeUnit.SECONDS),names.topics());}
                catch (Exception e) {throw new IllegalStateException(e);}
            },"Owned topics were not deleted");
        } catch (Exception e) {errors.add("topics: "+e);}
        } catch (Exception e) {errors.add("Kafka ownership/connection: "+e);}
        try {
            if (server.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,names.schema())!=0) {
                // This namespace was proven absent and journaled before creation; never accept a foreign marker.
                var markerExists = server.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='acceptance_owner'",Integer.class,names.schema());
                if (markerExists!=0) check(names.run().equals(server.queryForObject("SELECT run_id FROM `"+names.schema()+"`.acceptance_owner",String.class)),"Foreign schema owner");
                server.execute("DROP DATABASE `"+names.schema()+"`");
            }
            check(server.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,names.schema())==0,"Schema cleanup incomplete");
        } catch (Exception e) {errors.add("schema: "+e);}
        result.put("cleanup",errors.isEmpty()?"VERIFIED":"FAILED");
        save("cleanup.json",Map.of("time",Instant.now().toString(),"errors",errors,"ownership",owned));
        check(errors.isEmpty(),"Cleanup incomplete: "+errors);
    }

    static BookVisitEvent event(String id,long book,long delta) {return new BookVisitEvent(id,book,delta,Instant.now(),1);}
    static void check(boolean ok,String message) {if(!ok) throw new IllegalStateException(message);}
    static void await(BooleanSupplier condition,String message) throws InterruptedException {
        long end = System.nanoTime()+WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime()>=end) throw new IllegalStateException(message);
            Thread.sleep(150);
        }
    }
    static void stop(KafkaMessageListenerContainer<?,?> container) throws InterruptedException {
        var stopped = new CountDownLatch(1);container.stop(stopped::countDown);
        check(stopped.await(12,TimeUnit.SECONDS),"Consumer did not stop; do not clean active group");
    }
    synchronized void save(String name,Object value) {
        try {
            Path temporary = out.resolve(name+".tmp");
            Files.writeString(temporary,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
            Files.move(temporary,out.resolve(name),StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failed) {throw new IllegalStateException("Evidence write failed: "+name,failed);}
    }

    /** TEST ONLY: update through the actual mapped statement, then force rollback until released. */
    @Intercepts(@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}))
    public static final class WriteFault implements Interceptor {
        final boolean enabled;
        final JdbcTemplate jdbc;
        final AtomicBoolean executed = new AtomicBoolean();
        final AtomicBoolean release = new AtomicBoolean();
        final AtomicBoolean sawTransaction = new AtomicBoolean();
        final CountDownLatch rolledBack = new CountDownLatch(1);
        final List<String> failures = new CopyOnWriteArrayList<>();
        volatile List<Map<String,Object>> afterUpdate = List.of();
        volatile Map<String,Long> injectedUpdate = Map.of();
        volatile int successes;
        WriteFault(boolean enabled,JdbcTemplate jdbc) {this.enabled=enabled;this.jdbc=jdbc;}
        void verifyObservedUpdate(Map<Long,Long> expectedTotals) {
            check(injectedUpdate.size()==3,"Real UPDATE metadata missing");
            long book = injectedUpdate.get("bookId"), delta = injectedUpdate.get("delta");
            check(injectedUpdate.get("affectedRows")==1L,"Injected UPDATE must affect one row");
            check(expectedTotals.containsKey(book) && expectedTotals.get(book)==delta,
                "Injected delta must match the actual book aggregate");
            var seen = new HashSet<Long>();
            for (var row:afterUpdate) {
                long rowId = ((Number)row.get("id")).longValue();
                long value = ((Number)row.get("visit_count")).longValue();
                check(expectedTotals.containsKey(rowId) && seen.add(rowId),"Unexpected or duplicate observed book");
                check(value==(rowId==book ? delta : 0L),"Transaction snapshot must match exactly the injected UPDATE");
            }
            check(seen.equals(expectedTotals.keySet()),"Transaction snapshot is incomplete");
        }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            check(((MappedStatement)invocation.getArgs()[0]).getId().equals(FrontBookMapper.class.getName()+".addVisitCount"),"Unexpected production statement");
            check(TransactionSynchronizationManager.isActualTransactionActive(),"Writer transaction missing");
            sawTransaction.set(true);
            if (enabled && !release.get()) {
                if (executed.compareAndSet(false,true)) {
                    var parameters = (Map<?,?>)invocation.getArgs()[1];
                    long book = ((Number)parameters.get("bookId")).longValue();
                    long delta = ((Number)parameters.get("visitCount")).longValue();
                    Object affected = invocation.proceed();
                    injectedUpdate = Map.of("bookId",book,"delta",delta,"affectedRows",((Number)affected).longValue());
                    afterUpdate=jdbc.queryForList("SELECT * FROM book WHERE id IN (31,32) ORDER BY id");
                }
                failures.add(Instant.now()+" TEST_ONLY transient SQL failure");
                throw new java.sql.SQLTransientConnectionException("TEST_ONLY rollback gate");
            }
            Object updated = invocation.proceed();successes++;
            check(((Number)updated).intValue()==1,"Mapped update did not affect one book");
            return updated;
        }
    }
}
