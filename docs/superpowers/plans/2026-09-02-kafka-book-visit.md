# Kafka Book Visit Aggregation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace one synchronous MySQL update per book visit with asynchronous Kafka events that are consumed in batches, aggregated by `bookId`, and persisted with far fewer atomic increment SQL statements.

**Architecture:** `BookController` publishes a versioned `BookVisitEvent` keyed by `bookId` to `novel-book-visit-v1`. A batch listener validates and aggregates up to 500 records, then calls a transactional writer that performs one parameter-bound increment per distinct book; finite retries publish exhausted records to `novel-book-visit-dlt`.

**Tech Stack:** Java 21, Spring Boot 3.4.0, Spring Kafka (Boot-managed version), Apache Kafka Docker image 4.3.1 in KRaft mode, MyBatis, MySQL 8, Micrometer/Actuator, JUnit 5, Mockito, AssertJ, JMeter 5.6.3, PowerShell.

**Spec:** `docs/superpowers/specs/2026-09-02-kafka-book-visit-design.md`

## Global Constraints

- Keep the application as a single `novel-front` service; do not create a microservice.
- Pin the local broker to `apache/kafka:4.3.1`; do not use `latest` and do not add ZooKeeper.
- Use topics `novel-book-visit-v1` and `novel-book-visit-dlt`, each with 3 partitions and replication factor 1 for the single-node learning environment.
- Keep `POST /book/addVisitCount` and its existing response shape unchanged for the browser.
- Use `bookId` as the Kafka key, JSON for `BookVisitEvent`, `acks=all`, producer idempotence, batch size at most 500, and at-least-once consumption.
- Do not synchronously fall back to MySQL when Kafka is unavailable.
- Commit Kafka offsets only after the database batch succeeds; use finite retry and DLT recovery.
- Accept 1–3 seconds of display delay and rare duplicate counts after the database-commit/offset-commit crash window; do not claim exactly-once delivery.
- Use `#{visitCount}`, never `${visitCount}`, in the visit increment SQL.
- Do not add Redis visit counters, a deduplication table, recommendation logic, or unrelated refactors.
- Preserve all existing chapter-cache and three-task/single-navigation-SQL behavior.

## File Structure

### Existing files to modify

- `compose.local.yml`: track the existing MySQL/Redis services and add the single Kafka broker and volume.
- `novel-front/pom.xml`: add `spring-kafka` and test support.
- `novel-front/src/main/resources/application-dev.yml`: local producer, consumer, listener, and feature settings.
- `novel-front/src/main/java/com/java2nb/novel/controller/BookController.java`: publish visits instead of calling the synchronous service method.
- `novel-front/src/main/java/com/java2nb/novel/mapper/FrontBookMapper.java`: change increment delta from `Integer` to `Long`.
- `novel-front/src/main/resources/mybatis/mapping/BookMapper.xml`: replace text substitution with parameter binding.
- `performance/README.md`: document Kafka commands and visit benchmark use.
- `performance/results/chapter-performance-summary.md`: keep chapter conclusions unchanged; link to the separate visit report.
- `docs/learning/novel-plus-evolution-guide.md`: append implementation, code locations, failure semantics, and measured results.

### New production files

- `novel-front/src/main/java/com/java2nb/novel/event/BookVisitEvent.java`: immutable versioned event contract.
- `novel-front/src/main/java/com/java2nb/novel/config/BookVisitKafkaProperties.java`: topic, DLT topic, group, and batch settings.
- `novel-front/src/main/java/com/java2nb/novel/config/BookVisitKafkaConfig.java`: properties registration, UTC clock, topic beans, and finite retry/DLT error handler.
- `novel-front/src/main/java/com/java2nb/novel/messaging/BookVisitEventPublisher.java`: event creation, asynchronous send, and producer metrics.
- `novel-front/src/main/java/com/java2nb/novel/messaging/BookVisitBatchAggregator.java`: pure validation and `bookId` aggregation.
- `novel-front/src/main/java/com/java2nb/novel/messaging/BookVisitBatchWriter.java`: transactional database increment boundary.
- `novel-front/src/main/java/com/java2nb/novel/messaging/BookVisitEventConsumer.java`: batch listener and consumer metrics.

### New tests and performance files

- `novel-front/src/test/java/com/java2nb/novel/messaging/BookVisitEventPublisherTest.java`
- `novel-front/src/test/java/com/java2nb/novel/controller/BookControllerVisitCountTest.java`
- `novel-front/src/test/java/com/java2nb/novel/messaging/BookVisitBatchAggregatorTest.java`
- `novel-front/src/test/java/com/java2nb/novel/messaging/BookVisitBatchWriterTest.java`
- `novel-front/src/test/java/com/java2nb/novel/messaging/BookVisitEventConsumerTest.java`
- `novel-front/src/test/java/com/java2nb/novel/config/BookVisitKafkaConfigTest.java`
- `performance/jmeter/book-visit-count.jmx`
- `performance/run-book-visit-stages.ps1`
- `performance/check-book-visit-kafka.ps1`
- `performance/results/book-visit-kafka-summary.md`

---

### Task 1: Checkpoint the completed chapter navigation optimization

**Files:**
- Modify: `novel-front/src/main/java/com/java2nb/novel/controller/page/PageController.java:230-281`
- Modify: `novel-front/src/main/java/com/java2nb/novel/service/BookService.java:94-104`
- Modify: `novel-front/src/main/java/com/java2nb/novel/service/impl/BookServiceImpl.java:77,309-316`
- Create: `novel-front/src/main/java/com/java2nb/novel/mapper/FrontBookIndexMapper.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/vo/BookIndexNavigationVO.java`
- Test: `novel-front/src/test/java/com/java2nb/novel/controller/page/PageControllerChapterTaskTest.java`
- Test: `novel-front/src/test/java/com/java2nb/novel/mapper/FrontBookIndexMapperTest.java`

**Interfaces:**
- Produces: clean Git checkpoint containing the already implemented `BookService.queryBookIndexNavigation(Long, Integer)` and three-task page flow.
- Consumes: no Kafka code.

- [ ] **Step 1: Inspect the exact pending scope**

Run:

```powershell
git status --short
git diff --check
git diff -- novel-front/src/main/java/com/java2nb/novel/controller/page/PageController.java
```

Expected: only the known chapter-navigation files plus no whitespace errors; do not stage unrelated files.

- [ ] **Step 2: Run the full existing test suite**

Run:

```powershell
& 'C:\Users\26635\.m2\wrapper\dists\apache-maven-3.9.12-bin\5nmfsn99br87k5d4ajlekdq10k\apache-maven-3.9.12\bin\mvn.cmd' `
  -pl novel-front -am '-DskipTests=false' '-Dmaven.test.skip=false' test
```

Expected: `novel-front` 17 tests and `novel-common` 2 tests pass, with no failures or errors.

- [ ] **Step 3: Commit only the chapter optimization**

```powershell
git add -- `
  'novel-front/src/main/java/com/java2nb/novel/controller/page/PageController.java' `
  'novel-front/src/main/java/com/java2nb/novel/service/BookService.java' `
  'novel-front/src/main/java/com/java2nb/novel/service/impl/BookServiceImpl.java' `
  'novel-front/src/main/java/com/java2nb/novel/mapper/FrontBookIndexMapper.java' `
  'novel-front/src/main/java/com/java2nb/novel/vo/BookIndexNavigationVO.java' `
  'novel-front/src/test/java/com/java2nb/novel/controller/page/PageControllerChapterTaskTest.java' `
  'novel-front/src/test/java/com/java2nb/novel/mapper/FrontBookIndexMapperTest.java'
git diff --cached --check
git commit -m 'perf: reduce chapter navigation database round trips'
```

Expected: clean checkpoint for the chapter work; the Kafka plan/docs commits remain separate.

---

### Task 2: Capture the synchronous visit-count baseline

**Files:**
- Create: `performance/jmeter/book-visit-count.jmx`
- Create: `performance/run-book-visit-stages.ps1`
- Create: `performance/results/book-visit-kafka-summary.md`
- Modify: `performance/README.md`

**Interfaces:**
- Consumes: existing `POST /book/addVisitCount` with form field `bookId`.
- Produces: repeatable JMeter command and immutable `before-kafka` raw report directory.

- [ ] **Step 1: Add a focused JMeter plan**

Create `book-visit-count.jmx` by retaining the thread group/property structure from `chapter-baseline.jmx`, but use exactly this HTTP sampler and assertion:

```xml
<HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="Add book visit" enabled="true">
  <elementProp name="HTTPsampler.Arguments" elementType="Arguments">
    <collectionProp name="Arguments.arguments">
      <elementProp name="bookId" elementType="HTTPArgument">
        <boolProp name="HTTPArgument.always_encode">false</boolProp>
        <stringProp name="Argument.value">${__P(bookId)}</stringProp>
        <stringProp name="Argument.metadata">=</stringProp>
        <boolProp name="HTTPArgument.use_equals">true</boolProp>
        <stringProp name="Argument.name">bookId</stringProp>
      </elementProp>
    </collectionProp>
  </elementProp>
  <stringProp name="HTTPSampler.domain">127.0.0.1</stringProp>
  <stringProp name="HTTPSampler.port">8083</stringProp>
  <stringProp name="HTTPSampler.path">/book/addVisitCount</stringProp>
  <stringProp name="HTTPSampler.method">POST</stringProp>
  <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
</HTTPSamplerProxy>
<ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="Application success" enabled="true">
  <collectionProp name="Asserion.test_strings">
    <stringProp name="success-marker">&quot;code&quot;:0</stringProp>
  </collectionProp>
  <stringProp name="Assertion.custom_message">addVisitCount did not return application success</stringProp>
  <stringProp name="Assertion.test_field">Assertion.response_data</stringProp>
  <boolProp name="Assertion.assume_success">false</boolProp>
  <intProp name="Assertion.test_type">16</intProp>
</ResponseAssertion>
```

Confirm the actual `RestResult.ok()` JSON once with `Invoke-WebRequest`; if the success code serializes differently, use that exact stable marker in both before and after runs.

- [ ] **Step 2: Add the non-GUI stage runner**

Create `run-book-visit-stages.ps1` with parameters `BookId`, `Label`, `JMeterHome`, and `DurationSeconds`. Reuse the safe no-overwrite behavior from `run-chapter-stages.ps1`, set stages to `@(1, 10, 30, 50, 100, 200)`, pass `-JbookId=$BookId`, and write to `performance/results/raw/book-visit-$Label/`.

The core invocation must be:

```powershell
& $jmeter '-n' '-t' $testPlan "-Jthreads=$threads" "-JrampUp=$rampUp" `
  "-Jduration=$DurationSeconds" "-JbookId=$BookId" '-l' $resultFile '-e' '-o' $reportRoot
if ($LASTEXITCODE -ne 0) {
    throw "JMeter visit stage threads=$threads failed with exit code $LASTEXITCODE"
}
```

- [ ] **Step 3: Verify the plan with one short stage**

Run the JMeter plan directly for 1 thread and 10 seconds. Expected: 0 assertion errors and the chosen book's `visit_count` increases.

- [ ] **Step 4: Capture the synchronous baseline**

Record the initial `visit_count`, enable MySQL general log only for the bounded test window, then run:

```powershell
& '.\performance\run-book-visit-stages.ps1' `
  -BookId 2055879962859147264 `
  -Label 'before-kafka' `
  -DurationSeconds 60
```

Expected: raw JTL/HTML reports exist, final `visit_count` delta equals successful application responses, and general log shows approximately one `UPDATE book` per successful request.

- [ ] **Step 5: Record and commit the baseline harness**

Write environment, request counts, throughput, P95, P99, error rate, database delta, and counted UPDATE statements into `book-visit-kafka-summary.md` under “Before Kafka”.

```powershell
git add -- performance/jmeter/book-visit-count.jmx performance/run-book-visit-stages.ps1 performance/README.md performance/results/book-visit-kafka-summary.md
git commit -m 'perf: capture synchronous book visit baseline'
```

---

### Task 3: Add the Kafka Docker service and Spring dependency

**Files:**
- Create: `compose.local.yml`
- Modify: `novel-front/pom.xml:18-35`
- Modify: `novel-front/src/main/resources/application-dev.yml:1-40`
- Create: `novel-front/src/main/java/com/java2nb/novel/config/BookVisitKafkaProperties.java`
- Test: `novel-front/src/test/java/com/java2nb/novel/config/BookVisitKafkaPropertiesTest.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/config/BookVisitKafkaConfig.java`

**Interfaces:**
- Produces: `BookVisitKafkaProperties` with `topic()`, `dltTopic()`, `groupId()`, and `maxPollRecords()` values plus a UTC `Clock` bean.
- Consumes: Docker MySQL on 3307 and Redis on 6380 already used locally.

- [ ] **Step 1: Write the failing properties binding test**

```java
class BookVisitKafkaPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class)
        .withPropertyValues(
            "novel.kafka.book-visit.topic=visit-topic",
            "novel.kafka.book-visit.dlt-topic=visit-dlt",
            "novel.kafka.book-visit.group-id=visit-group",
            "novel.kafka.book-visit.max-poll-records=500");

    @Test
    void bindsBookVisitSettings() {
        contextRunner.run(context -> {
            BookVisitKafkaProperties properties = context.getBean(BookVisitKafkaProperties.class);
            assertThat(properties.topic()).isEqualTo("visit-topic");
            assertThat(properties.dltTopic()).isEqualTo("visit-dlt");
            assertThat(properties.groupId()).isEqualTo("visit-group");
            assertThat(properties.maxPollRecords()).isEqualTo(500);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BookVisitKafkaProperties.class)
    static class TestConfig {}
}
```

- [ ] **Step 2: Run the test and verify RED**

Run only `BookVisitKafkaPropertiesTest`. Expected: compilation fails because the properties record does not exist.

- [ ] **Step 3: Add dependencies and minimal properties**

Add to `novel-front/pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
```

Create:

```java
@ConfigurationProperties(prefix = "novel.kafka.book-visit")
public record BookVisitKafkaProperties(
    String topic,
    String dltTopic,
    String groupId,
    int maxPollRecords
) {}
```

Create the initial configuration now so every intermediate commit can start:

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookVisitKafkaProperties.class)
public class BookVisitKafkaConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

Task 7 extends this same class with topics and the error handler; do not add `@ConfigurationPropertiesScan` to the application.

- [ ] **Step 4: Add local Kafka settings**

Append to `application-dev.yml`:

```yaml
spring:
  kafka:
    bootstrap-servers: 127.0.0.1:9092
    producer:
      acks: all
      key-serializer: org.apache.kafka.common.serialization.LongSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      properties:
        enable.idempotence: true
        max.block.ms: 500
        request.timeout.ms: 1000
        delivery.timeout.ms: 3000
    consumer:
      enable-auto-commit: false
      key-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      max-poll-records: 500
      properties:
        spring.json.trusted.packages: com.java2nb.novel.event
        spring.deserializer.key.delegate.class: org.apache.kafka.common.serialization.LongDeserializer
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
        spring.json.value.default.type: com.java2nb.novel.event.BookVisitEvent
        spring.json.use.type.headers: false
    listener:
      type: batch
      ack-mode: batch

novel:
  kafka:
    book-visit:
      topic: novel-book-visit-v1
      dlt-topic: novel-book-visit-dlt
      group-id: novel-book-visit-writer-v1
      max-poll-records: 500
```

Merge the second `spring:` block into the existing top-level block; do not create duplicate YAML keys.

- [ ] **Step 5: Add and validate Compose**

Track the existing MySQL/Redis compose definition in this worktree and add:

```yaml
  kafka:
    image: apache/kafka:4.3.1
    container_name: novel-kafka
    restart: unless-stopped
    ports:
      - "9092:9092"
    volumes:
      - novel-kafka-data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 12
```

Add `novel-kafka-data:` to top-level `volumes`. Run `docker compose -f .\compose.local.yml config`; expected: valid merged configuration with all three services.

- [ ] **Step 6: Verify GREEN and commit**

Run `BookVisitKafkaPropertiesTest`, then the full Maven suite. Start only Kafka and wait for healthy:

```powershell
docker compose -f '.\compose.local.yml' up -d kafka
docker compose -f '.\compose.local.yml' ps kafka
```

```powershell
git add -- compose.local.yml novel-front/pom.xml novel-front/src/main/resources/application-dev.yml novel-front/src/main/java/com/java2nb/novel/config/BookVisitKafkaProperties.java novel-front/src/main/java/com/java2nb/novel/config/BookVisitKafkaConfig.java novel-front/src/test/java/com/java2nb/novel/config/BookVisitKafkaPropertiesTest.java
git commit -m 'build: add local Kafka infrastructure'
```

---

### Task 4: Implement the versioned event publisher

**Files:**
- Create: `novel-front/src/main/java/com/java2nb/novel/event/BookVisitEvent.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/messaging/BookVisitEventPublisher.java`
- Test: `novel-front/src/test/java/com/java2nb/novel/messaging/BookVisitEventPublisherTest.java`

**Interfaces:**
- Produces: `void BookVisitEventPublisher.publish(Long bookId)` and record `BookVisitEvent(String eventId, Long bookId, Long delta, Instant occurredAt, Integer version)`.
- Consumes: `KafkaTemplate<Long, BookVisitEvent>`, `BookVisitKafkaProperties`, `MeterRegistry`.

- [ ] **Step 1: Write the failing publisher test**

Use mocked `KafkaTemplate` returning a completed `SendResult`, a fixed `Clock`, and `SimpleMeterRegistry`. Assert topic, key, event fields, success counter, and invalid ID behavior:

```java
when(kafkaTemplate.send(eq("visit-topic"), eq(42L), any(BookVisitEvent.class)))
    .thenReturn(CompletableFuture.completedFuture(sendResult));
publisher.publish(42L);
verify(kafkaTemplate).send(eq("visit-topic"), eq(42L), eventCaptor.capture());
assertThat(eventCaptor.getValue().bookId()).isEqualTo(42L);
assertThat(eventCaptor.getValue().delta()).isEqualTo(1L);
assertThat(eventCaptor.getValue().version()).isEqualTo(1);
assertThat(eventCaptor.getValue().occurredAt()).isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
assertThat(registry.counter("novel.book.visit.kafka.send", "result", "success").count()).isEqualTo(1);
assertThatThrownBy(() -> publisher.publish(0L)).isInstanceOf(IllegalArgumentException.class);
```

Add a second test whose future completes exceptionally and a third test where `kafkaTemplate.send` throws `KafkaException` synchronously. Both must increment `result=failed` without propagating the broker failure or calling MySQL.

- [ ] **Step 2: Run and verify RED**

Expected: compilation fails because `BookVisitEvent` and publisher do not exist.

- [ ] **Step 3: Implement the immutable event and publisher**

```java
public record BookVisitEvent(String eventId, Long bookId, Long delta, Instant occurredAt, Integer version) {
    public static BookVisitEvent create(Long bookId, Clock clock) {
        if (bookId == null || bookId <= 0) {
            throw new IllegalArgumentException("bookId must be positive");
        }
        return new BookVisitEvent(UUID.randomUUID().toString(), bookId, 1L, clock.instant(), 1);
    }
}
```

`BookVisitEventPublisher.publish` must call `kafkaTemplate.send(properties.topic(), bookId, event)` inside `try`, attach `whenComplete`, increment `novel.book.visit.kafka.send{result=success|failed}`, and log only failures. Catch synchronous `KafkaException`, increment `result=failed`, and return without rethrowing so the noncritical endpoint stays bounded when broker metadata is unavailable. Do not catch event validation `IllegalArgumentException`. Inject the Task 3 `Clock` bean, allowing a fixed clock in unit tests.

- [ ] **Step 4: Run GREEN and commit**

Run publisher tests and the full suite.

```powershell
git add -- novel-front/src/main/java/com/java2nb/novel/event/BookVisitEvent.java novel-front/src/main/java/com/java2nb/novel/messaging/BookVisitEventPublisher.java novel-front/src/test/java/com/java2nb/novel/messaging/BookVisitEventPublisherTest.java
git commit -m 'feat: publish book visit events to Kafka'
```

---

### Task 5: Switch the HTTP endpoint from MySQL to Kafka

**Files:**
- Modify: `novel-front/src/main/java/com/java2nb/novel/controller/BookController.java:25-118`
- Create: `novel-front/src/test/java/com/java2nb/novel/controller/BookControllerVisitCountTest.java`

**Interfaces:**
- Consumes: `BookVisitEventPublisher.publish(Long)` from Task 4.
- Produces: unchanged `POST /book/addVisitCount` response without synchronous `BookService.addVisitCount`.

- [ ] **Step 1: Write the failing controller test**

Construct `BookController` directly with mocks and call the method:

```java
@Test
void publishesVisitWithoutUpdatingDatabaseSynchronously() {
    BookService bookService = mock(BookService.class);
    BookVisitEventPublisher publisher = mock(BookVisitEventPublisher.class);
    BookController controller = new BookController(
        bookService, Map.of(), mock(IpLocationService.class), mock(LikeService.class), publisher);

    RestResult<Void> result = controller.addVisitCount(42L);

    assertThat(result).isNotNull();
    verify(publisher).publish(42L);
    verify(bookService, never()).addVisitCount(anyLong(), anyInt());
}
```

- [ ] **Step 2: Run and verify RED**

Expected: constructor mismatch or publisher is never invoked.

- [ ] **Step 3: Make the minimal controller change**

Add final field `BookVisitEventPublisher bookVisitEventPublisher` and change only the endpoint body:

```java
@PostMapping("addVisitCount")
public RestResult<Void> addVisitCount(Long bookId) {
    bookVisitEventPublisher.publish(bookId);
    return RestResult.ok();
}
```

Do not delete `BookService.addVisitCount`; the consumer writer still needs the mapper increment boundary and existing callers must remain compatible.

- [ ] **Step 4: Run GREEN and commit**

Run controller and full tests.

```powershell
git add -- novel-front/src/main/java/com/java2nb/novel/controller/BookController.java novel-front/src/test/java/com/java2nb/novel/controller/BookControllerVisitCountTest.java
git commit -m 'feat: enqueue book visits from HTTP endpoint'
```

---

### Task 6: Implement pure batch aggregation and transactional database writing

**Files:**
- Create: `novel-front/src/main/java/com/java2nb/novel/messaging/BookVisitBatchAggregator.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/messaging/BookVisitBatchWriter.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/mapper/FrontBookMapper.java:18`
- Modify: `novel-front/src/main/resources/mybatis/mapping/BookMapper.xml:40-44`
- Test: `novel-front/src/test/java/com/java2nb/novel/messaging/BookVisitBatchAggregatorTest.java`
- Test: `novel-front/src/test/java/com/java2nb/novel/messaging/BookVisitBatchWriterTest.java`

**Interfaces:**
- Produces: `Map<Long, Long> BookVisitBatchAggregator.aggregate(List<BookVisitEvent>)` and `void BookVisitBatchWriter.write(Map<Long, Long>)`.
- Consumes: `FrontBookMapper.addVisitCount(Long bookId, Long visitCount)`.

- [ ] **Step 1: Write failing aggregator tests**

```java
@Test
void aggregatesVisitsByBook() {
    List<BookVisitEvent> events = List.of(event(1L, 1L), event(1L, 1L), event(2L, 1L), event(1L, 1L));
    assertThat(aggregator.aggregate(events)).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 3L, 2L, 1L));
}

@Test
void rejectsInvalidEvent() {
    assertThatThrownBy(() -> aggregator.aggregate(List.of(event(0L, 1L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bookId");
    assertThatThrownBy(() -> aggregator.aggregate(List.of(event(1L, 0L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("delta");
}
```

Also assert an empty list returns an immutable empty map.

- [ ] **Step 2: Write the failing writer test**

```java
@Test
void writesOneIncrementPerDistinctBook() {
    FrontBookMapper mapper = mock(FrontBookMapper.class);
    BookVisitBatchWriter writer = new BookVisitBatchWriter(mapper);
    writer.write(Map.of(1L, 3L, 2L, 1L));
    verify(mapper).addVisitCount(1L, 3L);
    verify(mapper).addVisitCount(2L, 1L);
    verifyNoMoreInteractions(mapper);
}
```

Use reflection to assert `write` carries `@Transactional(rollbackFor = Exception.class)`.

- [ ] **Step 3: Run both tests and verify RED**

Expected: missing aggregator/writer classes and mapper Long signature.

- [ ] **Step 4: Implement minimal aggregation and writer**

Aggregator:

```java
public Map<Long, Long> aggregate(List<BookVisitEvent> events) {
    Map<Long, Long> totals = new LinkedHashMap<>();
    for (BookVisitEvent event : events) {
        if (event == null || event.bookId() == null || event.bookId() <= 0) {
            throw new IllegalArgumentException("bookId must be positive");
        }
        if (event.delta() == null || event.delta() <= 0) {
            throw new IllegalArgumentException("delta must be positive");
        }
        totals.merge(event.bookId(), event.delta(), Math::addExact);
    }
    return Map.copyOf(totals);
}
```

Writer:

```java
@Transactional(rollbackFor = Exception.class)
public void write(Map<Long, Long> totals) {
    totals.forEach(bookMapper::addVisitCount);
}
```

Mapper signature:

```java
void addVisitCount(@Param("bookId") Long bookId, @Param("visitCount") Long visitCount);
```

XML:

```xml
<update id="addVisitCount">
    update book
    set visit_count = visit_count + #{visitCount}
    where id = #{bookId}
</update>
```

Adapt `BookServiceImpl.addVisitCount(Long, Integer)` with `visitCount.longValue()` so existing interface compatibility remains.

- [ ] **Step 5: Run GREEN and commit**

Run the two new tests, mapper-related tests, then full suite.

```powershell
git add -- novel-front/src/main/java/com/java2nb/novel/messaging/BookVisitBatchAggregator.java novel-front/src/main/java/com/java2nb/novel/messaging/BookVisitBatchWriter.java novel-front/src/main/java/com/java2nb/novel/mapper/FrontBookMapper.java novel-front/src/main/java/com/java2nb/novel/service/impl/BookServiceImpl.java novel-front/src/main/resources/mybatis/mapping/BookMapper.xml novel-front/src/test/java/com/java2nb/novel/messaging/BookVisitBatchAggregatorTest.java novel-front/src/test/java/com/java2nb/novel/messaging/BookVisitBatchWriterTest.java
git commit -m 'feat: aggregate and persist book visit batches'
```

---

### Task 7: Add batch consumption, finite retry, DLT, and metrics

**Files:**
- Modify: `novel-front/src/main/java/com/java2nb/novel/config/BookVisitKafkaConfig.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/messaging/BookVisitEventConsumer.java`
- Test: `novel-front/src/test/java/com/java2nb/novel/messaging/BookVisitEventConsumerTest.java`
- Test: `novel-front/src/test/java/com/java2nb/novel/config/BookVisitKafkaConfigTest.java`

**Interfaces:**
- Consumes: aggregator and writer from Task 6, `KafkaTemplate<Object, Object>`, and `BookVisitKafkaProperties`.
- Produces: batch listener `consume(List<BookVisitEvent>)`, two `NewTopic` beans, and a `DefaultErrorHandler`; preserves the Task 3 UTC `Clock` bean.

- [ ] **Step 1: Write failing consumer tests**

```java
@Test
void aggregatesThenWritesOneBatchAndRecordsMetrics() {
    BookVisitBatchAggregator aggregator = mock(BookVisitBatchAggregator.class);
    BookVisitBatchWriter writer = mock(BookVisitBatchWriter.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    BookVisitEventConsumer consumer = new BookVisitEventConsumer(aggregator, writer, registry);
    List<BookVisitEvent> events = List.of(event(1L), event(1L), event(2L));
    Map<Long, Long> totals = Map.of(1L, 2L, 2L, 1L);
    when(aggregator.aggregate(events)).thenReturn(totals);

    consumer.consume(events);

    verify(writer).write(totals);
    assertThat(registry.counter("novel.book.visit.kafka.consumed").count()).isEqualTo(3);
    assertThat(registry.counter("novel.book.visit.kafka.db_updates").count()).isEqualTo(2);
}
```

Add a failure test: writer throws; consumer must rethrow and must not increment successful consumed/db-update counters.

- [ ] **Step 2: Write failing config tests**

Instantiate config with properties and mocked `KafkaTemplate<Object,Object>`. Assert:

```java
assertThat(config.visitTopic().name()).isEqualTo("visit-topic");
assertThat(config.visitTopic().numPartitions()).isEqualTo(3);
assertThat(config.visitDltTopic().name()).isEqualTo("visit-dlt");
assertThat(config.clock().getZone()).isEqualTo(ZoneOffset.UTC);
assertThat(config.bookVisitErrorHandler(kafkaTemplate)).isInstanceOf(DefaultErrorHandler.class);
```

- [ ] **Step 3: Run tests and verify RED**

Expected: missing consumer/config classes.

- [ ] **Step 4: Implement consumer**

```java
@KafkaListener(
    topics = "${novel.kafka.book-visit.topic}",
    groupId = "${novel.kafka.book-visit.group-id}"
)
public void consume(List<BookVisitEvent> events) {
    Map<Long, Long> totals = aggregator.aggregate(events);
    writer.write(totals);
    consumedCounter.increment(events.size());
    databaseUpdateCounter.increment(totals.size());
    batchSizeSummary.record(events.size());
    aggregatedBookSummary.record(totals.size());
}
```

Construct counters/summaries once in the constructor. Do not log each successful event.

- [ ] **Step 5: Implement topic and error-handler configuration**

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookVisitKafkaProperties.class)
public class BookVisitKafkaConfig {
    @Bean
    NewTopic visitTopic(BookVisitKafkaProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic visitDltTopic(BookVisitKafkaProperties properties) {
        return TopicBuilder.name(properties.dltTopic()).partitions(3).replicas(1).build();
    }
    @Bean
    DefaultErrorHandler bookVisitErrorHandler(
        KafkaTemplate<Object, Object> template,
        BookVisitKafkaProperties properties,
        MeterRegistry registry
    ) {
        DeadLetterPublishingRecoverer dltPublisher = new DeadLetterPublishingRecoverer(
            template,
            (record, exception) -> new TopicPartition(properties.dltTopic(), record.partition()));
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            dltPublisher.accept(record, exception);
            registry.counter("novel.book.visit.kafka.dlt").increment();
        };
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(5000L, 12L));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        handler.setRetryListeners((record, exception, attempt) ->
            registry.counter("novel.book.visit.kafka.retry").increment());
        return handler;
    }
}
```

Compile against the Spring Kafka version resolved by Spring Boot 3.4.0. The required behavior is exact: `IllegalArgumentException` is non-retryable and goes directly to DLT; transient failures retry every 5 seconds up to 12 times; recovery publishes to the same-numbered DLT partition and increments the DLT counter.

- [ ] **Step 6: Run GREEN and commit**

Run consumer/config tests and the full suite.

```powershell
git add -- novel-front/src/main/java/com/java2nb/novel/config/BookVisitKafkaConfig.java novel-front/src/main/java/com/java2nb/novel/messaging/BookVisitEventConsumer.java novel-front/src/test/java/com/java2nb/novel/messaging/BookVisitEventConsumerTest.java novel-front/src/test/java/com/java2nb/novel/config/BookVisitKafkaConfigTest.java
git commit -m 'feat: consume book visits with retry and dead letter recovery'
```

---

### Task 8: Prove the complete Docker Kafka path and failure boundaries

**Files:**
- Create: `performance/check-book-visit-kafka.ps1`
- Modify: `performance/README.md`
- Modify: `performance/results/book-visit-kafka-summary.md`

**Interfaces:**
- Consumes: local Kafka 9092, MySQL 3307, Redis 6380, application 8083, Actuator 8084.
- Produces: repeatable functional and failure evidence without destructive database reset.

- [ ] **Step 1: Add a bounded functional-check script**

The script must:

1. Accept `BookId` and `RequestCount` (default 1000).
2. Read initial `visit_count` with `docker exec novel-mysql mysql -N`.
3. POST exactly `RequestCount` times and count application successes.
4. Poll consumer lag and database count for at most 60 seconds.
5. Assert final delta equals Kafka send-success metric delta, not merely HTTP accepted count.
6. Print initial count, accepted requests, producer successes/failures, final count, and elapsed drain time.
7. Never reset or overwrite the user's original visit count.

Use a bounded polling loop:

```powershell
$deadline = [DateTime]::UtcNow.AddSeconds(60)
do {
    Start-Sleep -Milliseconds 500
    $current = Get-BookVisitCount -BookId $BookId
    $lag = Get-VisitConsumerLag
} while (($lag -gt 0 -or $current -lt $expected) -and [DateTime]::UtcNow -lt $deadline)
if ($lag -gt 0) { throw "Kafka lag did not drain within 60 seconds: $lag" }
```

- [ ] **Step 2: Start all services and smoke test**

```powershell
docker compose -f '.\compose.local.yml' up -d
docker compose -f '.\compose.local.yml' ps
```

Start `novel-front` with external MySQL and Redis settings, verify Actuator `UP`, describe both topics, then run the script for 1000 requests. Expected: final count matches confirmed Kafka sends and SQL update count is at least 90% below event count.

- [ ] **Step 3: Verify MySQL outage behavior**

Stop only `novel-mysql`, send a bounded 100 events, and record consumer lag. Restart MySQL in `finally` within the 60-second retry window; wait for healthy and lag drain. Expected: sends succeed while MySQL is down, offset/lag does not falsely clear, no transient record reaches DLT, and the final increment appears after recovery.

- [ ] **Step 4: Verify Kafka outage boundary**

Stop only `novel-kafka`, make one chapter-page request and ten visit requests, then restart Kafka in `finally`. Expected: chapter page still contains `contentIdHidden`, visit endpoint returns within a bounded time, producer failure metric increases, and no synchronous `UPDATE book` is generated by those failed sends.

- [ ] **Step 5: Verify DLT behavior**

Use Kafka CLI to publish one JSON event with an invalid non-positive `bookId`, followed by a valid event. Expected: invalid record reaches `novel-book-visit-dlt` after the configured retries and valid later traffic is processed.

- [ ] **Step 6: Record evidence and commit**

Document exact commands, counts, lag, update count, recovery duration, and any accepted at-least-once duplicate observation.

```powershell
git add -- performance/check-book-visit-kafka.ps1 performance/README.md performance/results/book-visit-kafka-summary.md
git commit -m 'test: verify Kafka book visit recovery paths'
```

---

### Task 9: Run the after-Kafka benchmark and close the learning loop

**Files:**
- Modify: `performance/results/book-visit-kafka-summary.md`
- Modify: `performance/results/chapter-performance-summary.md`
- Modify: `docs/learning/novel-plus-evolution-guide.md`

**Interfaces:**
- Consumes: the exact Task 2 JMeter plan and same book/environment.
- Produces: before/after evidence, documented limitations, and a final verified branch.

- [ ] **Step 1: Run the matching Kafka benchmark**

```powershell
& '.\performance\run-book-visit-stages.ps1' `
  -BookId 2055879962859147264 `
  -Label 'after-kafka' `
  -DurationSeconds 60
```

Wait for consumer lag to become zero before recording the final database count.

- [ ] **Step 2: Build the comparison table**

For every stage record:

```text
threads | accepted req/s | producer-confirmed req/s | P95 | P99 | error % | DB UPDATE count | max lag | drain seconds
```

Calculate SQL reduction as:

```text
1 - (Kafka UPDATE count / synchronous UPDATE count)
```

Do not claim success if HTTP acceptance is high but producer failures or undrained lag hide data loss.

- [ ] **Step 3: Update the learning guide**

Append:

- Kafka terminology: broker, topic, partition, offset, producer, consumer group, lag, DLT.
- Exact code locations for publisher, event, aggregator, writer, consumer, retry config, Docker, tests, and JMeter.
- A worked “10,000 clicks become N UPDATEs” example using measured N.
- The at-least-once crash window and why approximate click counts accept it.
- Commands to inspect topics, consumer lag, DLT, metrics, and Docker logs.

Link the visit result from `chapter-performance-summary.md` without mixing read-path and write-path metrics.

- [ ] **Step 4: Run final verification**

```powershell
& 'C:\Users\26635\.m2\wrapper\dists\apache-maven-3.9.12-bin\5nmfsn99br87k5d4ajlekdq10k\apache-maven-3.9.12\bin\mvn.cmd' `
  -pl novel-front -am '-DskipTests=false' '-Dmaven.test.skip=false' test
git diff --check
git status --short
docker compose -f '.\compose.local.yml' ps
```

Expected: all tests pass, no whitespace errors, MySQL/Redis/Kafka are healthy, application smoke test succeeds, consumer lag is zero, and only intentional report/doc changes remain.

- [ ] **Step 5: Request independent code review**

Review must cover production correctness, batch transaction boundary, offset/retry semantics, DLT routing, producer failure behavior, metrics accuracy, SQL parameter binding, and whether the benchmark supports each claim. Fix all Critical and Important findings, then rerun Step 4.

- [ ] **Step 6: Commit the final evidence**

```powershell
git add -- performance/results/book-visit-kafka-summary.md performance/results/chapter-performance-summary.md docs/learning/novel-plus-evolution-guide.md
git commit -m 'docs: report Kafka visit aggregation performance'
```
