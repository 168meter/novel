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
| Application commit | `45546f5` plus the Task 2 instrumentation changes |
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
| 1 | Pending | Pending | Pending | Pending | Pending | Pending |
| 10 | Pending | Pending | Pending | Pending | Pending | Pending |
| 30 | Pending | Pending | Pending | Pending | Pending | Pending |
| 50 | Pending | Pending | Pending | Pending | Pending | Pending |
| 100 | Pending | Pending | Pending | Pending | Pending | Pending |
| 200 | Pending | Pending | Pending | Pending | Pending | Pending |

## Comparison

The after-optimization run will populate MySQL query reduction, P99 reduction, stable
successful-throughput multiplier, cache hit/miss/load counters, and the new limiting
resource. No improvement claim is made before that same-machine run is complete.
