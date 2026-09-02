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

## After Kafka

This section will be filled only after the approved Kafka implementation runs the exact same
JMeter plan, book ID, thread stages, duration, JVM arguments, and machine. The comparison must
include producer-confirmed messages, final database increment, UPDATE count, maximum consumer
lag, and drain time; HTTP acceptance alone is not sufficient evidence.
