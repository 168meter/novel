# Chapter Reading Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a repeatable JMeter baseline for chapter reading, add Redis-backed chapter content caching with hotspot protection and correct invalidation, and publish an evidence-based before/after report.

**Architecture:** Keep the existing modular monolith. A focused `ChapterContentCache` wraps the database loader with Cache-Aside, negative caching, jittered TTL, a Redis mutex, bounded waiting, and safe unlock; `DbBookContentServiceImpl` remains the database adapter and delegates cache policy to that component. JMeter drives the same parameterized chapter page before and after the change, while Actuator exposes local metrics.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Data Redis, MyBatis Dynamic SQL, JUnit 5, Mockito, Maven 3.9.6, MySQL 8, Redis 7, Apache JMeter 5.6.3, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-23-chapter-performance-design.md`

## Global Constraints

- Test `GET /book/{bookId}/{bookIndexId}.html` separately from `POST /book/addVisitCount`.
- Compare results only on the same machine, database, chapter, JVM settings, thread stages, warm-up, and duration.
- Formal load runs use JMeter non-GUI mode; `View Results Tree` is debug-only.
- Do not add Kafka, Elasticsearch, microservices, search, recommendation, or click aggregation in this plan.
- Do not cache or bypass VIP purchase and authorization decisions.
- Run Maven tests with `-Dmaven.test.skip=false -DskipTests=false`, because the parent POM disables tests by default.
- Stage and commit only files named by each task; preserve all pre-existing user changes.
- Acceptance requires at least 90% fewer chapter MySQL queries after warm-up.
- Acceptance requires at least 60% lower stable-interval P99 and at least 3x stable throughput, unless the report proves the load generator or host saturated first.
- Acceptance requires HTTP errors below 0.1%, bounded hotspot backfill, effective negative caching, fresh content after edits, and no indefinite wait during Redis failure.

## File Map

- `performance/jmeter/chapter-baseline.jmx`: parameterized HTTP load model and assertions.
- `performance/run-chapter-stages.ps1`: repeatable 1/10/30/50/100/200-thread runner.
- `performance/README.md`: beginner-facing commands, metrics, and interpretation.
- `performance/results/chapter-performance-summary.md`: committed environment and before/after evidence; generated HTML/JTL stays ignored.
- `novel-front/pom.xml`: Actuator and Prometheus registry dependencies.
- `novel-front/src/main/resources/application-dev.yml`: local-only management endpoint on port 8084.
- `novel-front/src/main/java/com/java2nb/novel/service/cache/ChapterContentCache.java`: cache keys, serialization, locking, waiting, failure fallback, and eviction.
- `novel-front/src/test/java/com/java2nb/novel/service/cache/ChapterContentCacheTest.java`: isolated cache-policy tests.
- `novel-front/src/main/java/com/java2nb/novel/service/impl/DbBookContentServiceImpl.java`: use cache around the existing MySQL loader.
- `novel-front/src/test/java/com/java2nb/novel/service/impl/DbBookContentServiceImplTest.java`: verifies hit/miss delegation and database loading.
- `novel-front/src/main/java/com/java2nb/novel/service/impl/BookServiceImpl.java`: evict content after successful update/delete transaction.
- `.gitignore`: ignore JMeter JTL and generated HTML reports.

---

### Task 1: Add a repeatable JMeter chapter harness

**Files:**
- Create: `performance/jmeter/chapter-baseline.jmx`
- Create: `performance/run-chapter-stages.ps1`
- Create: `performance/README.md`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: application base URL and real `bookId`/`bookIndexId` supplied as JMeter properties.
- Produces: `run-chapter-stages.ps1 -BookId $bookId -BookIndexId $bookIndexId -Label before|after` and per-stage JTL/HTML output.

- [ ] **Step 1: Create the JMeter test plan**

Build a JMX tree containing `Test Plan -> Thread Group -> HTTP Request Defaults -> HTTP Request -> Response Assertion -> Duration Assertion -> Summary Report`. Use these exact properties:

```text
protocol=${__P(protocol,http)}
host=${__P(host,127.0.0.1)}
port=${__P(port,8083)}
threads=${__P(threads,1)}
rampUp=${__P(rampUp,5)}
duration=${__P(duration,60)}
bookId=${__P(bookId,0)}
bookIndexId=${__P(bookIndexId,0)}
path=/book/${bookId}/${bookIndexId}.html
connect_timeout_ms=3000
response_timeout_ms=5000
```

Assert HTTP status `200`, assert the response body does not contain the application's error page marker, and fail samples slower than 5000 ms. Do not embed a Cookie Manager or login because the selected baseline chapter must be free.

- [ ] **Step 2: Create the staged PowerShell runner**

Implement this public parameter contract and fixed stages:

```powershell
param(
    [Parameter(Mandatory = $true)][long]$BookId,
    [Parameter(Mandatory = $true)][long]$BookIndexId,
    [ValidateSet('before','after')][string]$Label,
    [string]$JMeterHome = 'D:\jmeter\apache-jmeter-5.6.3',
    [int]$DurationSeconds = 60
)
$threadStages = @(1, 10, 30, 50, 100, 200)
```

For every stage, call `bin\jmeter.bat -n`, pass all properties with `-J`, write `performance/results/raw/$Label/threads-$threads/result.jtl`, and generate `report/index.html`. Stop immediately when JMeter returns a non-zero exit code. Resolve paths from `$PSScriptRoot`; do not depend on the caller's current directory.

- [ ] **Step 3: Document the exact beginner workflow**

Document GUI debugging with one thread, non-GUI formal execution, the meaning of Threads/QPS/P95/P99/error rate, and this real-ID query:

```sql
SELECT book_id, id AS book_index_id, index_name
FROM book_index
WHERE is_vip = 0
ORDER BY id
LIMIT 10;
```

Document the run command:

```powershell
& '.\performance\run-chapter-stages.ps1' -BookId 123 -BookIndexId 456 -Label before
```

State explicitly that `123` and `456` are replaced with the pair selected from the SQL query.

- [ ] **Step 4: Ignore generated load artifacts**

Append only these patterns:

```gitignore
performance/results/raw/
*.jtl
```

- [ ] **Step 5: Validate the test plan with one thread**

Run after the application is available:

```powershell
& 'D:\jmeter\apache-jmeter-5.6.3\bin\jmeter.bat' -n -t '.\performance\jmeter\chapter-baseline.jmx' -Jthreads=1 -JrampUp=1 -Jduration=10 -JbookId=$bookId -JbookIndexId=$bookIndexId -l "$env:TEMP\novel-jmeter-smoke.jtl"
```

Expected: JMeter exits `0`, summary shows 0 errors, and at least one sample is recorded.

- [ ] **Step 6: Commit the harness**

```powershell
git add -- .gitignore performance/jmeter/chapter-baseline.jmx performance/run-chapter-stages.ps1 performance/README.md
git commit -m "perf: add chapter load test harness"
```

---

### Task 2: Expose local application metrics and capture the unoptimized baseline

**Files:**
- Modify: `novel-front/pom.xml`
- Modify: `novel-front/src/main/resources/application-dev.yml`
- Create: `performance/results/chapter-performance-summary.md`

**Interfaces:**
- Consumes: the Task 1 runner and local Docker MySQL/Redis.
- Produces: health at `http://127.0.0.1:8084/actuator/health`, Prometheus metrics at `/actuator/prometheus`, and the committed baseline environment/results table.

- [ ] **Step 1: Add observability dependencies**

Add these dependencies to `novel-front/pom.xml` without explicit versions:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

- [ ] **Step 2: Restrict development metrics to localhost**

Append to `application-dev.yml`:

```yaml
management:
  server:
    port: 8084
    address: 127.0.0.1
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      application: novel-front
```

- [ ] **Step 3: Verify compilation and management endpoints**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' -pl novel-front -am test -Dmaven.test.skip=false -DskipTests=false
Invoke-RestMethod 'http://127.0.0.1:8084/actuator/health'
Invoke-WebRequest 'http://127.0.0.1:8084/actuator/prometheus' | Select-Object -ExpandProperty StatusCode
```

Expected: Maven exits `0`, health reports `UP`, and Prometheus returns `200`.

- [ ] **Step 4: Start dependencies and select a real free chapter**

```powershell
docker compose -f '.\compose.local.yml' up -d
$row = docker exec novel-mysql mysql -uroot -p123456 -N -B novel_plus -e "SELECT book_id,id FROM book_index WHERE is_vip=0 ORDER BY id LIMIT 1;"
$ids = $row.Trim() -split "`t"
$bookId = [long]$ids[0]
$bookIndexId = [long]$ids[1]
"Selected bookId=$bookId bookIndexId=$bookIndexId"
```

Record one returned `book_id`/`book_index_id` pair and use only that pair in both runs.

- [ ] **Step 5: Run the unoptimized stages**

```powershell
& '.\performance\run-chapter-stages.ps1' -BookId $bookId -BookIndexId $bookIndexId -Label before
```

Expected: all six stage directories exist, each report contains samples, and no stage is silently omitted. If errors begin at a high stage, keep the evidence; that is the measured capacity boundary.

- [ ] **Step 6: Record reproducibility data**

Create the summary with these concrete sections and populate every cell from the machine and JMeter reports:

```markdown
# Chapter Performance Results
## Environment
Windows version, CPU model/core count, total RAM, Java version, JVM arguments, Docker Desktop version, MySQL/Redis images, commit, book ID, chapter ID, duration.
## Before Optimization
| Threads | Throughput req/s | P50 ms | P95 ms | P99 ms | Error % |
## Saturation Point
First stage where throughput flattens while P99/error rate rises; supporting Java/MySQL/Redis observations.
## After Optimization
| Threads | Throughput req/s | P50 ms | P95 ms | P99 ms | Error % |
## Comparison
MySQL query reduction, P99 reduction, stable throughput multiplier, and limiting resource.
```

- [ ] **Step 7: Commit instrumentation and baseline evidence**

```powershell
git add -- novel-front/pom.xml novel-front/src/main/resources/application-dev.yml performance/results/chapter-performance-summary.md
git commit -m "perf: capture chapter reading baseline"
```

---

### Task 3: Implement the isolated chapter cache policy with TDD

**Files:**
- Create: `novel-front/src/main/java/com/java2nb/novel/service/cache/ChapterContentCache.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/service/cache/ChapterContentCacheTest.java`

**Interfaces:**
- Consumes: `StringRedisTemplate`, Spring's `ObjectMapper`, and `Supplier<BookContent>`.
- Produces: `BookContent getOrLoad(Long bookId, Long bookIndexId, Supplier<BookContent> loader)`, `void evict(Long bookId, Long bookIndexId)`, and `void evictAfterCommit(Long bookId, Long bookIndexId)`.

- [ ] **Step 1: Write failing cache behavior tests**

Use JUnit 5 and Mockito. Cover these exact behaviors:

```java
@Test void cacheHitReturnsBookContentWithoutCallingLoader()
@Test void cacheMissLoadsDatabaseAndStoresSerializedContentWithTtl()
@Test void missingContentStoresShortLivedNullMarker()
@Test void nullMarkerReturnsNullWithoutCallingLoader()
@Test void lockWinnerDoubleChecksBeforeLoading()
@Test void lockLoserReadsValueAfterBoundedWaitWithoutCallingLoader()
@Test void unlockUsesTokenCheckingLuaScript()
@Test void redisFailureFallsBackToLoaderWithoutInfiniteRetry()
@Test void evictDeletesOnlyRequestedChapterKey()
@Test void evictAfterCommitDoesNotDeleteBeforeCommitAndDeletesAfterCommit()
@Test void evictAfterCommitDoesNotDeleteAfterRollback()
```

Use deterministic constructor-injected values in tests: content TTL `1800`, jitter bound `300`, null TTL `60`, lock TTL `10`, retry count `10`, retry delay `50 ms`. Inject a sleeper and an `IntSupplier` jitter source so tests do not really sleep and TTL assertions remain exact.

- [ ] **Step 2: Run tests and verify failure**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' -pl novel-front -am -Dtest=ChapterContentCacheTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.skip=false -DskipTests=false test
```

Expected: FAIL because `ChapterContentCache` does not exist.

- [ ] **Step 3: Implement the minimal cache component**

Use these constants and key formats:

```java
static final String CONTENT_KEY_PREFIX = "novel:chapter:v1:";
static final String LOCK_KEY_PREFIX = "novel:chapter:lock:v1:";
static final String NULL_MARKER = "__NULL__";
static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
    "return redis.call('del', KEYS[1]) else return 0 end", Long.class);
```

On a hit, deserialize once and return. On a miss, acquire the lock with `setIfAbsent(lockKey, token, 10, TimeUnit.SECONDS)`, double-check the content key, load once, cache content for `1800 + jitter(0..300)` seconds or `NULL_MARKER` for 60 seconds, and unlock with the Lua script. A loser performs at most ten 50 ms waits; if still empty it calls the loader once. Catch Redis runtime failures at the cache boundary, log one warning, and call the loader once; never recursively retry.

Implement `evictAfterCommit` with `TransactionSynchronizationManager.registerSynchronization` and `afterCommit`; if no transaction synchronization is active, evict immediately.

- [ ] **Step 4: Run focused and module tests**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' -pl novel-front -am -Dtest=ChapterContentCacheTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.skip=false -DskipTests=false test
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' -pl novel-front -am test -Dmaven.test.skip=false -DskipTests=false
```

Expected: all cache tests and module tests PASS.

- [ ] **Step 5: Commit the cache policy**

```powershell
git add -- novel-front/src/main/java/com/java2nb/novel/service/cache/ChapterContentCache.java novel-front/src/test/java/com/java2nb/novel/service/cache/ChapterContentCacheTest.java
git commit -m "feat: add protected chapter content cache"
```

---

### Task 4: Wire cached reads and transaction-safe invalidation

**Files:**
- Modify: `novel-front/src/main/java/com/java2nb/novel/service/impl/DbBookContentServiceImpl.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/service/impl/DbBookContentServiceImplTest.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/service/impl/BookServiceImpl.java`

**Interfaces:**
- Consumes: Task 3 `ChapterContentCache` methods.
- Produces: cached database-backed chapter reads and eviction after successful update/delete commits.

- [ ] **Step 1: Write the failing database adapter tests**

Test these exact interactions:

```java
@Test void queryBookContentDelegatesToCacheWithBookAndIndexIds()
@Test void cacheLoaderReturnsTheSingleMapperResult()
@Test void cacheLoaderReturnsNullWhenMapperFindsNoContent()
```

Capture the `Supplier<BookContent>` passed to `getOrLoad`, execute it, and verify the mapper query is made only when that supplier runs.

- [ ] **Step 2: Run the adapter test and verify failure**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' -pl novel-front -am -Dtest=DbBookContentServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.skip=false -DskipTests=false test
```

Expected: FAIL because `DbBookContentServiceImpl` has not been wired to `ChapterContentCache`.

- [ ] **Step 3: Wrap the existing mapper query**

Inject `ChapterContentCache` and implement this shape:

```java
return chapterContentCache.getOrLoad(bookId, bookIndexId, () -> {
    SelectStatementProvider statement = /* existing select by bookIndexId */;
    return bookContentMapper.selectMany(statement).stream().findFirst().orElse(null);
});
```

Keep the existing ShardingSphere/MyBatis query and storage type dispatch unchanged.

- [ ] **Step 4: Add transaction-safe invalidation to writes**

Inject `ChapterContentCache` into `BookServiceImpl`. After a successful content update, call:

```java
chapterContentCache.evictAfterCommit(bookId, indexId);
```

After a successful chapter deletion, call the same method with the resolved `bookId` and `indexId`. Do not evict for title-only changes, rejected author operations, or rolled-back transactions. New chapters need no eviction because their versioned cache key cannot already contain valid content.

- [ ] **Step 5: Run focused and full front-module tests**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' -pl novel-front -am -Dtest=ChapterContentCacheTest,DbBookContentServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.skip=false -DskipTests=false test
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' -pl novel-front -am test -Dmaven.test.skip=false -DskipTests=false
```

Expected: all tests PASS and no production code accesses a mocked Redis connection during unit tests.

- [ ] **Step 6: Commit read integration and invalidation**

```powershell
git add -- novel-front/src/main/java/com/java2nb/novel/service/impl/DbBookContentServiceImpl.java novel-front/src/main/java/com/java2nb/novel/service/impl/BookServiceImpl.java novel-front/src/test/java/com/java2nb/novel/service/impl/DbBookContentServiceImplTest.java
git commit -m "feat: cache database chapter reads"
```

---

### Task 5: Prove performance, failure behavior, and cache consistency

**Files:**
- Modify: `performance/results/chapter-performance-summary.md`
- Modify: `performance/README.md`

**Interfaces:**
- Consumes: same IDs, runner, JVM, containers, and stage settings recorded in Task 2.
- Produces: final before/after evidence and repeatable fault-test instructions.

- [ ] **Step 1: Verify cache cold/hot behavior without destructive Redis commands**

Delete only the chosen chapter key, request once, then request again:

```powershell
$chapterKey = "novel:chapter:v1:$bookId`:$bookIndexId"
$chapterUrl = "http://127.0.0.1:8083/book/$bookId/$bookIndexId.html"
docker exec novel-redis redis-cli -a 123456 DEL $chapterKey
Invoke-WebRequest $chapterUrl | Select-Object -ExpandProperty StatusCode
Invoke-WebRequest $chapterUrl | Select-Object -ExpandProperty StatusCode
docker exec novel-redis redis-cli -a 123456 TTL $chapterKey
```

Expected: both requests return `200`; the key TTL is between 1 and 2100 seconds.

- [ ] **Step 2: Run the optimized stages**

```powershell
& '.\performance\run-chapter-stages.ps1' -BookId $bookId -BookIndexId $bookIndexId -Label after
```

Expected: all six stages complete and use the same IDs and duration recorded for `before`.

- [ ] **Step 3: Verify hotspot protection and negative caching**

Evict only the chosen key, start a 100-thread short run against that chapter, and compare MySQL statement activity with the normal miss baseline. Request a deliberately nonexistent content ID through a focused integration test twice and verify its loader is called once while the null marker exists. Do not use `FLUSHALL` or delete unrelated Redis keys.

- [ ] **Step 4: Verify update/delete invalidation**

Run integration tests that begin a transaction, call `evictAfterCommit`, and assert no delete occurs before commit; commit and assert one delete. Run the rollback variant and assert no delete. Confirm an actual author content update returns the new text on the next read.

- [ ] **Step 5: Verify Redis failure is bounded**

With the application running, stop only Redis, make a single chapter request, record response time and result, then restart Redis:

```powershell
docker stop novel-redis
Measure-Command { Invoke-WebRequest $chapterUrl }
docker start novel-redis
```

Expected: the request either succeeds through the database fallback or fails within the configured 5-second client limit; it must not wait indefinitely. Do not run a high-concurrency Redis-outage test until database protection is added in a later resilience phase.

- [ ] **Step 6: Complete the evidence report**

Populate every before/after row from JMeter `statistics.json`. Calculate:

```text
P99 reduction % = (before P99 - after P99) / before P99 * 100
Throughput multiplier = after stable req/s / before stable req/s
MySQL reduction % = (before chapter queries - after chapter queries) / before chapter queries * 100
```

State whether each spec threshold passed. If a threshold fails, report the measured value and limiting resource; do not rewrite the result as success.

- [ ] **Step 7: Run final verification**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' clean test -Dmaven.test.skip=false -DskipTests=false
git diff --check
git status --short
```

Expected: tests PASS, `git diff --check` prints nothing, and status contains only intentionally changed project files plus the user's pre-existing changes.

- [ ] **Step 8: Commit the final report**

```powershell
git add -- performance/README.md performance/results/chapter-performance-summary.md
git commit -m "docs: report chapter cache performance"
```
