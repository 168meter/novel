# Reading Daily Aggregation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consume privacy-safe reading heartbeat events from Kafka and persist strictly idempotent per-book, per-day aggregates in MySQL.

**Architecture:** A dedicated batch listener validates reading events and calls a separate transactional writer. The writer bulk-registers event IDs plus payload fingerprints, selects rows owned by its batch token, aggregates only new events, and batch-upserts daily totals in the same transaction; reading retries and DLT remain isolated from book visits.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Kafka, Spring Transactions, MyBatis XML, MySQL 8/H2 MySQL mode, Micrometer, Prometheus, Grafana, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-11-reading-daily-aggregation-design.md`

## Global Constraints

- Aggregate only by `Asia/Shanghai stat_date + book_id`; one valid event is exactly 30 seconds and one heartbeat.
- `event_id` is the idempotency key; conflicting payloads for one ID go to `novel-reading-engagement-dlt`.
- Dedup registration and daily UPSERT share one transaction; process at most 500 records with bulk SQL.
- Retain dedup rows 14 days and delete in independently committed batches of 5000.
- Never persist identity, Cookie, IP, session hash, `pageVisitId` or chapter trajectory; never use them as metric labels.
- Preserve the existing `BookVisitEvent` consumer and `novel-book-visit-dlt`.
- Never modify or stage the user-owned admin dev/prod YAML or `novel-common/src/main/resources/application-common-dev.yml`.
- Every production change starts with a focused failing test and ends with focused verification.

---

### Task 1: MySQL Schema and Bulk Mapper

**Files:**
- Create: `doc/sql/20260911_reading_daily_aggregation.sql`
- Create: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingDedupRecord.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingDedupState.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingDailyAggregate.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/mapper/ReadingAggregationMapper.java`
- Create: `novel-front/src/main/resources/mybatis/mapping/ReadingAggregationMapper.xml`
- Test: `novel-front/src/test/java/com/java2nb/novel/mapper/ReadingAggregationMapperTest.java`

**Interfaces:**
- Produces `insertDedupRecords(List<ReadingDedupRecord>)`, `findDedupStates(List<String>)`, `upsertDailyAggregates(List<ReadingDailyAggregate>)`, `deleteDedupBefore(LocalDateTime,int)`.

- [ ] **Step 1: Write the failing H2 MySQL-mode mapper test**

Follow `FrontBookIndexMapperTest`: local `SqlSessionFactory`, explicit schema setup. Assert duplicate insert returns zero, daily UPSERT adds counters, first/last times use min/max, and cleanup deletes only old rows.

```java
assertThat(mapper.insertDedupRecords(List.of(dedup))).isEqualTo(1);
assertThat(mapper.insertDedupRecords(List.of(dedup))).isZero();
mapper.upsertDailyAggregates(List.of(daily(42L, 30L)));
mapper.upsertDailyAggregates(List.of(daily(42L, 30L)));
assertThat(readDaily(42L)).containsExactly(60L, 2L);
```

- [ ] **Step 2: Run RED**

```powershell
mvn -pl novel-front -am -Dtest=ReadingAggregationMapperTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: missing record/mapper compilation errors.

- [ ] **Step 3: Add DDL and exact records**

Use `CREATE TABLE IF NOT EXISTS` and spec section 4. Records:

```java
record ReadingDedupRecord(String eventId, byte[] eventFingerprint,
                          String batchToken, LocalDate statDate) {}
record ReadingDedupState(String eventId, byte[] eventFingerprint,
                         String batchToken) {}
record ReadingDailyAggregate(LocalDate statDate, long bookId,
    long creditedSeconds, long heartbeatCount,
    Instant firstEventAt, Instant lastEventAt) {}
```

- [ ] **Step 4: Implement bound bulk SQL**

```java
int insertDedupRecords(@Param("records") List<ReadingDedupRecord> records);
List<ReadingDedupState> findDedupStates(@Param("eventIds") List<String> ids);
int upsertDailyAggregates(@Param("aggregates") List<ReadingDailyAggregate> rows);
int deleteDedupBefore(@Param("cutoff") LocalDateTime cutoff,
                      @Param("limit") int limit);
```

Use one MyBatis `foreach` per bulk statement, `INSERT IGNORE`, additive `ON DUPLICATE KEY UPDATE`, `LEAST/GREATEST`, and bound `LIMIT #{limit}`. Do not use `${}`.

- [ ] **Step 5: Run GREEN and commit**

```powershell
mvn -pl novel-front -am -Dtest=ReadingAggregationMapperTest -Dsurefire.failIfNoSpecifiedTests=false test
rg -n "\$\{" novel-front/src/main/resources/mybatis/mapping/ReadingAggregationMapper.xml
git add -- doc/sql/20260911_reading_daily_aggregation.sql novel-front/src/main/java/com/java2nb/novel/engagement/ReadingDedupRecord.java novel-front/src/main/java/com/java2nb/novel/engagement/ReadingDedupState.java novel-front/src/main/java/com/java2nb/novel/engagement/ReadingDailyAggregate.java novel-front/src/main/java/com/java2nb/novel/mapper/ReadingAggregationMapper.java novel-front/src/main/resources/mybatis/mapping/ReadingAggregationMapper.xml novel-front/src/test/java/com/java2nb/novel/mapper/ReadingAggregationMapperTest.java
git commit -m "feat: add reading aggregation schema"
```

---

### Task 2: Dedicated Reading Kafka Configuration

**Files:**
- Create: `novel-front/src/main/java/com/java2nb/novel/config/ReadingEngagementKafkaProperties.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/config/ReadingEngagementKafkaConfig.java`
- Modify: `ReadingEngagementConfig.java`, `ReadingEngagementProperties.java`, `ReadingEngagementEventPublisher.java`
- Test: new Kafka properties/config tests; modify existing reading config/properties/publisher tests.

**Interfaces:**
- Beans: `readingEngagementConsumerFactory`, `readingEngagementKafkaListenerContainerFactory`, `readingEngagementErrorHandler`.

- [ ] **Step 1: Write failing default/isolation tests**

Require topic `novel-reading-engagement-v1`, DLT `novel-reading-engagement-dlt`, group `novel-reading-engagement-writer-v1`, poll 500, 5-second retry, 12 retries, 14-day retention, cleanup 5000, cron `0 15 3 * * *`. Require value type `ReadingEngagementEvent`, batch listener, `AckMode.BATCH`, and no book-visit DLT reference.

- [ ] **Step 2: Run RED**

```powershell
mvn -pl novel-front -am "-Dtest=ReadingEngagementKafkaPropertiesTest,ReadingEngagementKafkaConfigTest,ReadingEngagementConfigTest,ReadingEngagementPropertiesTest,ReadingEngagementEventPublisherTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Implement validated defaults**

Create mutable `@ConfigurationProperties("novel.kafka.reading-engagement")` fields with the exact values above. Validate nonblank names, poll 1–500, positive durations/retries, cleanup 1–5000. Remove only topic from Redis/security `ReadingEngagementProperties`.

- [ ] **Step 4: Implement isolated factory/error handler**

Build from Spring `KafkaProperties`, overriding:

```java
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, reading.maxPollRecords());
props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ReadingEngagementEvent.class.getName());
props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.java2nb.novel.event");
```

Use `DefaultKafkaConsumerFactory<Long,ReadingEngagementEvent>`, batch/BATCH mode, and only the reading error handler. Use `FixedBackOff(5000,12)`; route to the same partition of reading DLT; mark `IllegalArgumentException` and `ReadingEventConflictException` non-retryable. Emit exact counters `novel.reading.kafka.retry`, `novel.reading.kafka.dlt`, and `novel.reading.kafka.dlt_publish_failures`. Configure `DeadLetterPublishingRecoverer` to wait for the send result and fail when the DLT send fails; catch that failure only to increment the publish-failure counter, then rethrow so the record is not acknowledged as recovered.

- [ ] **Step 5: Move publisher topic ownership**

Inject Kafka properties and call `kafkaTemplate.send(kafkaProperties.topic(), bookId, event)`. New config creates main and DLT topics; old config retains general engagement property registration. Do not edit protected YAML.

- [ ] **Step 6: Run GREEN and commit**

```powershell
mvn -pl novel-front -am "-Dtest=ReadingEngagementKafkaPropertiesTest,ReadingEngagementKafkaConfigTest,ReadingEngagementConfigTest,ReadingEngagementPropertiesTest,ReadingEngagementEventPublisherTest,BookVisitKafkaConfigTest,BookVisitEventConsumerTest" -Dsurefire.failIfNoSpecifiedTests=false test
git add -- novel-front/src/main/java/com/java2nb/novel/config/ReadingEngagementKafkaProperties.java novel-front/src/main/java/com/java2nb/novel/config/ReadingEngagementKafkaConfig.java novel-front/src/main/java/com/java2nb/novel/config/ReadingEngagementConfig.java novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEngagementProperties.java novel-front/src/main/java/com/java2nb/novel/messaging/ReadingEngagementEventPublisher.java novel-front/src/test/java/com/java2nb/novel/config/ReadingEngagementKafkaPropertiesTest.java novel-front/src/test/java/com/java2nb/novel/config/ReadingEngagementKafkaConfigTest.java novel-front/src/test/java/com/java2nb/novel/config/ReadingEngagementConfigTest.java novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEngagementPropertiesTest.java novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementEventPublisherTest.java
git commit -m "feat: isolate reading kafka configuration"
```

---

### Task 3: Event Validation, Fingerprint and Aggregation

**Files:**
- Create under `engagement/`: `ReadingEventValidator.java`, `ReadingEventFingerprint.java`, `ReadingDailyBatchAggregator.java`, `ReadingEventConflictException.java`
- Test: matching validator, fingerprint and aggregator tests.

**Interfaces:** `validate(event)`, `fingerprint(event)`, `aggregate(events)`, and `ReadingEventConflictException.eventId()`.

- [ ] **Step 1: Write failing tests**

Reject null, malformed UUID, nonpositive IDs, seconds != 30, null time/date, version != 1, and date unequal to `occurredAt.atZone(Asia/Shanghai).toLocalDate()`. Assert stable 32-byte SHA-256, every business-field mutation changes it, correct date/book grouping, min/max times, deterministic order, and `Math.addExact` overflow.

- [ ] **Step 2: Run RED**

```powershell
mvn -pl novel-front -am "-Dtest=ReadingEventValidatorTest,ReadingEventFingerprintTest,ReadingDailyBatchAggregatorTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Implement pure components**

Fingerprint newline-delimited UTF-8 fields in fixed order: version, event ID, book ID, chapter ID, seconds, ISO instant, ISO date. Never log canonical content. Aggregate with a private `(LocalDate,long)` key and return sorted date/book rows.

- [ ] **Step 4: Run GREEN and commit**

```powershell
mvn -pl novel-front -am "-Dtest=ReadingEventValidatorTest,ReadingEventFingerprintTest,ReadingDailyBatchAggregatorTest" -Dsurefire.failIfNoSpecifiedTests=false test
git add -- novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEventValidator.java novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEventFingerprint.java novel-front/src/main/java/com/java2nb/novel/engagement/ReadingDailyBatchAggregator.java novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEventConflictException.java novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEventValidatorTest.java novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEventFingerprintTest.java novel-front/src/test/java/com/java2nb/novel/engagement/ReadingDailyBatchAggregatorTest.java
git commit -m "feat: validate reading aggregation events"
```

---

### Task 4: Transactional Idempotent Batch Writer

**Files:**
- Create: `ReadingBatchWriteResult.java`, `messaging/ReadingDailyBatchWriter.java`
- Test: `ReadingDailyBatchWriterTest.java`, opt-in `ReadingDailyBatchWriterMySqlIT.java`

**Interface:** `ReadingBatchWriteResult write(List<ReadingEngagementEvent>)` reporting received/new/deduplicated events, daily rows and persisted seconds.

- [ ] **Step 1: Write failing unit tests**

Cover empty, all-new, prior duplicate, same-batch duplicate, same-batch conflict, stored fingerprint conflict and missing mapper state. Verify one bulk insert, one query and at most one UPSERT. Require `@Transactional(rollbackFor=Exception.class)`.

- [ ] **Step 2: Run RED**

```powershell
mvn -pl novel-front -am -Dtest=ReadingDailyBatchWriterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Implement one-transaction algorithm**

Deduplicate into `LinkedHashMap`; reject unequal fingerprints; generate one batch UUID; bulk insert; bulk select every ID; compare with `MessageDigest.isEqual`; treat only current-token states as new; aggregate/upsert only new events; calculate counts with `Math.addExact`. Missing state throws.

- [ ] **Step 4: Add opt-in real MySQL test**

Use `@EnabledIfSystemProperty(named="novel.mysql.it",matches="true")` and local port 3307. Verify replay, date/book split, concurrent same-ID writes and rollback. For rollback, call through a Spring transaction proxy with a test mapper decorator that throws after dedup insertion; neither table may retain the event. Use reserved IDs and exact cleanup.

- [ ] **Step 5: Run GREEN and commit**

```powershell
mvn -pl novel-front -am -Dtest=ReadingDailyBatchWriterTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl novel-front -am -Dnovel.mysql.it=true -Dtest=ReadingDailyBatchWriterMySqlIT -Dsurefire.failIfNoSpecifiedTests=false test
git add -- novel-front/src/main/java/com/java2nb/novel/engagement/ReadingBatchWriteResult.java novel-front/src/main/java/com/java2nb/novel/messaging/ReadingDailyBatchWriter.java novel-front/src/test/java/com/java2nb/novel/messaging/ReadingDailyBatchWriterTest.java novel-front/src/test/java/com/java2nb/novel/messaging/ReadingDailyBatchWriterMySqlIT.java
git commit -m "feat: persist reading batches idempotently"
```

---

### Task 5: Indexed Consumer and Reading DLT

**Files:**
- Create: `novel-front/src/main/java/com/java2nb/novel/messaging/ReadingEngagementConsumer.java`
- Test: `ReadingEngagementConsumerTest.java`, `ReadingEngagementKafkaIT.java`

- [ ] **Step 1: Write failing consumer tests**

Require listener topic/group with `containerFactory="readingEngagementKafkaListenerContainerFactory"`. For `[valid,invalid,later]`, require `BatchListenerFailedException` index 1 and no writer call. Translate writer conflict ID to the original index. Increment committed metrics only after writer success.

- [ ] **Step 2: Run RED**

```powershell
mvn -pl novel-front -am -Dtest=ReadingEngagementConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Implement indexed processing**

Validate in index order, wrap invalid records with their index, invoke injected `ReadingDailyBatchWriter` once, and translate conflict IDs to indices. Emit consumed, deduplicated, persisted seconds, daily rows and batch size from the returned result only.

- [ ] **Step 4: Add Embedded Kafka isolation test**

Send valid reading, malformed reading JSON, later valid reading and one visit event. With bounded polling, prove two readings reach only the reading writer, malformed JSON reaches only reading DLT, and the visit reaches only its existing writer.

- [ ] **Step 5: Run GREEN and commit**

```powershell
mvn -pl novel-front -am "-Dtest=ReadingEngagementKafkaIT,ReadingEngagementConsumerTest,BookVisitEventConsumerTest,BookVisitKafkaConfigTest" -Dsurefire.failIfNoSpecifiedTests=false test
git add -- novel-front/src/main/java/com/java2nb/novel/messaging/ReadingEngagementConsumer.java novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementConsumerTest.java novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementKafkaIT.java
git commit -m "feat: consume reading engagement batches"
```

---

### Task 6: Bounded Dedup Cleanup

**Files:** Create `ReadingDedupCleanupBatch.java`, `ReadingDedupCleanupJob.java` and matching tests under `messaging/`.

- [ ] **Step 1: Write failing tests**

Fixed instant `2026-09-11T04:00:00Z` yields Shanghai cutoff `2026-08-28T12:00`. Results `5000,5000,27` cause three calls; `4999` causes one. A second-call exception increments failure metric and does not escape scheduled method. Reflectively require `Propagation.REQUIRES_NEW` on one-batch delete.

- [ ] **Step 2: Run RED**

```powershell
mvn -pl novel-front -am "-Dtest=ReadingDedupCleanupBatchTest,ReadingDedupCleanupJobTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Implement and verify**

Calculate cutoff with fixed clock minus 14 days in `Asia/Shanghai`; loop while count == configured 5000; record deleted/failure counters; log only exception class and completed batches; schedule configured cron with Shanghai zone.

```powershell
mvn -pl novel-front -am "-Dtest=ReadingDedupCleanupBatchTest,ReadingDedupCleanupJobTest,ReadingAggregationMapperTest" -Dsurefire.failIfNoSpecifiedTests=false test
git add -- novel-front/src/main/java/com/java2nb/novel/messaging/ReadingDedupCleanupBatch.java novel-front/src/main/java/com/java2nb/novel/messaging/ReadingDedupCleanupJob.java novel-front/src/test/java/com/java2nb/novel/messaging/ReadingDedupCleanupBatchTest.java novel-front/src/test/java/com/java2nb/novel/messaging/ReadingDedupCleanupJobTest.java
git commit -m "feat: expire reading dedup records"
```

---

### Task 7: Persistence Monitoring

**Files:** Modify Prometheus rules, Grafana overview, `test-observability-config.ps1`, and `check-observability.ps1`.

- [ ] **Step 1: Extend static contract and run RED**

Require panels for persisted seconds, deduplicated events, daily rows and retry/DLT. The retry/DLT panel has separate expressions `sum(rate(novel_reading_kafka_retry_total[$__rate_interval]))` and `sum(rate(novel_reading_kafka_dlt_total[$__rate_interval]))`. Require alerts `ReadingConsumerLagHigh`, `ReadingConsumerDltGrowth`, `ReadingConsumerRetries`, `ReadingConsumerDltPublishFailures`, and `ReadingDedupCleanupFailures`.

```powershell
& .\performance\test-observability-config.ps1
```

- [ ] **Step 2: Add exact metrics/rules**

Use `novel_reading_kafka_persisted_seconds_total`, `novel_reading_kafka_deduplicated_total`, `novel_reading_kafka_daily_rows_updated_total`, `novel_reading_kafka_retry_total`, `novel_reading_kafka_dlt_total`, `novel_reading_kafka_dlt_publish_failures_total` and cleanup metrics. Lag >10000 for 2m; any DLT increase/5m; retries positive for 1m; any DLT-publish failure/5m; any cleanup failure/24h. Use unique, nonoverlapping Grafana panels.

- [ ] **Step 3: Verify and commit**

```powershell
& .\performance\test-observability-config.ps1
docker run --rm --entrypoint /bin/promtool -v "${PWD}/monitoring/prometheus:/etc/prometheus:ro" prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml
git add -- monitoring/prometheus/rules/novel-plus-alerts.yml monitoring/grafana/dashboards/novel-plus-overview.json performance/test-observability-config.ps1 performance/check-observability.ps1
git commit -m "feat: monitor reading daily aggregation"
```

---

### Task 8: Safe Local Acceptance and Learning Docs

**Files:**
- Create: `performance/apply-reading-aggregation-schema.ps1`
- Create: `performance/check-reading-daily-aggregation.ps1`
- Modify: `performance/README.md`, learning guide, observability contract.

- [ ] **Step 1: Add failing script contracts**

Require both scripts to parse, exact-ID cleanup, reading topic/DLT/group names, and reject broad daily-table deletes. Run contract and expect missing-file failure.

- [ ] **Step 2: Implement schema application**

Resolve root from `$PSScriptRoot`, require healthy `novel-mysql`, and pipe only the dated SQL into its MySQL client. Suppress the known password warning without hiding a nonzero exit; do not print the password in custom output.

- [ ] **Step 3: Implement ordinary/DLT checks**

Generate UUID/keyed JSON and poll with deadlines. Prove first delta = 30/1, identical replay delta = 0, different book/date split, invalid version DLT delta = 1, later valid event persists, lag = 0. Capture/restore any preexisting exact daily rows and delete only generated IDs in `finally`.

- [ ] **Step 4: Implement MySQL failure drill**

With `-FailureDrill MySql`, stop only MySQL, publish one ID, require retry metric growth, restore healthy in `finally`, then require exactly one 30-second credit.

- [ ] **Step 5: Document and commit**

Explain schema application, drills, transaction proxy, batch token, fingerprint, 14-day retention and strict reading dedup versus approximate clicks.

```powershell
& .\performance\test-observability-config.ps1
git add -- performance/apply-reading-aggregation-schema.ps1 performance/check-reading-daily-aggregation.ps1 performance/README.md docs/learning/novel-plus-evolution-guide.md performance/test-observability-config.ps1
git commit -m "test: verify reading daily aggregation"
```

---

### Task 9: Full Regression and Live Verification

- [ ] **Step 1: Run all automated tests**

```powershell
mvn -pl novel-front -am test
node --test novel-front/src/test/javascript/reading-heartbeat.test.mjs
```

Require zero Java failures/errors and all 9 Node tests; record the new Java count.

- [ ] **Step 2: Start and apply**

```powershell
docker compose -f .\compose.local.yml up -d mysql redis kafka prometheus grafana
& .\performance\apply-reading-aggregation-schema.ps1
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
& .\performance\start-front-monitoring.ps1
```

Keep the foreground app in one PowerShell window.

- [ ] **Step 3: Run new acceptance in a second window**

```powershell
& .\performance\check-reading-daily-aggregation.ps1 -BookId 2055879962859147264
& .\performance\check-reading-daily-aggregation.ps1 -BookId 2055879962859147264 -FailureDrill Dlt
& .\performance\check-reading-daily-aggregation.ps1 -BookId 2055879962859147264 -FailureDrill MySql
```

- [ ] **Step 4: Run existing regressions**

```powershell
& .\performance\check-reading-engagement.ps1 -BookId 2055879962859147264
& .\performance\check-book-visit-kafka.ps1 -BookId 2055879962859147264 -RequestCount 1000 -MaxConcurrency 100
& .\performance\check-observability.ps1 -GrafanaUser 'admin' -GrafanaPassword '123456'
& .\performance\test-observability-config.ps1
```

Require reading producer PASS, click delta 1000, both lags 0 and monitoring PASS.

- [ ] **Step 5: Validate final state**

```powershell
docker run --rm --entrypoint /bin/promtool -v "${PWD}/monitoring/prometheus:/etc/prometheus:ro" prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml
git diff --check
git status --short
```

Only the protected YAML changes may remain outside intended commits. On failure, use `superpowers:systematic-debugging`: preserve evidence, reproduce, add a focused failing test, apply the smallest fix and rerun adjacent regression. Commit a final fix checkpoint only if real files changed; otherwise create no empty commit.
