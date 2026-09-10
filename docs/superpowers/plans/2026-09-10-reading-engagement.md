# Anonymous Reading Engagement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collect privacy-minimized anonymous chapter reading time in fixed 30-second credits, protect the endpoint with an atomic Redis gate, and publish accepted credits to Kafka without reducing reading availability.

**Architecture:** `NovelFilter` keeps the existing `userClientMarkKey` browser identity but hardens its cookie attributes. A successful, readable chapter render registers a short-lived server-side `pageVisitId`; a shared desktop/mobile JavaScript module sends focused-and-visible heartbeats, while a Redis Lua script atomically validates the page binding, deduplicates sequence numbers, enforces two sliding-window limits, and caps each anonymous session/chapter/day at 1,800 seconds. Only accepted credits are sent asynchronously to the versioned Kafka topic; no anonymous identifier or IP-derived value enters Kafka.

**Tech Stack:** Java 21, Spring Boot 3.4, Jakarta Servlet/Validation, Thymeleaf, Spring Data Redis, Redis Lua, Spring Kafka, Micrometer, JUnit 5, Mockito, AssertJ, Node.js 24 built-in test runner, PowerShell, Prometheus, Grafana.

**Spec:** `docs/superpowers/specs/2026-09-10-reading-engagement-design.md`

## Global Constraints

- Reuse the existing `userClientMarkKey` Cookie; do not add a second reader identity Cookie.
- Keep the identity Cookie for exactly 7 days with `HttpOnly`, `SameSite=Lax`, path `/`, and `Secure` whenever the request is HTTPS.
- A heartbeat represents exactly 30 seconds; the browser cannot submit a duration or date.
- Start the first heartbeat only after a full 30 seconds of continuous `visible` and focused state; reset partial time on hide, minimize, or blur, and never backfill it.
- Generate a new server-side `pageVisitId` per successful readable chapter render and keep its Redis binding for 2 hours.
- Enforce per-session maximum 2 accepted credits and per-IP-HMAC maximum 120 accepted credits in every sliding 60-second interval.
- Enforce a maximum 1,800 credited seconds for one server date + anonymous session + book + chapter; refreshing must not reset this cap.
- Use the server timezone `Asia/Shanghai` for `statDate`.
- Never put raw `userMark`, session hash, `pageVisitId`, raw IP, IP HMAC, email, or user ID in Kafka events or MySQL.
- Redis or Kafka failures must never make a readable chapter fail.
- Do not trust forwarding headers unless `request.remoteAddr` is an explicitly configured trusted proxy; trusted proxies may supply only an overwritten `X-Real-IP` value.
- Do not write MySQL or implement a Kafka consumer/recommendation changes in this plan.
- Do not modify or stage the user's existing changes in `novel-admin/src/main/resources/application-dev.yml`, `novel-admin/src/main/resources/application-prod.yml`, or `novel-common/src/main/resources/application-common-dev.yml`.
- Run Maven tests with `-Dmaven.test.skip=false -DskipTests=false` because the parent POM disables tests by default.
- Use exact-path `git add -- ...` in every task; never use `git add .`.

## File Structure

New feature classes live together under `com.java2nb.novel.engagement`; only the versioned event and Kafka publisher follow the repository's existing `event` and `messaging` packages.

```text
novel-front/src/main/java/com/java2nb/novel/
├── controller/ReadingEngagementController.java
├── core/filter/NovelFilter.java                         (modify)
├── core/utils/Constants.java                            (modify)
├── core/utils/CookieUtil.java                           (modify)
├── engagement/
│   ├── ReadingClientAddressResolver.java
│   ├── ReadingEngagementProperties.java
│   ├── ReadingEngagementService.java
│   ├── ReadingHeartbeatCommand.java
│   ├── ReadingHeartbeatGate.java
│   ├── ReadingHeartbeatOutcome.java
│   ├── ReadingHeartbeatRequest.java
│   ├── ReadingIdentityHasher.java
│   └── ReadingPageVisitRegistrar.java
├── event/ReadingEngagementEvent.java
├── messaging/ReadingEngagementEventPublisher.java
└── config/ReadingEngagementConfig.java

novel-front/src/main/resources/
├── static/javascript/reading-heartbeat.mjs
├── templates/book/book_content.html                     (modify)
└── templates/mobile/book/book_content.html              (modify)

novel-front/src/test/
├── java/com/java2nb/novel/...                            (matching unit tests)
└── javascript/reading-heartbeat.test.mjs

performance/
├── check-reading-engagement.ps1
├── start-front-monitoring.ps1                            (modify)
├── test-observability-config.ps1                        (modify)
└── README.md                                             (modify)

monitoring/
├── grafana/dashboards/novel-plus-overview.json           (modify)
└── prometheus/rules/novel-plus-alerts.yml                (modify)

docs/learning/novel-plus-evolution-guide.md               (modify)
```

---

### Task 1: Harden the existing anonymous identity Cookie

**Files:**
- Modify: `novel-front/src/main/java/com/java2nb/novel/core/utils/Constants.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/core/utils/CookieUtil.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/core/filter/NovelFilter.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/core/utils/CookieUtilTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/core/filter/NovelFilterCookieTest.java`

**Interfaces:**
- Consumes: existing Cookie name `Constants.USER_CLIENT_MARK_KEY`, whose value is `userClientMarkKey`.
- Produces: `CookieUtil.setCookie(HttpServletResponse, String, String, int, boolean, boolean, String)` and `Constants.USER_CLIENT_MARK_MAX_AGE_SECONDS = 604800`.

- [ ] **Step 1: Write failing Cookie attribute tests**

Create `CookieUtilTest` with these assertions:

```java
@Test
void writesSevenDayHardenedCookie() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    CookieUtil.setCookie(response, "userClientMarkKey", "abc", 604800,
        true, true, "Lax");

    Cookie cookie = response.getCookie("userClientMarkKey");
    assertThat(cookie).isNotNull();
    assertThat(cookie.getValue()).isEqualTo("abc");
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getMaxAge()).isEqualTo(604800);
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getSecure()).isTrue();
    assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
}
```

Create `NovelFilterCookieTest` using `MockHttpServletRequest`, `MockHttpServletResponse`, a no-op `FilterChain`, and static Mockito mocks for `SpringUtil.getBean(CacheService.class)` and `BrowserUtil.isMobile(request)`. Assert a new HTTP request receives a 7-day `HttpOnly`, `SameSite=Lax`, non-secure Cookie; set `request.setSecure(true)` and assert the HTTPS case is secure. Add a request containing the existing Cookie and assert the filter does not emit a replacement Cookie. Request Cookies do not expose their original response attributes, so reissuing on every CSS/image/API request would add avoidable response headers; legacy local session Cookies naturally upgrade after they expire or are cleared, and a new public domain starts hardened immediately.

- [ ] **Step 2: Run the focused tests and verify RED**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=CookieUtilTest,NovelFilterCookieTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: compilation fails because the seven-argument `setCookie` overload and max-age constant do not exist.

- [ ] **Step 3: Implement the minimal Cookie API**

Add the constant:

```java
public static final int USER_CLIENT_MARK_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;
```

Implement the new overload without removing the existing three-argument overload used elsewhere:

```java
public static void setCookie(HttpServletResponse response, String key, String value,
                             int maxAgeSeconds, boolean httpOnly, boolean secure,
                             String sameSite) {
    Cookie cookie = new Cookie(key, value);
    cookie.setPath("/");
    cookie.setMaxAge(maxAgeSeconds);
    cookie.setHttpOnly(httpOnly);
    cookie.setSecure(secure);
    cookie.setAttribute("SameSite", sameSite);
    response.addCookie(cookie);
}
```

Change only the new-identity branch in `NovelFilter`:

```java
CookieUtil.setCookie(resp, Constants.USER_CLIENT_MARK_KEY, userMark,
    Constants.USER_CLIENT_MARK_MAX_AGE_SECONDS, true, req.isSecure(), "Lax");
```

- [ ] **Step 4: Run focused and existing filter tests**

Run the Step 2 command. Expected: all Cookie/filter tests pass.

- [ ] **Step 5: Commit the Cookie hardening**

```powershell
git add -- `
  novel-front/src/main/java/com/java2nb/novel/core/utils/Constants.java `
  novel-front/src/main/java/com/java2nb/novel/core/utils/CookieUtil.java `
  novel-front/src/main/java/com/java2nb/novel/core/filter/NovelFilter.java `
  novel-front/src/test/java/com/java2nb/novel/core/utils/CookieUtilTest.java `
  novel-front/src/test/java/com/java2nb/novel/core/filter/NovelFilterCookieTest.java
git commit -m "feat: harden anonymous reader cookie"
```

### Task 2: Add configuration, hashing, and trusted-proxy IP resolution

**Files:**
- Create: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEngagementProperties.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingIdentityHasher.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingClientAddressResolver.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/config/ReadingEngagementConfig.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEngagementPropertiesTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingIdentityHasherTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingClientAddressResolverTest.java`

**Interfaces:**
- Consumes: environment variable `NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET` through Spring relaxed binding.
- Produces: `ReadingIdentityHasher.sessionHash(String)`, `ReadingIdentityHasher.ipHmac(String)`, and `ReadingClientAddressResolver.resolve(HttpServletRequest)`.

- [ ] **Step 1: Write failing property and security tests**

The properties test must bind an explicit secret and verify every approved default:

```java
assertThat(properties.topic()).isEqualTo("novel-reading-engagement-v1");
assertThat(properties.pageTtl()).isEqualTo(Duration.ofHours(2));
assertThat(properties.rateWindow()).isEqualTo(Duration.ofSeconds(60));
assertThat(properties.rateKeyTtl()).isEqualTo(Duration.ofMinutes(2));
assertThat(properties.creditKeyTtl()).isEqualTo(Duration.ofDays(2));
assertThat(properties.sessionLimit()).isEqualTo(2);
assertThat(properties.ipLimit()).isEqualTo(120);
assertThat(properties.dailyCapSeconds()).isEqualTo(1800);
assertThat(properties.creditedSeconds()).isEqualTo(30);
assertThat(properties.zoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
```

The hasher test must assert identical inputs are stable, distinct inputs differ, SHA-256/HMAC outputs are 64 lowercase hex characters, and changing the HMAC secret changes the IP result.

The resolver test must cover all three cases:

```java
// Untrusted remote: ignore a forged X-Real-IP.
request.setRemoteAddr("203.0.113.10");
request.addHeader("X-Real-IP", "198.51.100.20");
assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");

// Trusted loopback proxy: accept its overwritten, syntactically valid X-Real-IP.
request.setRemoteAddr("127.0.0.1");
request.addHeader("X-Real-IP", "198.51.100.20");
assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");

// Reject header text that is not an IPv4/IPv6 literal.
request.addHeader("X-Real-IP", "attacker.example");
assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=ReadingEngagementPropertiesTest,ReadingIdentityHasherTest,ReadingClientAddressResolverTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: compilation fails because the engagement classes do not exist.

- [ ] **Step 3: Implement exact configuration defaults and validation**

Implement `ReadingEngagementProperties` as a mutable `@ConfigurationProperties(prefix = "novel.reading-engagement")`, `@Validated` class with initialized defaults listed above, `Set<String> trustedProxyAddresses = Set.of("127.0.0.1", "::1")`, and `@NotBlank String ipHmacSecret`. Expose read methods named exactly `topic()`, `pageTtl()`, `rateWindow()`, `rateKeyTtl()`, `creditKeyTtl()`, `sessionLimit()`, `ipLimit()`, `dailyCapSeconds()`, `creditedSeconds()`, `zoneId()`, `ipHmacSecret()`, and `trustedProxyAddresses()` plus JavaBean setters for Spring binding.

Create the properties configuration in this task so page registration and the Redis gate are valid Spring components before Kafka work begins:

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReadingEngagementProperties.class)
public class ReadingEngagementConfig {
}
```

Load this configuration in `ReadingEngagementPropertiesTest` with `novel.reading-engagement.ip-hmac-secret=test-secret`. Add a second context run without the secret and assert startup fails validation; production must never silently use a repository default secret.

Annotate `ReadingIdentityHasher` and `ReadingClientAddressResolver` with `@Component`. Implement hashes with UTF-8 bytes, `MessageDigest.getInstance("SHA-256")`, `Mac.getInstance("HmacSHA256")`, `SecretKeySpec`, and `HexFormat.of().formatHex(...)`. Reject blank raw values with `IllegalArgumentException`; never log either the raw value or digest.

Implement `ReadingClientAddressResolver` so only exact configured proxy addresses may supply `X-Real-IP`. Accept a header only when its length is at most 45 and it matches `[0-9A-Fa-f:.]+`; otherwise return `request.getRemoteAddr()`. Do not use the existing `IpUtil.getRealIp`, because it trusts attacker-controlled forwarding headers from every remote address.

- [ ] **Step 4: Run the focused tests**

Run the Step 2 command. Expected: all property, hash, and address tests pass.

- [ ] **Step 5: Commit the security primitives**

```powershell
git add -- `
  novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEngagementProperties.java `
  novel-front/src/main/java/com/java2nb/novel/engagement/ReadingIdentityHasher.java `
  novel-front/src/main/java/com/java2nb/novel/engagement/ReadingClientAddressResolver.java `
  novel-front/src/main/java/com/java2nb/novel/config/ReadingEngagementConfig.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEngagementPropertiesTest.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingIdentityHasherTest.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingClientAddressResolverTest.java
git commit -m "feat: add reading engagement security primitives"
```

### Task 3: Register page visits only for readable chapter renders

**Files:**
- Create: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingPageVisitRegistrar.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingPageVisitRegistrarTest.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/controller/page/PageController.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/controller/page/PageControllerChapterTaskTest.java`

**Interfaces:**
- Consumes: `ReadingIdentityHasher.sessionHash(String)` and `ReadingEngagementProperties.pageTtl()`.
- Produces: `Optional<String> ReadingPageVisitRegistrar.register(String userMark, Long bookId, Long chapterId)` and model attribute `readingPageVisitId`.

- [ ] **Step 1: Write failing registrar tests**

Mock `StringRedisTemplate.execute` and capture keys/arguments. The success test must assert the returned token is 32 lowercase hexadecimal characters and registration passes these values to one Lua call:

```text
KEYS[1] = reading:page:{pageVisitId}
ARGV[1] = session SHA-256
ARGV[2] = bookId
ARGV[3] = chapterId
ARGV[4] = 7200
```

Add tests asserting Redis returning null or throwing `RedisConnectionFailureException` returns `Optional.empty()`, never throws, and increments `novel.reading.page.registration` with `result=error`; success increments `result=success`.

- [ ] **Step 2: Run the registrar test and verify RED**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am '-Dtest=ReadingPageVisitRegistrarTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: compilation fails because `ReadingPageVisitRegistrar` does not exist.

- [ ] **Step 3: Implement atomic page registration**

Use a `DefaultRedisScript<Long>` containing exactly one atomic registration operation:

```lua
redis.call('HSET', KEYS[1],
  'sessionHash', ARGV[1],
  'bookId', ARGV[2],
  'chapterId', ARGV[3],
  'lastSequence', '0')
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
return 1
```

Annotate the registrar with `@Component`. Generate the token with `UUID.randomUUID().toString().replace("-", "")`. Catch `RuntimeException`, increment the error counter, and return `Optional.empty()`. Implement warning throttling with an `AtomicLong`: log the first registration failure and then every 1,000th failure, never including the Cookie or digest.

- [ ] **Step 4: Write failing controller integration tests**

Extend `PageControllerChapterTaskTest` with a mocked registrar passed into the constructor. Mock `ThreadLocalUtil.getClientId()` as `"reader-mark"`. For a non-VIP chapter with non-null content, return `Optional.of("page-token")` and assert:

```java
verify(registrar).register("reader-mark", 1L, 100L);
assertThat(model.get("readingPageVisitId")).isEqualTo("page-token");
```

For `needBuy=true`, verify `register` is never called and the model has no `readingPageVisitId`. Add the same no-registration assertion when `bookContent` is null.

- [ ] **Step 5: Integrate registration after the chapter future succeeds**

Add `ReadingPageVisitRegistrar` as the final constructor dependency. After adding existing page data to the model, register only when content exists and access is allowed:

```java
if (!chapterPageData.needBuy() && chapterPageData.bookContent() != null) {
    readingPageVisitRegistrar.register(ThreadLocalUtil.getClientId(), bookId, bookIndexId)
        .ifPresent(pageVisitId -> model.addAttribute("readingPageVisitId", pageVisitId));
}
```

Keep registration outside the shared chapter executor task. A Redis failure must not fail or delay the database/cache task, and a locked VIP chapter must not accrue reading time.

- [ ] **Step 6: Run registrar and page controller tests**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=ReadingPageVisitRegistrarTest,PageControllerChapterTaskTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: all focused tests pass and existing page model assertions remain unchanged.

- [ ] **Step 7: Commit page visit registration**

```powershell
git add -- `
  novel-front/src/main/java/com/java2nb/novel/engagement/ReadingPageVisitRegistrar.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingPageVisitRegistrarTest.java `
  novel-front/src/main/java/com/java2nb/novel/controller/page/PageController.java `
  novel-front/src/test/java/com/java2nb/novel/controller/page/PageControllerChapterTaskTest.java
git commit -m "feat: register readable chapter page visits"
```

### Task 4: Implement the atomic Redis heartbeat gate

**Files:**
- Create: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingHeartbeatOutcome.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingHeartbeatCommand.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingHeartbeatGate.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingHeartbeatGateTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingHeartbeatGateRedisIT.java`

**Interfaces:**
- Consumes: approved limits from `ReadingEngagementProperties`.
- Produces: `ReadingHeartbeatOutcome ReadingHeartbeatGate.evaluate(ReadingHeartbeatCommand)`.

- [ ] **Step 1: Write failing outcome, key, and argument tests**

Define expected outcomes and Redis codes in the test: `1=ACCEPTED`, `2=DUPLICATE`, `3=INVALID_PAGE`, `4=SESSION_RATE_LIMITED`, `5=IP_RATE_LIMITED`, `6=DAILY_CAP_REACHED`; Java-side exceptions map to `REDIS_ERROR`.

Use this fixed command:

```java
ReadingHeartbeatCommand command = new ReadingHeartbeatCommand(
    42L, 99L, "abc123", 7L,
    "session-hash", "ip-hmac",
    LocalDate.of(2026, 9, 10), 1_789_000_000_000L);
```

Capture the Redis invocation and assert exact keys:

```text
reading:page:abc123
reading:rate:session:session-hash
reading:rate:ip:ip-hmac
reading:credit:2026-09-10:session-hash:42:99
```

Assert arguments include the page binding values, sequence `7`, current milliseconds, window start `now-60000`, limits `2` and `120`, cap `1800`, credit `30`, rate TTL `120`, credit TTL `172800`, and unique ZSET member `abc123:7`.

Add tests for all six return codes, null/unknown Redis results as `REDIS_ERROR`, thrown Redis errors as `REDIS_ERROR`, and Micrometer timer `novel.reading.redis.gate` recording every call.

- [ ] **Step 2: Run the gate test and verify RED**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am '-Dtest=ReadingHeartbeatGateTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: compilation fails because the command, outcome, and gate do not exist.

- [ ] **Step 3: Define immutable command and outcome types**

```java
public record ReadingHeartbeatCommand(
    Long bookId,
    Long chapterId,
    String pageVisitId,
    long sequence,
    String sessionHash,
    String ipHmac,
    LocalDate statDate,
    long nowEpochMillis
) {}
```

```java
public enum ReadingHeartbeatOutcome {
    ACCEPTED, DUPLICATE, INVALID_PAGE, SESSION_RATE_LIMITED,
    IP_RATE_LIMITED, DAILY_CAP_REACHED, REDIS_ERROR
}
```

- [ ] **Step 4: Implement the exact Lua state transition**

Use a `DefaultRedisScript<Long>` with this logic; keep result codes synchronized with the enum mapping tests:

```lua
if redis.call('EXISTS', KEYS[1]) == 0 then return 3 end
if redis.call('HGET', KEYS[1], 'sessionHash') ~= ARGV[1] then return 3 end
if redis.call('HGET', KEYS[1], 'bookId') ~= ARGV[2] then return 3 end
if redis.call('HGET', KEYS[1], 'chapterId') ~= ARGV[3] then return 3 end

local sequence = tonumber(ARGV[4])
local lastSequence = tonumber(redis.call('HGET', KEYS[1], 'lastSequence') or '0')
if sequence <= lastSequence then return 2 end

local now = tonumber(ARGV[5])
local windowStart = tonumber(ARGV[6])
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', windowStart)
if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[7]) then return 4 end
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', windowStart)
if redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[8]) then return 5 end

local credited = tonumber(redis.call('GET', KEYS[4]) or '0')
local creditSeconds = tonumber(ARGV[10])
if credited + creditSeconds > tonumber(ARGV[9]) then return 6 end

redis.call('HSET', KEYS[1], 'lastSequence', sequence)
redis.call('ZADD', KEYS[2], now, ARGV[13])
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[11]))
redis.call('ZADD', KEYS[3], now, ARGV[13])
redis.call('EXPIRE', KEYS[3], tonumber(ARGV[11]))
redis.call('SET', KEYS[4], credited + creditSeconds, 'EX', tonumber(ARGV[12]))
return 1
```

Annotate `ReadingHeartbeatGate` with `@Component`. The Java class must build all keys/arguments itself, call `redisTemplate.execute`, map results, record the timer, and catch `RuntimeException` as `REDIS_ERROR`. It must never log key material because the keys contain identity-derived hashes.

- [ ] **Step 5: Add a static Lua contract assertion**

In `ReadingHeartbeatGateTest`, inspect `ReadingHeartbeatGate.GATE_SCRIPT.getScriptAsString()` and assert it contains `HGET`, `ZREMRANGEBYSCORE`, `ZCARD`, `ZADD`, `lastSequence`, the cap comparison, and `SET ... EX`. This guards accidental replacement with non-atomic Java read/check/write calls.

- [ ] **Step 6: Run the focused test**

Run the Step 2 command. Expected: all gate mapping, key, argument, timer, error, and script contract tests pass.

- [ ] **Step 7: Exercise the actual Lua script against local Redis**

Create `ReadingHeartbeatGateRedisIT` guarded by:

```java
@EnabledIfSystemProperty(named = "redis.it", matches = "true")
```

Connect a dedicated `LettuceConnectionFactory` to `127.0.0.1:6380` with password `123456`, construct the real gate, and use a random UUID suffix in every session, IP, and page value. Register each page Hash with `sessionHash`, `bookId`, `chapterId`, `lastSequence=0` and a two-hour expiry. Exercise these state transitions against Redis itself:

```text
same page sequence 1 -> ACCEPTED
same page sequence 1 again -> DUPLICATE
same session sequence 2 -> ACCEPTED
same session sequence 3 inside 60 seconds -> SESSION_RATE_LIMITED
60 accepted calls spaced 61 seconds in command time -> exactly 1800 stored seconds
61st spaced call -> DAILY_CAP_REACHED
20 simultaneous calls with test dailyCapSeconds=30 -> exactly one ACCEPTED
```

For the concurrent test, give `ReadingEngagementProperties` session/IP limits of 100 and daily cap 30, start 20 calls with a `CountDownLatch`, and assert one result is `ACCEPTED` while all other results are `DAILY_CAP_REACHED` or `DUPLICATE`. In `@AfterEach`, delete only the exact page, rate, and credit keys collected by the test; never use `KEYS`, glob deletion, `FLUSHDB`, or `FLUSHALL`.

Run:

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am '-Dtest=ReadingHeartbeatGateRedisIT' `
  '-Dredis.it=true' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: the actual Lua transitions, sliding window, daily cap, and concurrent atomicity tests pass.

- [ ] **Step 8: Commit the Redis gate**

```powershell
git add -- `
  novel-front/src/main/java/com/java2nb/novel/engagement/ReadingHeartbeatOutcome.java `
  novel-front/src/main/java/com/java2nb/novel/engagement/ReadingHeartbeatCommand.java `
  novel-front/src/main/java/com/java2nb/novel/engagement/ReadingHeartbeatGate.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingHeartbeatGateTest.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingHeartbeatGateRedisIT.java
git commit -m "feat: gate reading heartbeats atomically"
```

### Task 5: Publish privacy-minimized reading events to Kafka

**Files:**
- Create: `novel-front/src/main/java/com/java2nb/novel/event/ReadingEngagementEvent.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/messaging/ReadingEngagementEventPublisher.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/config/ReadingEngagementConfig.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/event/ReadingEngagementEventTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementEventPublisherTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/config/ReadingEngagementConfigTest.java`

**Interfaces:**
- Consumes: topic `ReadingEngagementProperties.topic()` and the existing JSON Kafka producer.
- Produces: `void ReadingEngagementEventPublisher.publish(Long, Long, int, Instant, LocalDate)` and topic `novel-reading-engagement-v1`.

- [ ] **Step 1: Write failing event contract tests**

Require this exact record shape:

```java
public record ReadingEngagementEvent(
    String eventId,
    Long bookId,
    Long chapterId,
    Integer creditedSeconds,
    Instant occurredAt,
    LocalDate statDate,
    Integer version
) {}
```

Test the factory rejects non-positive IDs, any credit other than 30, null timestamps/dates, creates a nonblank unique event ID, and always sets `version=1`. Reflect over record component names and assert there is no component for Cookie, session, page visit, IP, email, or user ID.

- [ ] **Step 2: Write failing publisher tests**

Model tests after `BookVisitEventPublisherTest`. Assert `KafkaTemplate.send("novel-reading-engagement-v1", 42L, event)` is called, the event contains the supplied server time/date, success increments `novel.reading.kafka.send{result=success}`, synchronous/asynchronous failures do not propagate and increment `result=failed`, and 1,001 failures produce only the first and 1,000th warning.

- [ ] **Step 3: Run event/publisher tests and verify RED**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=ReadingEngagementEventTest,ReadingEngagementEventPublisherTest,ReadingEngagementConfigTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: compilation fails because the event, publisher, and topic configuration do not exist.

- [ ] **Step 4: Implement the event and nonblocking publisher**

Implement `ReadingEngagementEvent.create(...)` with the validation above and `UUID.randomUUID().toString()`. Annotate the publisher with `@Component` and implement the same `CompletableFuture.whenComplete` and rate-limited warning pattern as `BookVisitEventPublisher`, but do not log any identity-derived data.

- [ ] **Step 5: Register properties and the topic**

Extend the proxy-free configuration created in Task 2:

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReadingEngagementProperties.class)
public class ReadingEngagementConfig {
    @Bean
    NewTopic readingEngagementTopic(ReadingEngagementProperties properties) {
        return TopicBuilder.name(properties.topic())
            .partitions(3)
            .replicas(1)
            .build();
    }
}
```

The configuration test must assert topic name, three partitions, and one replica. Reuse the existing `Clock` bean from `BookVisitKafkaConfig`; do not declare a duplicate clock.

- [ ] **Step 6: Run the focused tests**

Run the Step 3 command. Expected: all event, privacy-shape, publisher, failure, metric, and topic tests pass.

- [ ] **Step 7: Commit Kafka production**

```powershell
git add -- `
  novel-front/src/main/java/com/java2nb/novel/event/ReadingEngagementEvent.java `
  novel-front/src/main/java/com/java2nb/novel/messaging/ReadingEngagementEventPublisher.java `
  novel-front/src/main/java/com/java2nb/novel/config/ReadingEngagementConfig.java `
  novel-front/src/test/java/com/java2nb/novel/event/ReadingEngagementEventTest.java `
  novel-front/src/test/java/com/java2nb/novel/messaging/ReadingEngagementEventPublisherTest.java `
  novel-front/src/test/java/com/java2nb/novel/config/ReadingEngagementConfigTest.java
git commit -m "feat: publish reading engagement events"
```

### Task 6: Add the heartbeat application service and HTTP endpoint

**Files:**
- Create: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingHeartbeatRequest.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEngagementService.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/controller/ReadingEngagementController.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEngagementServiceTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/controller/ReadingEngagementControllerTest.java`

**Interfaces:**
- Consumes: page/gate types from Tasks 3-4, publisher from Task 5, current `userClientMarkKey`, and trusted client address resolver.
- Produces: `POST /engagement/reading/heartbeat` with JSON request and uniform `RestResult.ok()` for all well-formed internal outcomes.

- [ ] **Step 1: Write failing service tests**

Define the request record with Bean Validation:

```java
public record ReadingHeartbeatRequest(
    @NotNull @Positive Long bookId,
    @NotNull @Positive Long chapterId,
    @NotBlank @Pattern(regexp = "[0-9a-f]{32}") String pageVisitId,
    @Positive @Max(10000) long sequence
) {}
```

Use a clock fixed at `2026-09-10T16:30:00Z`, which must produce `statDate=2026-09-11` in `Asia/Shanghai`. Assert the service hashes the user mark/IP, builds the exact command, calls the gate once, and:

- publishes one fixed 30-second event only for `ACCEPTED`;
- increments `novel.reading.heartbeat` for every result with `result=outcome.name().toLowerCase(Locale.ROOT)`;
- increments `novel.reading.credited.seconds` by 30 only for `ACCEPTED`;
- never publishes for duplicate, invalid page, either rate limit, daily cap, or Redis error;
- treats a missing/blank browser identity as `INVALID_PAGE` without calling Redis.

- [ ] **Step 2: Run service tests and verify RED**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am '-Dtest=ReadingEngagementServiceTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: compilation fails because the request and service do not exist.

- [ ] **Step 3: Implement service orchestration**

Implement this method signature:

```java
public ReadingHeartbeatOutcome handle(ReadingHeartbeatRequest request,
                                      String userMark,
                                      String clientAddress)
```

Annotate the implementation with `@Service`. Obtain one `Instant now = clock.instant()`, derive `LocalDate` with `properties.zoneId()`, build `ReadingHeartbeatCommand`, and evaluate the gate. Pre-register one counter per finite enum outcome in an `EnumMap` so no user-controlled metric label is possible. On `ACCEPTED`, increment the credited-seconds counter and call:

```java
publisher.publish(request.bookId(), request.chapterId(),
    properties.creditedSeconds(), now, statDate);
```

- [ ] **Step 4: Write failing endpoint tests**

Build standalone MockMvc around `ReadingEngagementController`. Mock `ThreadLocalUtil.getClientId()` and the resolver. Assert:

```text
POST valid JSON -> HTTP 200 and service called once
POST bookId=0 -> HTTP 400 and service not called
POST pageVisitId with wrong length/characters -> HTTP 400
POST sequence=0 or 10001 -> HTTP 400
service returns each non-error internal outcome -> identical HTTP 200 response body
```

The controller must not return the enum value in its response.

- [ ] **Step 5: Implement the controller**

```java
@PostMapping("/heartbeat")
public RestResult<Void> heartbeat(@Valid @RequestBody ReadingHeartbeatRequest request,
                                  HttpServletRequest servletRequest) {
    engagementService.handle(request, ThreadLocalUtil.getClientId(),
        clientAddressResolver.resolve(servletRequest));
    return RestResult.ok();
}
```

Annotate the class with `@RestController` and `@RequestMapping("/engagement/reading")`. Do not add CORS; the endpoint is same-origin. Do not catch validation exceptions into HTTP 200; malformed input must remain HTTP 400.

- [ ] **Step 6: Run service and endpoint tests**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=ReadingEngagementServiceTest,ReadingEngagementControllerTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: all orchestration, privacy, outcome, validation, and uniform-response tests pass.

- [ ] **Step 7: Commit the heartbeat endpoint**

```powershell
git add -- `
  novel-front/src/main/java/com/java2nb/novel/engagement/ReadingHeartbeatRequest.java `
  novel-front/src/main/java/com/java2nb/novel/engagement/ReadingEngagementService.java `
  novel-front/src/main/java/com/java2nb/novel/controller/ReadingEngagementController.java `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingEngagementServiceTest.java `
  novel-front/src/test/java/com/java2nb/novel/controller/ReadingEngagementControllerTest.java
git commit -m "feat: accept protected reading heartbeats"
```

### Task 7: Share one focus-aware browser heartbeat implementation

**Files:**
- Create: `novel-front/src/main/resources/static/javascript/reading-heartbeat.mjs`
- Create: `novel-front/src/test/javascript/reading-heartbeat.test.mjs`
- Create: `novel-front/src/test/java/com/java2nb/novel/engagement/ReadingHeartbeatTemplateTest.java`
- Modify: `novel-front/src/main/resources/templates/book/book_content.html`
- Modify: `novel-front/src/main/resources/templates/mobile/book/book_content.html`

**Interfaces:**
- Consumes: model attribute `readingPageVisitId`, existing `book.id`, existing `bookIndex.id`, and endpoint from Task 6.
- Produces: `createReadingHeartbeat(options)` ES module factory and automatic DOM bootstrap.

- [ ] **Step 1: Write failing JavaScript timer tests with fake dependencies**

Export a factory so Node tests can inject fake document/window/timers/fetch. The test must require these behaviors:

```javascript
const tracker = createReadingHeartbeat({
  documentRef,
  windowRef,
  fetchFn,
  setTimeoutFn,
  clearTimeoutFn,
  intervalMs: 30000,
  payload: {bookId: 42, chapterId: 99, pageVisitId: 'a'.repeat(32)}
});

tracker.start();
assert.equal(fetchCalls.length, 0);       // no immediate credit
timers.runNext();
assert.equal(fetchCalls.length, 1);       // first full 30 seconds
assert.equal(JSON.parse(fetchCalls[0].options.body).sequence, 1);

documentRef.visibilityState = 'hidden';
documentRef.dispatch('visibilitychange');
assert.equal(timers.pendingCount(), 0);   // partial interval discarded

documentRef.visibilityState = 'visible';
windowRef.focused = true;
windowRef.dispatch('focus');
assert.equal(timers.pendingDelay(), 30000); // restart a complete interval
```

Also assert blur stops the timer, continued visibility sends sequence 2, fetch uses `POST`, JSON content type and `credentials: 'same-origin'`, a rejected fetch schedules no immediate retry, and `destroy()` removes listeners/timers.

- [ ] **Step 2: Run Node tests and verify RED**

```powershell
& 'C:\Program Files\nodejs\node.exe' --test `
  '.\novel-front\src\test\javascript\reading-heartbeat.test.mjs'
```

Expected: FAIL because `reading-heartbeat.mjs` does not exist.

- [ ] **Step 3: Implement the minimal focus-aware module**

The factory must use one recursive `setTimeout`, not `setInterval`, so pause/resume always starts a complete interval. Its active predicate is exactly:

```javascript
const isActive = () =>
  documentRef.visibilityState === 'visible' && documentRef.hasFocus();
```

Each timer callback must re-check `isActive()`, increment an in-memory sequence, issue this request, swallow the rejected promise, and schedule the next full interval only while still active:

```javascript
fetchFn('/engagement/reading/heartbeat', {
  method: 'POST',
  credentials: 'same-origin',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({...payload, sequence})
}).catch(() => {});
```

Register `visibilitychange`, `focus`, `blur`, and `pagehide`; `pagehide` calls `destroy()` and must not use `sendBeacon` because no partial duration is credited. Automatic bootstrap reads a hidden element with id `reading-engagement`, validates its dataset values, and does nothing when the element/token is absent.

- [ ] **Step 4: Run Node tests**

Run the Step 2 command. Expected: all JavaScript tests pass with no real 30-second wait.

- [ ] **Step 5: Write failing template contract tests**

`ReadingHeartbeatTemplateTest` must load both classpath templates and assert each contains exactly one shared module reference and one conditional data element:

```html
<div id="reading-engagement"
     th:if="${readingPageVisitId != null}"
     th:data-book-id="${book.id}"
     th:data-chapter-id="${bookIndex.id}"
     th:data-page-visit-id="${readingPageVisitId}"
     hidden></div>
<script type="module" src="/javascript/reading-heartbeat.mjs"></script>
```

Assert neither template contains a second inline implementation of the heartbeat timer.

- [ ] **Step 6: Add the same integration to desktop and mobile templates**

Insert the exact data element and module tag near the end of both templates. Do not alter their existing navigation, purchase, speech, theme, or visit-count scripts.

- [ ] **Step 7: Run JavaScript, template, and page tests**

```powershell
& 'C:\Program Files\nodejs\node.exe' --test `
  '.\novel-front\src\test\javascript\reading-heartbeat.test.mjs'
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am `
  '-Dtest=ReadingHeartbeatTemplateTest,PageControllerChapterTaskTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: shared JavaScript behavior, both template contracts, and controller render tests pass.

- [ ] **Step 8: Commit browser collection**

```powershell
git add -- `
  novel-front/src/main/resources/static/javascript/reading-heartbeat.mjs `
  novel-front/src/test/javascript/reading-heartbeat.test.mjs `
  novel-front/src/test/java/com/java2nb/novel/engagement/ReadingHeartbeatTemplateTest.java `
  novel-front/src/main/resources/templates/book/book_content.html `
  novel-front/src/main/resources/templates/mobile/book/book_content.html
git commit -m "feat: collect focused chapter reading time"
```

### Task 8: Add runtime secrets, monitoring, verification, and learning documentation

**Files:**
- Modify: `performance/start-front-monitoring.ps1`
- Create: `performance/check-reading-engagement.ps1`
- Modify: `performance/test-observability-config.ps1`
- Modify: `monitoring/prometheus/rules/novel-plus-alerts.yml`
- Modify: `monitoring/grafana/dashboards/novel-plus-overview.json`
- Modify: `performance/README.md`
- Modify: `docs/learning/novel-plus-evolution-guide.md`

**Interfaces:**
- Consumes: live app ports 8083/8084, Redis on 6380, Kafka on 9092, existing Prometheus/Grafana stack.
- Produces: inherited process secret, two engagement alerts, dashboard panels, and a repeatable end-to-end smoke test.

- [ ] **Step 1: Extend the static observability contract and verify RED**

Add assertions to `performance/test-observability-config.ps1` for alert names `ReadingEngagementRedisErrors` and `ReadingEngagementKafkaPublishFailures`, dashboard panels `Reading Heartbeat Outcomes`, `Reading Credited Seconds / s`, and `Reading Kafka Send / s`, plus existence of `performance/check-reading-engagement.ps1`.

Run:

```powershell
& '.\performance\test-observability-config.ps1'
```

Expected: FAIL on the first missing reading-engagement alert or panel.

- [ ] **Step 2: Inject a safe local HMAC secret without printing it**

Add optional parameter `[string]$ReadingIpHmacSecret = ''` to `start-front-monitoring.ps1`. Before Maven starts, set `NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET` from the parameter, existing environment, or a cryptographically random process-local value:

```powershell
if ($ReadingIpHmacSecret) {
    $env:NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET = $ReadingIpHmacSecret
}
elseif (-not $env:NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET) {
    $secretBytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($secretBytes) } finally { $random.Dispose() }
    $env:NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET =
        [Convert]::ToBase64String($secretBytes)
    Write-Host 'Generated a process-local reading IP HMAC secret.'
}
```

Never print the secret. Document that public deployment must inject a stable, high-entropy value and keep the application port reachable only through the trusted Nginx proxy.

- [ ] **Step 3: Add exact Prometheus alerts and Grafana panels**

Add alerts:

```promql
sum(increase(novel_reading_heartbeat_total{result="redis_error"}[5m])) > 0
sum(increase(novel_reading_kafka_send_total{result="failed"}[5m])) > 0
```

Use immediate evaluation for Kafka failures and `for: 1m` for sustained Redis errors. Add dashboard queries:

```promql
sum by (result) (rate(novel_reading_heartbeat_total[$__rate_interval]))
sum(rate(novel_reading_credited_seconds_total[$__rate_interval]))
sum by (result) (rate(novel_reading_kafka_send_total[$__rate_interval]))
```

Use only finite `result` labels; no ID/hash/IP labels.

- [ ] **Step 4: Create a real endpoint smoke test**

Create `check-reading-engagement.ps1` with mandatory `-BookId`, optional `[long]$ChapterId = 0`, default base URLs `http://127.0.0.1:8083` and `http://127.0.0.1:8084`, and a `WebRequestSession`. When `ChapterId` is zero, select it read-only from the local container before making HTTP requests:

```powershell
$rawChapterId = docker exec -e MYSQL_PWD=123456 novel-mysql `
    mysql -uroot -N -s novel_plus -e `
    "SELECT id FROM book_index WHERE book_id = $BookId AND COALESCE(is_vip, 0) = 0 ORDER BY index_num LIMIT 1;" 2>$null
if ($LASTEXITCODE -ne 0 -or -not $rawChapterId) {
    throw "No readable non-VIP chapter found for book $BookId"
}
$ChapterId = [long]($rawChapterId | Select-Object -Last 1)
```

It must then:

1. GET `/book/{BookId}/{ChapterId}.html` and retain `userClientMarkKey`.
2. Extract the 32-hex `data-page-visit-id` from rendered HTML and fail clearly if absent.
3. Read baseline Actuator counters for accepted, duplicate, and Kafka success.
4. POST sequence 1, duplicate sequence 1, then sequence 2 as JSON with the same session.
5. Poll the metrics endpoint for at most 10 seconds.
6. Assert accepted increased by 2, duplicate by 1, credited seconds by 60, Kafka success by 2, and every HTTP response was successful.

The script must not print the Cookie, Redis keys, session hash, or IP HMAC. The database query is read-only and the script must never mutate MySQL.

- [ ] **Step 5: Run static and focused automated verification**

```powershell
& '.\performance\test-observability-config.ps1'
& 'C:\Program Files\nodejs\node.exe' --test `
  '.\novel-front\src\test\javascript\reading-heartbeat.test.mjs'
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Expected: static contract and JavaScript tests pass; all existing 52 Maven tests plus the new engagement tests pass.

- [ ] **Step 6: Restart and run live verification**

Restart `novel-front` with `performance/start-front-monitoring.ps1`, then run:

```powershell
& '.\performance\check-reading-engagement.ps1' `
  -BookId 2055879962859147264
& '.\performance\check-observability.ps1' `
  -GrafanaUser 'admin' -GrafanaPassword '123456'
```

Expected: the script selects a readable chapter, reports two accepted credits, one duplicate, 60 credited seconds, and two successful Kafka sends; the complete observability verifier passes.

- [ ] **Step 7: Perform failure drills**

Run three bounded drills and restore each dependency before continuing:

1. Stop Redis, load a known chapter already available through MySQL, and verify HTTP 200 with no `readingPageVisitId`; restart Redis.
2. Stop Kafka, obtain a page token while Redis remains up, send two heartbeats, and verify HTTP 200 plus `novel_reading_kafka_send_total{result="failed"}` growth; restart Kafka.
3. Add a `-VerifyIpLimit` switch to `check-reading-engagement.ps1`. Under that switch, create 121 independent `WebRequestSession` instances, load the selected chapter once per session, and send sequence 1 with overwritten `X-Real-IP: 198.51.100.77`. Assert the accepted metric increases by exactly 120 and `ip_rate_limited` increases by exactly 1. Run this after the ordinary smoke test because it intentionally emits 120 test events; the page/rate keys expire automatically and the script performs no broad Redis deletion.

Never stop MySQL for this stage, and never delete broad Redis key patterns.

- [ ] **Step 8: Update runbook and learning guide**

Document exact startup, secret injection, desktop/mobile manual focus test, smoke-test command, metric meanings, failure semantics, privacy boundaries, and the next phase: Kafka batch aggregation into daily MySQL tables. Explicitly explain that 7-day Cookie identity and server-day aggregation are independent, and that this is anonymous-browser approximation rather than real-person identification.

- [ ] **Step 9: Run final verification before claiming completion**

```powershell
& '.\performance\test-observability-config.ps1'
& 'C:\Program Files\nodejs\node.exe' --test `
  '.\novel-front\src\test\javascript\reading-heartbeat.test.mjs'
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am '-Dmaven.test.skip=false' '-DskipTests=false' test
docker compose -f '.\compose.local.yml' config
docker run --rm --entrypoint /bin/promtool `
  -v "${PWD}/monitoring/prometheus:/etc/prometheus:ro" `
  prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml
& '.\performance\check-reading-engagement.ps1' `
  -BookId 2055879962859147264
& '.\performance\check-observability.ps1' `
  -GrafanaUser 'admin' -GrafanaPassword '123456'
git diff --check
git status --short
```

Expected: every command succeeds; report the actual Maven test count instead of assuming it; Git status contains only intended Task 8 files plus the three untouched user-owned YAML modifications.

- [ ] **Step 10: Commit monitoring and documentation**

```powershell
git add -- `
  performance/start-front-monitoring.ps1 `
  performance/check-reading-engagement.ps1 `
  performance/test-observability-config.ps1 `
  monitoring/prometheus/rules/novel-plus-alerts.yml `
  monitoring/grafana/dashboards/novel-plus-overview.json `
  performance/README.md `
  docs/learning/novel-plus-evolution-guide.md
git commit -m "feat: verify reading engagement observability"
```

- [ ] **Step 11: Perform completion review**

Invoke `superpowers:verification-before-completion`, then `superpowers:requesting-code-review`. Review privacy fields, Redis atomicity, failure isolation, metric cardinality, and exact spec coverage before moving to the Kafka-to-MySQL aggregation phase.
