# Benchmark observability

`Novel-Plus Overview` is the provisioned, 12-panel evidence dashboard. Its UID
and Prometheus datasource UID remain unchanged. Authentication metrics remain
in the application; authentication and maintenance charts no longer occupy
this dashboard.

## Before collecting evidence

Select the experiment's **job**, **instance**, and a single fixed time range.
Record the Git SHA, hardware, JVM/heap settings, pool sizes, data set, request
mix, concurrency, warm-up, duration, and load-generator version. Keep the
load-generator report: server metrics cannot account for requests that never
reach the application, client timeouts, or client-side queueing.

The dashboard preserves instance throughout aggregation. HTTP latency is a
histogram estimate per instance, not an average of instance percentiles. The
chapter filter is exactly GET `/book/{bookId}/{bookIndexId}.html`; latency
uses HTTP 200 only. Status throughput/share exposes 200, 404, 503, 500, and
other statuses when actually observed. An unobserved status is not fabricated.

Prometheus scrapes every 15 seconds. Queries use `$__rate_interval`, with a
15-second query minimum; use a consistent window for comparisons. A 5-minute
window smooths short events, so correlate fault times with logs, raw gauges,
counter changes, and client results. No-activity ratios and latency are N/A.
A registered counter's rate of 0 means no observed events in that window;
it does not establish successful execution of that path.

## Panel semantics

| Panel | Meaning and limit |
| --- | --- |
| Application Target / Uptime | Scrape health and restart evidence; not dependency health. |
| Chapter Throughput by Status | Successful and failed chapter requests per second. |
| Successful Chapter Latency | P50/P95/P99 for HTTP 200; no successful requests means N/A. |
| Chapter HTTP Status Share | Per-status fraction, only with a positive per-instance denominator. |
| Chapter Cache Lookup / Hit Ratio | First lookup only; hit includes a null marker; error remains in the ratio denominator. |
| Chapter Content Loader / Write / Front Eviction | Loader executions include failures, not all SQL; writes include null markers; eviction counts operations, not deleted keys or TTL expiry. |
| Shared Front Executor | Active/max and queue occupancy fractions; rejected tasks/s on a separate axis. The pool serves multiple front pages. Execution/idle timers are excluded because their wrapper is not used. |
| Kafka Application Flow | Producer acknowledgements, committed received events, aggregated book updates, and persisted reading seconds have distinct units. Reading received includes deduplication. Book retry counts failed deliveries; reading retry excludes the initial attempt. Book DLT counts recovery callbacks, not separately confirmed broker acknowledgements. |
| Kafka Client Fetch Lag | Per-client/topic/partition fetch-position lag, not committed group, database, or end-to-end lag. Missing data is not zero. |
| Hikari Connection Pressure | Active/max, pending, connection timeouts/s, and supporting acquisition duration; not SQL throughput. |
| JVM Resource | Process CPU, used heap bytes, and GC pause seconds/elapsed seconds. Heap bytes avoid inventing a capacity from unknown per-pool maxima. |
| Reading / Degradation Outcomes | Redis accepted seconds differ from DB persisted seconds; expose heartbeat, registration errors, home source and refresh failures. Not complete coverage of every cache fallback. |

Reading client metrics are bound using Spring Kafka's `MicrometerConsumerListener`
on its dedicated consumer factory. Client metrics are removed when the consumer
closes. Group, topic, offsets, concurrency, retry, and DLT behavior are unchanged.
Existing finite error/outcome counters are registered at startup with zero;
event code retains its original increments. No ID, session, or IP tags are added.

## Alert interpretation

Core cache, executor, Kafka, connection-pool, and recommendation alerts retain
`job` and `instance`. Existing authentication and daily-cleanup alerts are
unchanged. Chapter 5xx alerting requires over 5% errors, at least 20 requests in
five minutes, and one minute of sustained condition. Chapter 503 is a separate
observed outcome during an active request window. These are diagnostic defaults,
not a performance SLO inferred from measurements.

`ReadingConsumerMetricsMissing` warns if a reachable application lacks reading
client connection or topic lag input for five minutes. Lag may require fetched
topic records before Kafka creates partition metrics; inspect assignment and
traffic before interpreting this warning as a dependency outage. `NovelFrontDown`
also handles the absence of the entire application target. Known-positive pool
maxima are used by the existing heap-pressure rule; the dashboard displays bytes
instead of claiming a universally valid total-heap ratio.

## Docker Desktop and Linux host access

The application runs on the host with Actuator listening on port 8084. The
Prometheus service has:

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

This retains the scrape target `host.docker.internal:8084` and provides an explicit
host-gateway mapping on Linux Docker Engine. Use Docker Engine 20.10+ and Compose
with `host-gateway` support. Grafana continues to reach `http://prometheus:9090`
through the Compose network. Existing host port defaults and `.env` overrides
are unchanged; actual secrets and runtime configurations must remain private.

Compose parsing and a live Docker Desktop scrape validate this configuration
locally. They do **not** validate a native Linux cloud deployment, its host
firewall, application launcher, secrets, or dependency addresses. At cloud smoke,
verify the host-gateway resolution, port 8084 reachability, both client metric
families and their topic partitions, datasource health, and all 12 panels. Keep
management and infrastructure ports within the intended private access boundary.

## Verification

From the repository root:

```powershell
& ./performance/test-observability-config.ps1
docker run --rm --entrypoint promtool `
  -v "${PWD}/monitoring/prometheus:/audit:ro" -w /audit/tests `
  prom/prometheus:v3.5.0 test rules benchmark-evidence.test.yml
mvn '-Dmaven.test.skip=false' test
node --test novel-front/src/test/javascript/*.test.mjs
```

The Prometheus tests use synthetic time series only: idle windows, instance
isolation, unrelated HTTP traffic, 503/404 distinction, missing reading metric
input, histogram grouping, and counter resets. They are not a benchmark or a
fault experiment. The runtime checker accepts the actual local Prometheus port
and private Grafana credentials as parameters; it requires live client metrics,
preregistered counters, the new rules, datasource connectivity, and provisioned
queries matching the tracked dashboard.

## Evidence still required

Latency, ratio and Hikari panels omit last-non-null legend summaries so that
an earlier observation is not presented as a current value during an idle window.
Their time-series graphs still show observations within the selected time range.

Formal cold/warm and concurrency experiments must establish successful throughput,
latency distributions, loader changes and resource behavior. Fault experiments
must establish degradation, backlog, recovery and data correctness. Group offsets
and database reconciliation remain separate evidence sources. This dashboard
does not prove improvement percentages, peak QPS, fault recovery time, all-SQL
reduction, or end-to-end Kafka latency.
