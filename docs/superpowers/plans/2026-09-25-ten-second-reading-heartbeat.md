# Ten-Second Reading Heartbeat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change the reading heartbeat from 30 seconds to 10 seconds without over-crediting, while preserving the existing Redis, Kafka, MySQL aggregation, recommendation, and idempotency architecture.

**Architecture:** The browser emits one heartbeat after each complete 10-second active interval. Redis remains the source of truth for atomic validation and credits exactly 10 seconds per accepted request, while Kafka version 1 is directly redefined to require `creditedSeconds=10`; the batch consumer and MySQL schema remain generic and unchanged. Existing `eventId` deduplication and 14-day cleanup remain intact.

**Tech Stack:** Java 21, Spring Boot 3.4, JavaScript ES modules, Redis Lua, Kafka, MyBatis/MySQL, JUnit 5, AssertJ, Mockito, Node test runner, PowerShell acceptance scripts.

---

## File map

- Browser runtime and mirror:
  - `novel-front/src/main/resources/static/javascript/reading-heartbeat.mjs`
  - `templates/green/static/javascript/reading-heartbeat.mjs`
  - `novel-front/src/test/javascript/reading-heartbeat.test.mjs`
- Server defaults and wire contract:
  - `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEngagementProperties.java`
  - `novel-front/src/main/java/com/java2nb/novel/event/ReadingEngagementEvent.java`
  - `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEventValidator.java`
  - `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEngagementPropertiesTest.java`
  - `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingHeartbeatGateTest.java`
  - `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEngagementServiceTest.java`
  - `novel-front/src/test/java/com/java2nb/novel/event/ReadingEngagementEventTest.java`
  - `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEventValidatorTest.java`
  - `novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementEventPublisherTest.java`
- Redis/Kafka/aggregation behavior:
  - `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingHeartbeatGateRedisIT.java`
  - `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingDailyBatchAggregatorTest.java`
  - `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEventFingerprintTest.java`
  - `novel-front/src/test/java/com/java2nb/novel/messaging/ReadingDailyBatchWriterTest.java`
  - `novel-front/src/test/java/com/java2nb/novel/messaging/ReadingDailyBatchWriterMySqlIT.java`
  - `novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementConsumerTest.java`
  - `novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementKafkaIT.java`
  - `novel-front/src/test/java/com/java2nb/novel/config/ReadingEngagementKafkaConfigTest.java`
- Runtime acceptance and current documentation:
  - `performance/check-reading-engagement.ps1`
  - `performance/check-reading-daily-aggregation.ps1`
  - `performance/test-reading-daily-script.ps1`
  - `performance/README.md`
  - `docs/learning/novel-plus-evolution-guide.md`

Do not modify the MySQL schema, Kafka topics, recommendation queries, dedup cleanup implementation, authentication code, click-count flow, or the user's unrelated YAML changes and bundle files.

### Task 1: Change the browser's default active interval

**Files:**
- Modify: `novel-front/src/test/javascript/reading-heartbeat.test.mjs`
- Modify: `novel-front/src/main/resources/static/javascript/reading-heartbeat.mjs`
- Modify: `templates/green/static/javascript/reading-heartbeat.mjs`

- [ ] **Step 1: Make the default-interval test require 10 seconds**

Keep tests that explicitly inject `intervalMs: 30000`; they verify dependency injection rather than the production default. In `automatic bootstrap preserves snowflake ids as strings`, change only the default bootstrap assertion:

```javascript
assert.equal(environment.timers.pendingDelay(), 10000);
```

- [ ] **Step 2: Run the Node test and verify RED**

Run:

```powershell
& 'C:\Program Files\nodejs\node.exe' --test `
  '.\novel-front\src\test\javascript\reading-heartbeat.test.mjs'
```

Expected: one failure showing an actual delay of `30000` where `10000` was expected.

- [ ] **Step 3: Change the canonical default and production mirror**

In both runtime files use:

```javascript
const DEFAULT_INTERVAL_MS = 10000;
```

Make no changes to focus, visibility, `pagehide`, sequence, Snowflake-string, or fetch behavior. Copy the canonical file content to the production mirror so the existing equality test remains meaningful.

- [ ] **Step 4: Run the Node test and mirror check**

Run the Node command from Step 2. Expected: `10` tests pass and `0` fail.

Run:

```powershell
$canonical = (Get-FileHash '.\novel-front\src\main\resources\static\javascript\reading-heartbeat.mjs' -Algorithm SHA256).Hash
$production = (Get-FileHash '.\templates\green\static\javascript\reading-heartbeat.mjs' -Algorithm SHA256).Hash
if ($canonical -ne $production) { throw 'Heartbeat assets differ' }
```

Expected: no exception.

- [ ] **Step 5: Commit the browser interval**

```powershell
git add -- `
  novel-front/src/main/resources/static/javascript/reading-heartbeat.mjs `
  templates/green/static/javascript/reading-heartbeat.mjs `
  novel-front/src/test/javascript/reading-heartbeat.test.mjs
git commit -m "feat: send reading heartbeats every ten seconds"
```

### Task 2: Change the server credit and rate-limit contract

**Files:**
- Modify: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEngagementProperties.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/event/ReadingEngagementEvent.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEventValidator.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEngagementPropertiesTest.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingHeartbeatGateTest.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEngagementServiceTest.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/event/ReadingEngagementEventTest.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEventValidatorTest.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementEventPublisherTest.java`

- [ ] **Step 1: Write failing assertions for the new defaults and wire contract**

Update `ReadingEngagementPropertiesTest` to assert:

```java
assertThat(properties.sessionLimit()).isEqualTo(8);
assertThat(properties.creditedSeconds()).isEqualTo(10);
```

Update `ReadingHeartbeatGateTest.buildsExactKeysAndArgumentsForTheAtomicRedisTransition` so the expected Redis arguments contain:

```java
"session-hash", "42", "99", "7", "1789000000000", "1788999940000", "8", "120", "1800",
"10", "120", "172800", "abc123:7"
```

Update the event, validator, publisher, and service tests so valid events use `10`, emitted events expose `10`, the credited-seconds metric increases by `10`, and `30` is explicitly rejected by both event creation and consumer validation.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=ReadingEngagementPropertiesTest,ReadingHeartbeatGateTest,ReadingEngagementServiceTest,ReadingEngagementEventTest,ReadingEventValidatorTest,ReadingEngagementEventPublisherTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' `
  '-Dmaven.repo.local=C:\Users\26635\.m2\repository' test
```

Expected: failures report the old defaults `2`/`30` and the old fixed event duration.

- [ ] **Step 3: Centralize and apply the direct-replacement contract**

In `ReadingEngagementEvent` expose the single protocol definition:

```java
public static final int VERSION = 1;
public static final int CREDITED_SECONDS = 10;
```

Require `creditedSeconds == CREDITED_SECONDS`, use an error message containing `creditedSeconds must be 10`, and construct the record with `VERSION`.

In `ReadingEventValidator`, reference the event constants instead of duplicating numeric values:

```java
private static final int SUPPORTED_VERSION = ReadingEngagementEvent.VERSION;
private static final int CREDITED_SECONDS = ReadingEngagementEvent.CREDITED_SECONDS;
```

Its invalid-duration message must contain `creditedSeconds must be 10`.

In `ReadingEngagementProperties`, use:

```java
private int sessionLimit = 8;
private int creditedSeconds = ReadingEngagementEvent.CREDITED_SECONDS;
```

Add the required `ReadingEngagementEvent` import. Do not change `ipLimit`, `dailyCapSeconds`, rate-window duration, or key TTL values.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the Maven command from Step 2. Expected: all selected tests pass with `BUILD SUCCESS`.

- [ ] **Step 5: Commit the server contract**

```powershell
git add -- `
  novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEngagementProperties.java `
  novel-front/src/main/java/com/java2nb/novel/event/ReadingEngagementEvent.java `
  novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEventValidator.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEngagementPropertiesTest.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingHeartbeatGateTest.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEngagementServiceTest.java `
  novel-front/src/test/java/com/java2nb/novel/event/ReadingEngagementEventTest.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEventValidatorTest.java `
  novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementEventPublisherTest.java
git commit -m "feat: credit ten-second reading intervals"
```

### Task 3: Prove Redis atomic limits and batch aggregation with 10-second events

**Files:**
- Modify: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingHeartbeatGateRedisIT.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingDailyBatchAggregatorTest.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEventFingerprintTest.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/messaging/ReadingDailyBatchWriterTest.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/messaging/ReadingDailyBatchWriterMySqlIT.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementConsumerTest.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementKafkaIT.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/config/ReadingEngagementKafkaConfigTest.java`

- [ ] **Step 1: Update Redis integration tests to express the new boundary**

In the sliding-window test, send distinct sequences `1..8` within the same 60-second window and assert all eight are `ACCEPTED`; assert sequence `9` is `SESSION_RATE_LIMITED`. Keep the duplicate sequence assertion before the limit check.

For the cap test, set a local cap of 30 seconds, accept three spaced heartbeats, assert the stored credit is `30`, and assert the fourth spaced heartbeat is `DAILY_CAP_REACHED`:

```java
properties.setDailyCapSeconds(30);
gate = new ReadingHeartbeatGate(redisTemplate, properties, new SimpleMeterRegistry());
```

In the concurrent 30-second-cap test, expect exactly three `ACCEPTED` results, with all other results limited by the daily cap or identified as duplicates, and retain the final Redis value assertion of `30`.

- [ ] **Step 2: Update aggregation fixtures before running tests**

Every fixture representing a valid version-1 reading event in the listed aggregator, writer, consumer, Kafka configuration, and Kafka integration tests must use `creditedSeconds=10`. Adjust expected totals by the number of events, for example:

```java
new ReadingDailyAggregate(firstDate, 41L, 20L, 2L, firstTime, secondTime)
```

for two accepted events, and:

```java
assertCounters(date, bookId, 10L, 1L);
```

for one event. In `ReadingEventFingerprintTest`, make the baseline duration `10` and use `11` as the changed-duration fingerprint case. Do not weaken eventId collision/conflict assertions.

- [ ] **Step 3: Run unit-level aggregation tests**

Run:

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=ReadingDailyBatchAggregatorTest,ReadingEventFingerprintTest,ReadingDailyBatchWriterTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' `
  '-Dmaven.repo.local=C:\Users\26635\.m2\repository' test
```

Expected: all selected tests pass with `BUILD SUCCESS`.

- [ ] **Step 4: Run Redis and MySQL/Kafka integration tests**

Ensure the local Redis container is healthy on `127.0.0.1:6380` with the local test password `123456`. Run the Redis test with its `redis.it` opt-in property:

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=ReadingHeartbeatGateRedisIT' `
  '-Dredis.it=true' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' `
  '-Dmaven.repo.local=C:\Users\26635\.m2\repository' test
```

Expected: the Redis integration class passes, including 8/9 session limiting and three concurrent 10-second credits at a 30-second cap.

Create the isolated local MySQL integration database if it does not exist:

```powershell
docker exec -e MYSQL_PWD=123456 novel-mysql `
  mysql -uroot -e 'CREATE DATABASE IF NOT EXISTS novel_plus_reading_it'
```

Then run the MySQL writer and embedded-Kafka integration tests:

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=ReadingDailyBatchWriterMySqlIT,ReadingEngagementKafkaIT' `
  '-Dnovel.mysql.it=true' `
  '-Dnovel.mysql.username=root' `
  '-Dnovel.mysql.password=123456' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' `
  '-Dmaven.repo.local=C:\Users\26635\.m2\repository' test
```

Expected: both integration classes pass; valid events persist 10 seconds each, replayed event IDs do not increase totals, and the embedded Kafka listener drains all produced records.

- [ ] **Step 5: Commit the integration semantics**

```powershell
git add -- `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingHeartbeatGateRedisIT.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingDailyBatchAggregatorTest.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEventFingerprintTest.java `
  novel-front/src/test/java/com/java2nb/novel/messaging/ReadingDailyBatchWriterTest.java `
  novel-front/src/test/java/com/java2nb/novel/messaging/ReadingDailyBatchWriterMySqlIT.java `
  novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementConsumerTest.java `
  novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementKafkaIT.java `
  novel-front/src/test/java/com/java2nb/novel/config/ReadingEngagementKafkaConfigTest.java
git commit -m "test: cover ten-second reading aggregation"
```

### Task 4: Update acceptance contracts and current learning documentation

**Files:**
- Modify: `performance/check-reading-engagement.ps1`
- Modify: `performance/check-reading-daily-aggregation.ps1`
- Modify: `performance/test-reading-daily-script.ps1`
- Modify: `performance/README.md`
- Modify: `docs/learning/novel-plus-evolution-guide.md`

- [ ] **Step 1: Change acceptance expectations to real 10-second totals**

In `check-reading-engagement.ps1`, two accepted heartbeats must expect:

```powershell
Assert-Deltas $before @{ accepted = 2; duplicate = 1; credited = 20; kafka = 2 }
```

In `check-reading-daily-aggregation.ps1`, emit:

```powershell
creditedSeconds = 10
```

One valid event must assert `10/1`; a replay must keep `10/1`; two distinct valid events for the same book/date must assert `20/2`. Change status text to report `first credit=10/1` and remove the phrase `30-second credit`.

Update `test-reading-daily-script.ps1` mock expectations so it recognizes these exact SQL totals and output strings; keep its bounded-cleanup and failure-drill assertions unchanged.

- [ ] **Step 2: Update active operator and learning guidance**

In `performance/README.md`, replace the manual focus check with: sequence 1 appears only after a complete 10-second interval; leaving after fewer than 10 seconds discards the partial interval; returning starts a new complete 10-second interval.

In `docs/learning/novel-plus-evolution-guide.md`, document 10-second browser intervals, 8 session requests per minute, 120 IP requests per minute, and 10 credited seconds per accepted Kafka event. Do not rewrite historical design specs; the committed `2026-09-25-ten-second-reading-heartbeat-design.md` explicitly supersedes their interval details.

- [ ] **Step 3: Run script contract tests**

Run:

```powershell
& .\performance\test-reading-daily-script.ps1
```

Expected: `Reading daily aggregation script behavior passed.`

Run:

```powershell
& .\performance\test-observability-config.ps1
```

Expected: `Observability configuration contract passed.` Metrics continue to represent actual credited seconds, so no dashboard expression changes are required.

- [ ] **Step 4: Commit acceptance and documentation changes**

```powershell
git add -- `
  performance/check-reading-engagement.ps1 `
  performance/check-reading-daily-aggregation.ps1 `
  performance/test-reading-daily-script.ps1 `
  performance/README.md `
  docs/learning/novel-plus-evolution-guide.md
git commit -m "docs: verify ten-second reading heartbeats"
```

### Task 5: Run full focused regression and production acceptance

**Files:**
- No code changes expected.

- [ ] **Step 1: Run the complete reading-focused Java regression**

Run:

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=ReadingEngagementPropertiesTest,ReadingHeartbeatGateTest,ReadingEngagementServiceTest,ReadingEngagementEventTest,ReadingEventValidatorTest,ReadingEngagementEventPublisherTest,ReadingDailyBatchAggregatorTest,ReadingEventFingerprintTest,ReadingDailyBatchWriterTest,ReadingHeartbeatTemplateTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' `
  '-Dmaven.repo.local=C:\Users\26635\.m2\repository' test
```

Expected: `BUILD SUCCESS`, with zero failures and zero errors.

- [ ] **Step 2: Run JavaScript and repository hygiene checks**

Run the Node test from Task 1, then:

```powershell
git diff --check
git status --short
```

Expected: Node tests pass, `git diff --check` prints no errors, and status contains no uncommitted files from this plan. The user's pre-existing YAML changes and bundle files may remain unstaged and must not be committed.

- [ ] **Step 3: Deploy only after local verification**

Create an incremental bundle from the production server's verified current SHA, upload it, fast-forward the server checkout, rebuild `novel-front:prod`, and recreate only the `novel-front` container. Keep the previous image tagged for rollback.

- [ ] **Step 4: Verify the real production flow**

Force-refresh a readable chapter, keep it visible and focused, and inspect `/engagement/reading/heartbeat` in browser Network tools. Expected: the first HTTP 200 request appears after about 10 seconds, IDs remain decimal strings, and sequence starts at 1.

Query `book_reading_daily`. Expected after consumer drain:

```text
credited_seconds = 10, heartbeat_count = 1
```

After about 30 active seconds:

```text
credited_seconds = 30, heartbeat_count = 3
```

Confirm reading Kafka consumer lag returns to zero and `novel-front` remains healthy. If any result fails, retain the old image and roll back instead of changing unrelated production services.
