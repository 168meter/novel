# Snowflake-Safe Reading Heartbeat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make production chapter heartbeats preserve 19-digit book and chapter IDs exactly from the DOM through JSON so valid reading time reaches Kafka and `book_reading_daily`.

**Architecture:** Keep both IDs as validated decimal strings in the browser and retain the existing `Long` request fields in Java. Update the canonical static module and its production external-template mirror together; use Node tests to prove exact string transport and MockMvc to prove Jackson binds those strings without precision loss.

**Tech Stack:** JavaScript ES modules, Node test runner, Spring MVC/Jackson, JUnit 5, Mockito, Maven, Docker Compose.

---

## File map

- Modify `novel-front/src/main/resources/static/javascript/reading-heartbeat.mjs`: remove unsafe numeric conversion from DOM bootstrap.
- Modify `templates/green/static/javascript/reading-heartbeat.mjs`: keep the production external static asset identical to the canonical module.
- Modify `novel-front/src/test/javascript/reading-heartbeat.test.mjs`: reproduce the production failure with 19-digit IDs and lock exact JSON transport plus mirror equality.
- Modify `novel-front/src/test/java/com/java2nb/novel/controller/ReadingEngagementControllerTest.java`: lock Jackson string-to-`Long` binding and out-of-range rejection.

### Task 1: Reproduce the browser precision failure

**Files:**
- Modify: `novel-front/src/test/javascript/reading-heartbeat.test.mjs`

- [ ] **Step 1: Replace the small-ID automatic bootstrap test with a real snowflake-ID transport test**

Add constants near `PAYLOAD`:

```javascript
const SNOWFLAKE_BOOK_ID = '2055879962859147264';
const SNOWFLAKE_CHAPTER_ID = '2055880123456789012';
```

Replace `automatic bootstrap starts with valid element data` with:

```javascript
test('automatic bootstrap preserves snowflake ids as strings', async () => {
    const fetchCalls = [];
    const environment = createEnvironment({
        fetchFn: (url, options) => {
            fetchCalls.push({url, options});
            return Promise.resolve();
        }
    });
    environment.documentRef.getElementById = (id) => id === 'reading-engagement' ? {
        dataset: {
            bookId: SNOWFLAKE_BOOK_ID,
            chapterId: SNOWFLAKE_CHAPTER_ID,
            pageVisitId: '0123456789abcdef0123456789abcdef'
        }
    } : null;

    await withBrowserGlobals(environment, () =>
        import(`../../main/resources/static/javascript/reading-heartbeat.mjs?bootstrap-snowflake=${Date.now()}`));

    assert.equal(environment.timers.pendingDelay(), 30000);
    environment.timers.runNext();
    assert.equal(fetchCalls.length, 1);
    const body = JSON.parse(fetchCalls[0].options.body);
    assert.equal(body.bookId, SNOWFLAKE_BOOK_ID);
    assert.equal(body.chapterId, SNOWFLAKE_CHAPTER_ID);
    assert.equal(typeof body.bookId, 'string');
    assert.equal(typeof body.chapterId, 'string');
});
```

- [ ] **Step 2: Run the Node test and verify RED**

```powershell
& 'C:\Program Files\nodejs\node.exe' --test `
  '.\novel-front\src\test\javascript\reading-heartbeat.test.mjs'
```

Expected: FAIL at `pendingDelay()` because the current `Number.isSafeInteger` checks reject both 19-digit IDs and never schedule a heartbeat.

### Task 2: Preserve IDs as strings in both runtime copies

**Files:**
- Modify: `novel-front/src/main/resources/static/javascript/reading-heartbeat.mjs`
- Modify: `templates/green/static/javascript/reading-heartbeat.mjs`
- Modify: `novel-front/src/test/javascript/reading-heartbeat.test.mjs`

- [ ] **Step 1: Remove all browser numeric conversion from bootstrap payload creation**

Replace the final portion of `readBootstrapPayload`:

```javascript
    const parsedBookId = Number(bookId);
    const parsedChapterId = Number(chapterId);
    if (!Number.isSafeInteger(parsedBookId) || !Number.isSafeInteger(parsedChapterId)) {
        return null;
    }

    return {bookId: parsedBookId, chapterId: parsedChapterId, pageVisitId};
```

with:

```javascript
    return {bookId, chapterId, pageVisitId};
```

Keep `POSITIVE_INTEGER_PATTERN` and `PAGE_VISIT_ID_PATTERN` unchanged so zero, signs, decimals and non-digits remain invalid.

- [ ] **Step 2: Synchronize the production external static asset**

```powershell
Copy-Item -Force `
  '.\novel-front\src\main\resources\static\javascript\reading-heartbeat.mjs' `
  '.\templates\green\static\javascript\reading-heartbeat.mjs'
```

- [ ] **Step 3: Add a production mirror contract to the Node test**

Add this import:

```javascript
import {readFile} from 'node:fs/promises';
```

Add this test:

```javascript
test('production external heartbeat asset matches the canonical module', async () => {
    const canonical = await readFile(new URL(
        '../../main/resources/static/javascript/reading-heartbeat.mjs', import.meta.url), 'utf8');
    const production = await readFile(new URL(
        '../../../../templates/green/static/javascript/reading-heartbeat.mjs', import.meta.url), 'utf8');

    assert.equal(production, canonical);
});
```

- [ ] **Step 4: Run all heartbeat JavaScript tests and verify GREEN**

```powershell
& 'C:\Program Files\nodejs\node.exe' --test `
  '.\novel-front\src\test\javascript\reading-heartbeat.test.mjs'
```

Expected: every test passes; the snowflake test observes the exact original decimal strings after `JSON.parse`.

### Task 3: Lock the Spring/Jackson binding contract

**Files:**
- Modify: `novel-front/src/test/java/com/java2nb/novel/controller/ReadingEngagementControllerTest.java`

- [ ] **Step 1: Add a controller test for quoted snowflake IDs**

Add:

```java
@Test
void snowflakeIdStringsBindToLongWithoutPrecisionLoss() throws Exception {
    long bookId = 2055879962859147264L;
    long chapterId = 2055880123456789012L;
    ReadingHeartbeatRequest expectedRequest =
        new ReadingHeartbeatRequest(bookId, chapterId, PAGE_VISIT_ID, 1L);
    when(clientAddressResolver.resolve(any(HttpServletRequest.class))).thenReturn("203.0.113.9");
    when(engagementService.handle(expectedRequest, "browser-user-mark", "203.0.113.9"))
        .thenReturn(ReadingHeartbeatOutcome.ACCEPTED);

    try (MockedStatic<ThreadLocalUtil> threadLocal = mockStatic(ThreadLocalUtil.class)) {
        threadLocal.when(ThreadLocalUtil::getClientId).thenReturn("browser-user-mark");

        mockMvc.perform(post("/engagement/reading/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"bookId":"2055879962859147264","chapterId":"2055880123456789012",
                     "pageVisitId":"0123456789abcdef0123456789abcdef","sequence":1}
                    """))
            .andExpect(status().isOk());
    }

    verify(engagementService)
        .handle(expectedRequest, "browser-user-mark", "203.0.113.9");
}
```

- [ ] **Step 2: Add an out-of-range decimal string to the malformed request cases**

Append to `@ValueSource(strings = {...})`:

```java
"{\"bookId\":\"9223372036854775808\",\"chapterId\":7,\"pageVisitId\":\"0123456789abcdef0123456789abcdef\",\"sequence\":3}"
```

This is one greater than `Long.MAX_VALUE`; MockMvc must return 400 and must not call the engagement service.

- [ ] **Step 3: Run the focused controller contract**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=ReadingEngagementControllerTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: the quoted 19-digit IDs produce the exact Java `long` values, while the out-of-range value returns HTTP 400.

### Task 4: Run regression checks and commit the fix

**Files:**
- Verify all four files listed in the file map.

- [ ] **Step 1: Run focused JavaScript and Java regressions**

```powershell
& 'C:\Program Files\nodejs\node.exe' --test `
  '.\novel-front\src\test\javascript\reading-heartbeat.test.mjs'

& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=ReadingEngagementControllerTest,ReadingEngagementServiceTest,ReadingHeartbeatTemplateTest,ReadingHeartbeatGateTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: Node and all named Java tests pass with zero failures and zero errors.

- [ ] **Step 2: Verify the production asset, formatting and scope**

```powershell
$canonical = Get-FileHash '.\novel-front\src\main\resources\static\javascript\reading-heartbeat.mjs'
$production = Get-FileHash '.\templates\green\static\javascript\reading-heartbeat.mjs'
if ($canonical.Hash -ne $production.Hash) { throw 'Production heartbeat asset differs from canonical module.' }

git diff --check
git status --short
git diff -- `
  novel-front/src/main/resources/static/javascript/reading-heartbeat.mjs `
  templates/green/static/javascript/reading-heartbeat.mjs `
  novel-front/src/test/javascript/reading-heartbeat.test.mjs `
  novel-front/src/test/java/com/java2nb/novel/controller/ReadingEngagementControllerTest.java
```

Expected: only the four intended implementation/test files are part of this fix; existing user configuration changes and bundle files remain unstaged.

- [ ] **Step 3: Commit**

```powershell
git add -- `
  novel-front/src/main/resources/static/javascript/reading-heartbeat.mjs `
  templates/green/static/javascript/reading-heartbeat.mjs `
  novel-front/src/test/javascript/reading-heartbeat.test.mjs `
  novel-front/src/test/java/com/java2nb/novel/controller/ReadingEngagementControllerTest.java

git commit -m "fix: preserve snowflake ids in reading heartbeats"
```

### Task 5: Deploy and prove end-to-end persistence

**Files:**
- No additional source changes.

- [ ] **Step 1: Transfer the incremental Git bundle and fast-forward the production checkout**

Run locally after the Task 4 commit:

```powershell
git bundle create novel-plus-reading-heartbeat-fix.bundle `
  feature/chapter-performance '^5f6b310'
git bundle verify novel-plus-reading-heartbeat-fix.bundle

$key = Join-Path $env:USERPROFILE '.ssh\novel_plus_hk_ed25519'
scp -4 -i "$key" `
  '.\novel-plus-reading-heartbeat-fix.bundle' `
  'deploy@38.76.179.245:/home/deploy/'
```

Run on the server:

```bash
cd /opt/novel-plus/app
test -z "$(git status --short)"
git bundle verify /home/deploy/novel-plus-reading-heartbeat-fix.bundle
git fetch /home/deploy/novel-plus-reading-heartbeat-fix.bundle feature/chapter-performance
git merge --ff-only FETCH_HEAD
git log -2 --oneline
```

Expected: the server fast-forwards to `fix: preserve snowflake ids in reading heartbeats`. Do not copy `.env.prod` or print expanded Compose configuration.

- [ ] **Step 2: Rebuild and recreate only novel-front**

```bash
cd /opt/novel-plus/app
docker compose --env-file .env.prod -f compose.prod.yml build novel-front
docker compose --env-file .env.prod -f compose.prod.yml up -d --no-deps --force-recreate novel-front
docker compose --env-file .env.prod -f compose.prod.yml ps novel-front
```

Expected: `novel-front` becomes healthy; MySQL, Redis, Kafka, Prometheus, Grafana and Nginx are not recreated.

- [ ] **Step 3: Perform the browser acceptance**

Open a readable chapter using its public HTTP URL, keep that tab visible and focused for more than 35 seconds, and confirm the browser Network panel records a `POST /engagement/reading/heartbeat` with HTTP 200. Its request payload must show quoted, unchanged 19-digit `bookId` and `chapterId` values.

- [ ] **Step 4: Verify MySQL persistence without exposing credentials**

```bash
docker compose --env-file .env.prod -f compose.prod.yml exec -T mysql sh -lc 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --table -e "SELECT book_id,stat_date,credited_seconds,heartbeat_count FROM book_reading_daily ORDER BY stat_date DESC,credited_seconds DESC LIMIT 20;"'
```

Expected: the tested book has a row for the current Asia/Shanghai date with positive `credited_seconds` and `heartbeat_count`.

- [ ] **Step 5: Wait for and verify the recommendation refresh**

Wait at least one five-minute recommendation refresh interval, then inspect `novel:home:reading-recommendation:v1`. The reading-backed type 2/type 3 ordering may now include the tested book according to its 7-day/15-day seconds; no claim is made when configured fallback or other books legitimately outrank it.
