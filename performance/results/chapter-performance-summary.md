# Chapter Performance Results

## Environment

| Item | Value |
|---|---|
| Operating system | Windows 11 Pro 10.0.26200 |
| CPU | Intel Core i5-14600KF, 14 cores / 20 logical processors |
| Memory | 31.8 GB |
| Java | Oracle JDK 21.0.10 LTS |
| JVM arguments | `-XX:TieredStopAtLevel=1` (Spring Boot Maven run) |
| Docker engine/client | 29.5.3 / 29.5.3 |
| MySQL image | `mysql:8.0`, host port 3307 |
| Redis image | `redis:7-alpine` (Redis 7.4.11), host port 6380 |
| Before / after commits | `dece9eb` / `a1aa4ae` |
| Book ID | `2055879962859147264` |
| Chapter ID | `2055884263706857472` |
| JMeter | Existing local Apache JMeter 5.6.3 |
| Test shape | 10-second warm-up, then 60 seconds each at 1/10/30/50/100/200 threads |
| Target | `GET http://127.0.0.1:8083/book/{bookId}/{bookIndexId}.html` |

The application used Docker MySQL and Redis with password `123456`. Actuator ran only on
`127.0.0.1:8084`; health reported MySQL and Redis `UP`, and Prometheus exposed JVM and
HikariCP metrics.

## Before Optimization

`Throughput` is JMeter's total sample throughput. `Successful throughput` removes samples
that returned the application's HTTP-200 custom 404 page. The latency percentiles include
all samples, so the values at 50+ threads are artificially low because rejected requests
fail much faster than a real chapter render.

| Threads | Throughput req/s | Successful throughput req/s | P50 ms | P95 ms | P99 ms | Error % |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 136.17 | 136.17 | 7 | 10 | 14 | 0.00 |
| 10 | 803.66 | 803.66 | 12 | 17 | 21 | 0.00 |
| 30 | 719.15 | 719.15 | 39 | 52 | 59 | 0.00 |
| 50 | 3306.09 | 215.16 | 2 | 74 | 88 | 93.49 |
| 100 | 3666.04 | 201.49 | 15 | 94 | 138 | 94.50 |
| 200 | 3619.30 | 174.44 | 39 | 116 | 231 | 95.18 |

Raw reports are under `performance/results/raw/before/` and are intentionally ignored by
Git. The first run that checked only HTTP status is preserved locally as
`performance/results/raw/before-invalid-http200-errors/`.

## Saturation Point

The last stable stage is 30 threads. Throughput already falls from 803.66 req/s at 10
threads to 719.15 req/s at 30 threads while P99 rises from 21 ms to 59 ms. At 50 threads,
93.49% of responses are not chapter pages.

The immediate cause is the page controller's shared `ThreadPoolExecutor`: development
configuration has 10 core threads, 20 maximum threads, and a queue of 100. A chapter request
submits multiple `CompletableFuture` database tasks. At 50+ concurrent requests the queue
fills, `AbortPolicy` throws `RejectedExecutionException`, and `CommonExceptionHandler`
renders the `404` view while leaving the HTTP status as 200. The original load assertion
therefore reported fast failures as successful QPS. The corrected JMeter plan requires the
real chapter marker `id="contentIdHidden"`.

This establishes two separate improvement targets:

1. Reduce repeated chapter-data reads with cache-aside so requests spend less time occupying
   the database/worker pool.
2. Preserve truthful failure signals so rejected work cannot be mistaken for successful
   throughput.

## After Optimization

| Threads | Throughput req/s | Successful throughput req/s | P50 ms | P95 ms | P99 ms | Error % |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 149.55 | 149.55 | 6 | 7 | 9 | 0.00 |
| 10 | 900.63 | 900.63 | 11 | 14 | 16 | 0.00 |
| 30 | 938.20 | 938.20 | 31 | 36 | 42 | 0.00 |
| 50 | 3428.32 | 218.96 | 2 | 66 | 74 | 93.61 |
| 100 | 3586.82 | 202.90 | 15 | 82 | 123 | 94.34 |
| 200 | 3553.26 | 176.53 | 46 | 122 | 250.99 | 95.03 |

The optimized run remains stable through 30 threads, but the capacity boundary does not
move past 50 threads. The cache improves the zero-error 30-thread stage; it does not remove
the controller's per-request fan-out into the fixed 20-thread executor.

## Comparison

| Check | Measured result | Target | Status |
|---|---:|---:|---|
| Same-stage successful throughput at 30 threads | 719.15 -> 938.20 req/s (`1.30x`, +30.46%) | `>= 3x` | FAIL |
| Maximum zero-error throughput | 803.66 -> 938.20 req/s (`1.17x`) | `>= 3x` | FAIL |
| P99 at 30 threads | 59 -> 42 ms (`28.81%` reduction) | `>= 60%` reduction | FAIL |
| Chapter-content MySQL loads for a hot fixed key | 43,134 baseline calls vs at most 1 cold load (`99.998%` reduction) | `>= 90%` | PASS |
| Error rate at 1/10/30 threads | `0.00%` | `< 0.1%` | PASS |
| Error rate at 50/100/200 threads | `93.61% / 94.34% / 95.03%` | `< 0.1%` | FAIL |
| Cold-key hotspot protection | 100 threads, exactly 1 `book_content2` SELECT in MySQL general log | not proportional to concurrency | PASS |
| Negative-cache behavior | two reads use one loader call while `__NULL__` is cached | no continuous penetration | PASS (unit test) |
| Transaction-safe invalidation | no delete before commit; one after commit; none after rollback | no stale key after commit | PASS (unit test) |
| Redis outage bound | HTTP-200 custom error page in 91.28 ms; Redis restarted successfully | no infinite wait | PASS for bound, FAIL for graceful page |

The baseline chapter adapter performs one `book_content` mapper call per successful page.
At the 30-thread baseline this means 43,134 content queries. With a hot cache, the fixed
chapter is served from Redis; a separate cold-cache 100-thread test used MySQL general-log
evidence and observed exactly one physical query:

```sql
select id, content from book_content2
where index_id = 2055884263706857472 limit 1
```

Cold/hot functional checks both returned a real mobile chapter page. The cold request took
49.46 ms, the hot request 36.70 ms, and the resulting TTL was 1815 seconds (the designed
1800–2100 second range).

The remaining limiting resource is the shared `ThreadPoolExecutor` (10 core, 20 maximum,
queue 100) used for several `CompletableFuture` tasks per page. At 50 threads its
`AbortPolicy` still rejects work. The global exception handler then renders a 404 page with
HTTP status 200, which is why raw throughput rises while successful throughput collapses.

During the Redis outage, `ChapterContentCache` correctly logged a database fallback. The
page still failed because the pre-existing `ThreadLocalUtil.getTemplateDir()` path reads
Redis through `RedisServiceImpl` after the controller work completes. That broader Redis
dependency is outside the chapter-cache boundary and is the next resilience task.

An actual author-data mutation was not performed against the shared local dataset. Cache
deletion timing is covered by the transaction synchronization tests; an authenticated
author update smoke test remains required before production deployment.

## Separate visit-write optimization

Chapter reads and popularity-counter writes use different bottlenecks and are not mixed in
one throughput claim. The Kafka visit aggregation results and failure boundaries are recorded
in performance/results/book-visit-kafka-summary.md.
