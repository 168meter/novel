# Kafka Book Visit Aggregation Results

## Environment

| Item | Value |
|---|---|
| Operating system | Windows 11 Pro 10.0.26200 |
| CPU | Intel Core i5-14600KF, 14 cores / 20 logical processors |
| Memory | 31.8 GB |
| Java | Oracle JDK 21.0.10 LTS |
| Spring Boot | 3.4.0 |
| MySQL | Docker `mysql:8.0`, host port 3307 |
| Redis | Docker `redis:7-alpine`, host port 6380 |
| JMeter | Existing local Apache JMeter 5.6.3 |
| Book ID | `2055879962859147264` |
| Endpoint | `POST http://127.0.0.1:8083/book/addVisitCount` |
| Test shape | 10-second warm-up, then 60 seconds each at 1/10/30/50/100/200 threads |
| Success rule | HTTP 200 and response contains `"ok":true` |

## Before Kafka: synchronous MySQL update

Test date: 2026-09-02. Raw JTL and HTML reports are under
`performance/results/raw/book-visit-before-kafka/` and are intentionally ignored by Git.

| Threads | Samples | Throughput req/s | Average ms | P95 ms | P99 ms | Error % |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 12,536 | 209.14 | 4.66 | 6 | 8 | 0.00 |
| 10 | 30,527 | 509.11 | 19.27 | 24 | 30 | 0.00 |
| 30 | 33,034 | 550.60 | 51.79 | 60 | 71 | 0.00 |
| 50 | 32,247 | 537.15 | 85.42 | 105 | 115 | 0.00 |
| 100 | 32,605 | 542.43 | 153.88 | 201 | 213 | 0.00 |
| 200 | 30,783 | 510.62 | 294.15 | 447 | 478 | 0.00 |

The warm-up produced 2,023 successful requests. The six measured stages produced 171,732,
for 173,755 total requests during the bounded MySQL general-log window.

### Database evidence

| Check | Result |
|---|---:|
| Initial `visit_count` | 1,768 |
| Final `visit_count` | 175,523 |
| Database increment | 173,755 |
| Successful JMeter requests including warm-up | 173,755 |
| MySQL prepared-statement `Execute` rows for the increment SQL | 173,755 |

MySQL recorded the executed statement as:

```sql
update book
set visit_count = visit_count + 1
where id = 2055879962859147264
```

The three independent counts match exactly: every successful request increased the database
value once and generated one physical UPDATE. This is the baseline that Kafka aggregation
must reduce.

### Saturation point

Throughput reaches its highest measured value at 30 threads (550.60 req/s). Increasing the
load to 200 threads does not increase capacity; throughput falls to 510.62 req/s while P99
rises from 71 ms to 478 ms. The extra requests are waiting on the same hot database row rather
than producing useful throughput.

## Kafka integration and failure evidence

Test date: 2026-09-02. The checks used the same book, the local three-container
Compose environment, and the running novel-front process. No check reset the
book's existing visit count.

### Concurrent functional check

check-book-visit-kafka.ps1 sent 1,000 requests in bounded batches of 100.
MySQL general logging was enabled only for the request window. Prepared statements
were counted as command_type='Execute', and the increment values inside those
statements were summed independently.

| Check | Result |
|---|---:|
| Initial visit_count | 176,553 |
| HTTP accepted | 1,000 |
| Producer-confirmed successes | 1,000 |
| Producer failures | 0 |
| Final visit_count | 177,553 |
| Database increment | 1,000 |
| Consumer lag after drain | 0 |
| Drain time | 2,262 ms |
| Physical increment UPDATEs | 20 |
| Sum of all SQL increment deltas | 1,000 |
| SQL reduction against one UPDATE per request | 98.0% |

The important result is not merely that the endpoint returned quickly. Three
independent totals agree: producer confirmations, database delta, and summed SQL
deltas are all 1,000. At the same time, only 20 hot-row UPDATEs were executed.

### MySQL outage and recovery

MySQL was stopped, 100 concurrent visits were sent, and MySQL was restarted within
the configured finite retry window.

| Check | Result |
|---|---:|
| HTTP accepted while MySQL was down | 100 |
| Producer-confirmed successes | 100 |
| Consumer lag while MySQL was down | 100 |
| DLT records during outage | 0 |
| Database increment after recovery | 100 |
| Final consumer lag | 0 |
| MySQL health after recovery | healthy |

This proves the HTTP/Tomcat thread does not wait for MySQL and Kafka retains the
work until the database can commit it.

### Kafka outage boundary

With only Kafka stopped, a real mobile chapter page still returned the
contentIdHidden marker in 578 ms. Ten visit requests returned in 26 ms total,
but producer successes increased by 0, failures increased by 10, and MySQL did not
change. This is deliberate: the service exposes producer failure metrics and does
not silently fall back to the original synchronous hot-row UPDATE.

### Dead-letter isolation

One JSON event with bookId=0 increased the DLT end offset from 0 to 1. A valid
event published immediately afterward increased the target book by 1, and final
consumer lag was 0. The invalid event therefore did not poison later traffic.

These tests demonstrate at-least-once processing, not exactly-once processing.
A process crash after the database commit but before the Kafka offset commit can
still replay an increment. That small duplicate-count window is accepted for this
non-financial popularity counter.

## After Kafka benchmark

Test date: 2026-09-02. Raw JTL and HTML reports are under
performance/results/raw/book-visit-after-kafka/ and are intentionally ignored by Git.

| Threads | Samples | Throughput req/s | Average ms | P95 ms | P99 ms | JMeter errors | Error % |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 105,076 | 1,753.46 | 0.51 | 1 | 1 | 0 | 0.0000 |
| 10 | 1,059,669 | 17,682.07 | 0.52 | 1 | 1 | 3 | 0.0003 |
| 30 | 934,830 | 15,600.26 | 1.79 | 5 | 16 | 60,068 | 6.4256 |
| 50 | 1,197,452 | 19,975.18 | 2.26 | 31 | 43 | 27,652 | 2.3092 |
| 100 | 1,022,475 | 17,058.59 | 4.79 | 28 | 46 | 55,026 | 5.3816 |
| 200 | 1,050,980 | 17,529.77 | 8.38 | 119 | 129 | 47,780 | 4.5462 |

Every JMeter error was a load-generator-side Windows socket error:
java.net.BindException: Address already in use: getsockopt. No failed sample
contained an application HTTP 500 response. The higher-thread rows therefore show
that this one-machine test reached a client networking limit; they are not a clean
measurement of the server's absolute maximum capacity.

### End-to-end totals

| Check | Result |
|---|---:|
| Total JMeter samples including warm-up | 5,384,000 |
| Requests that reached the application successfully | 5,193,471 |
| Kafka producer-confirmed successes | 5,068,377 |
| Kafka producer failures/timeouts | 125,094 |
| Producer-confirmed rate among reached requests | 97.5905% |
| Initial visit_count | 177,654 |
| Final visit_count after lag drained | 5,246,511 |
| Database increment | 5,068,857 |
| Database increment above confirmed successes | 480 |
| Consumer database UPDATE count | 18,623 |
| SQL reduction versus one UPDATE per increment | 99.6326% |
| Maximum observed consumer lag | 1,965,279 |
| Final consumer lag | 0 |
| Approximate post-load drain time | 190 seconds |

The producer outcomes add up exactly to the requests that reached the application:
5,068,377 + 125,094 = 5,193,471. HTTP acceptance therefore did not hide missing
producer callbacks.

The database ended 480 above the producer-confirmed success count. Topic offsets and
consumer metrics did not show 480 duplicate consumptions. The most likely explanation
is an acknowledgement ambiguity under the deliberately short 3-second producer delivery
timeout: 480 sends completed to the broker but their client futures reported a timeout.
This is an inference from the three counters, not an exactly-once guarantee. It reinforces
why popularity counts may tolerate small over-counting and why financial writes need an
outbox/idempotency design.

The consumer database-update metric increased by 18,623 for the benchmark. At the measured
ratio, 10,000 persisted clicks require about 37 UPDATE statements instead of 10,000.
The earlier bounded 1,000-click burst used 20 UPDATEs; sustained traffic produces fuller
500-record consumer batches and a higher reduction.

### Before/after interpretation

At one thread, throughput rose from 209.14 to 1,753.46 req/s (8.38x) while P99 fell
from 8 ms to 1 ms. The synchronous version peaked at 550.60 req/s at 30 threads because
each request locked and updated the same MySQL row. The Kafka version reached roughly
20,000 req/s in this local test before the load generator and single broker became the
visible limits.

This is not unlimited capacity:

- A single hot book key goes to one Kafka partition to preserve per-book ordering.
- Maximum lag reached nearly two million and needed about 190 seconds to drain.
- 125,094 sends exceeded the intentionally short local producer timeout.
- The benchmark ran with verbose MyBatis/ShardingSphere SQL logging, which also consumed
  substantial console I/O.
- Failed producer sends are counted and are not synchronously written to MySQL.

The code review following this run added warning-rate limiting: every send failure still
increments the metric, but only the first and every 1,000th failure emits a full WARN.
This prevents another failure storm from generating hundreds of megabytes of logs.
