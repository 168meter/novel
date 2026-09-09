# Novel-Plus Local Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reproducible local Prometheus and Grafana stack that observes the Windows-hosted `novel-front` application and verifies its cache, Kafka, HTTP, JVM, and HikariCP signals.

**Architecture:** `novel-front` remains on Windows and exposes Actuator on port 8084 only when the explicit `monitoring` profile is active. Docker Compose runs Prometheus and Grafana; Prometheus scrapes `host.docker.internal:8084`, while Grafana provisions the datasource and dashboard from repository files.

**Tech Stack:** Spring Boot 3.4, Micrometer Prometheus registry, Docker Compose, Prometheus, PromQL, Grafana provisioning, PowerShell 7/Windows PowerShell.

**Spec:** `docs/superpowers/specs/2026-09-09-observability-design.md`

## Global Constraints

- Keep Java on the Windows host; do not containerize or deploy the application in this stage.
- Bind Prometheus port 9090 and Grafana port 3000 to `127.0.0.1` only.
- Bind Actuator to `0.0.0.0:8084` only through the explicit `monitoring` Spring profile.
- Do not modify the user's existing changes in the three dev/prod YAML files.
- Do not add Alertmanager, notification delivery, Loki, tracing, Redis Exporter, or MySQL Exporter.
- Keep the existing 46 Maven tests passing.
- Every task must add only its named files to Git; never use `git add .`.

---

### Task 1: Monitoring profile and static contract test

**Files:**
- Create: `novel-front/src/main/resources/application-monitoring.yml`
- Create: `performance/test-observability-config.ps1`

**Interfaces:**
- Consumes: existing Actuator configuration and `application="novel-front"` tag from `application-dev.yml`.
- Produces: explicit `monitoring` profile contract and a repository-level configuration test reused by later tasks.

- [ ] **Step 1: Write the failing configuration contract**

Create `performance/test-observability-config.ps1` with strict error handling and assertions that require the profile, Prometheus files, Grafana provisioning, dashboard, and Compose services:

```powershell
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Assert-FileContains([string]$Path, [string]$Pattern) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required file is missing: $Path"
    }
    if (-not (Select-String -LiteralPath $Path -Pattern $Pattern -Quiet)) {
        throw "Expected pattern '$Pattern' in $Path"
    }
}

$profile = Join-Path $root 'novel-front/src/main/resources/application-monitoring.yml'
Assert-FileContains $profile '^\s*address:\s*0\.0\.0\.0\s*$'
Assert-FileContains $profile '^\s*percentiles-histogram:'
Assert-FileContains $profile '^\s*http\.server\.requests:\s*true\s*$'

$compose = Join-Path $root 'compose.local.yml'
Assert-FileContains $compose '^\s{2}prometheus:\s*$'
Assert-FileContains $compose '^\s{2}grafana:\s*$'
Assert-FileContains $compose '127\.0\.0\.1:9090:9090'
Assert-FileContains $compose '127\.0\.0\.1:3000:3000'

@(
    'monitoring/prometheus/prometheus.yml'
    'monitoring/prometheus/rules/novel-plus-alerts.yml'
    'monitoring/grafana/provisioning/datasources/prometheus.yml'
    'monitoring/grafana/provisioning/dashboards/dashboards.yml'
    'monitoring/grafana/dashboards/novel-plus-overview.json'
) | ForEach-Object {
    $path = Join-Path $root $_
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required file is missing: $path"
    }
}

Write-Host 'Observability configuration contract passed.'
```

- [ ] **Step 2: Run the contract and verify RED**

Run:

```powershell
& '.\performance\test-observability-config.ps1'
```

Expected: FAIL with `Required file is missing: ...application-monitoring.yml`.

- [ ] **Step 3: Add the minimal Spring profile**

Create `application-monitoring.yml`:

```yaml
management:
  server:
    address: 0.0.0.0
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

- [ ] **Step 4: Run a focused profile test**

Run:

```powershell
Select-String -LiteralPath '.\novel-front\src\main\resources\application-monitoring.yml' -Pattern 'address: 0.0.0.0','http.server.requests: true'
```

Expected: both settings are printed. The full contract still fails at the missing Prometheus file, proving it advances to the next boundary.

- [ ] **Step 5: Commit the profile and contract**

```powershell
git add -- novel-front/src/main/resources/application-monitoring.yml performance/test-observability-config.ps1
git commit -m "test: define observability configuration contract"
```

### Task 2: Prometheus service, scrape configuration, and alert rules

**Files:**
- Modify: `compose.local.yml`
- Create: `monitoring/prometheus/prometheus.yml`
- Create: `monitoring/prometheus/rules/novel-plus-alerts.yml`

**Interfaces:**
- Consumes: Windows endpoint `host.docker.internal:8084/actuator/prometheus` and current Micrometer metric names.
- Produces: Compose service `prometheus`, persistent volume `novel-prometheus-data`, scrape job `novel-front`, and seven named alert rules.

- [ ] **Step 1: Extend the contract with Prometheus semantics**

Add assertions to `performance/test-observability-config.ps1`:

```powershell
$prometheus = Join-Path $root 'monitoring/prometheus/prometheus.yml'
Assert-FileContains $prometheus 'host\.docker\.internal:8084'
Assert-FileContains $prometheus '/actuator/prometheus'

$rules = Join-Path $root 'monitoring/prometheus/rules/novel-plus-alerts.yml'
@('NovelFrontDown','ChapterCacheErrors','LowChapterCacheHitRate','KafkaPublishFailures','KafkaConsumerLagHigh','HikariPendingConnections','JvmHeapUsageHigh') |
    ForEach-Object { Assert-FileContains $rules ("alert:\s*" + $_) }
```

- [ ] **Step 2: Run the contract and verify RED**

Expected: FAIL because `monitoring/prometheus/prometheus.yml` does not exist.

- [ ] **Step 3: Add Prometheus configuration**

Configure a 15-second scrape/evaluation interval, load `/etc/prometheus/rules/*.yml`, and scrape job `novel-front` at `host.docker.internal:8084` with metrics path `/actuator/prometheus`.

- [ ] **Step 4: Add exact alert expressions**

Implement these behaviors in `novel-plus-alerts.yml`:

```promql
up{job="novel-front"} == 0
sum(increase(novel_chapter_cache_lookup_total{result="error"}[5m])) + sum(increase(novel_chapter_cache_write_total{result="error"}[5m])) + sum(increase(novel_chapter_cache_lock_total{result="error"}[5m])) > 0
sum(increase(novel_chapter_cache_lookup_total{result="hit"}[5m])) / clamp_min(sum(increase(novel_chapter_cache_lookup_total[5m])), 1) < 0.90 and sum(increase(novel_chapter_cache_lookup_total[5m])) >= 100
sum(increase(novel_book_visit_kafka_send_total{result="failed"}[5m])) > 0
sum(kafka_consumer_fetch_manager_records_lag{topic="novel-book-visit-v1"}) > 10000
sum(hikaricp_connections_pending) > 0
sum(jvm_memory_used_bytes{area="heap"}) / clamp_min(sum(jvm_memory_max_bytes{area="heap"}), 1) > 0.85
```

Use `for: 1m`, immediate, `2m`, immediate, `2m`, `1m`, and `5m` respectively.

- [ ] **Step 5: Add the Compose service**

Add `prometheus` with pinned image `prom/prometheus:v3.5.0`, read-only config mounts, named data volume, healthcheck against `/-/ready`, and port mapping `127.0.0.1:9090:9090`. Do not make it depend on `novel-front` because the application is outside Compose.

- [ ] **Step 6: Validate Prometheus configuration**

Run:

```powershell
docker compose -f '.\compose.local.yml' config
docker run --rm `
  --entrypoint /bin/promtool `
  -v "${PWD}/monitoring/prometheus:/etc/prometheus:ro" `
  prom/prometheus:v3.5.0 `
  check config /etc/prometheus/prometheus.yml
```

Expected: Compose renders successfully; Prometheus config and rule checks report `SUCCESS`.

- [ ] **Step 7: Commit Prometheus**

```powershell
git add -- compose.local.yml monitoring/prometheus performance/test-observability-config.ps1
git commit -m "feat: add Prometheus monitoring and alerts"
```

### Task 3: Grafana provisioning and dashboard

**Files:**
- Modify: `compose.local.yml`
- Modify: `performance/test-observability-config.ps1`
- Create: `monitoring/grafana/provisioning/datasources/prometheus.yml`
- Create: `monitoring/grafana/provisioning/dashboards/dashboards.yml`
- Create: `monitoring/grafana/dashboards/novel-plus-overview.json`

**Interfaces:**
- Consumes: Compose DNS URL `http://prometheus:9090` and Prometheus series defined in Task 2.
- Produces: service `grafana`, datasource UID `prometheus`, and dashboard UID `novel-plus-overview`.

- [ ] **Step 1: Add failing dashboard assertions**

Add JSON parsing and title/UID checks:

```powershell
$dashboardPath = Join-Path $root 'monitoring/grafana/dashboards/novel-plus-overview.json'
$dashboard = Get-Content -LiteralPath $dashboardPath -Raw | ConvertFrom-Json
if ($dashboard.uid -ne 'novel-plus-overview') { throw 'Unexpected dashboard UID.' }
if ($dashboard.title -ne 'Novel-Plus Overview') { throw 'Unexpected dashboard title.' }
if ($dashboard.panels.Count -lt 12) { throw 'Dashboard must contain at least 12 panels.' }
```

- [ ] **Step 2: Run the contract and verify RED**

Expected: FAIL because Grafana files are missing.

- [ ] **Step 3: Add datasource and dashboard providers**

Provision one default Prometheus datasource with `uid: prometheus`, URL `http://prometheus:9090`, and `editable: false`. Provision dashboards from `/var/lib/grafana/dashboards` with UI updates disabled so the repository remains the source of truth.

- [ ] **Step 4: Add the dashboard**

Create valid Grafana dashboard JSON with rows/panels for:

- target state, request rate, P95, P99, and 5xx ratio;
- request-level chapter cache hit ratio, lookup outcomes, Redis writes, lock outcomes, DB load count and average latency;
- Kafka send results, consume/update rates, batch sizes, aggregated books, and summed topic lag;
- heap percentage, GC pause rate, live threads, Hikari active/idle/pending connections, and acquisition latency.

Every target must use datasource UID `prometheus`. Rate panels use `$__rate_interval`; percentile panels use `histogram_quantile` over `http_server_requests_seconds_bucket`.

- [ ] **Step 5: Add the Grafana Compose service**

Use pinned image `grafana/grafana:12.1.0`, depend on healthy Prometheus, mount provisioning read-only, mount dashboard JSON read-only, store state in `novel-grafana-data`, bind `127.0.0.1:3000:3000`, and set:

```yaml
GF_SECURITY_ADMIN_USER: admin
GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:-admin}
```

- [ ] **Step 6: Run static and Compose validation**

```powershell
& '.\performance\test-observability-config.ps1'
docker compose -f '.\compose.local.yml' config
```

Expected: both commands exit 0 and the contract prints `Observability configuration contract passed.`

- [ ] **Step 7: Commit Grafana**

```powershell
git add -- compose.local.yml monitoring/grafana performance/test-observability-config.ps1
git commit -m "feat: provision Grafana observability dashboard"
```

### Task 4: Reproducible application startup and runtime smoke test

**Files:**
- Create: `performance/start-front-monitoring.ps1`
- Create: `performance/check-observability.ps1`

**Interfaces:**
- Consumes: repository root, Maven, active app ports 8083/8084, Prometheus API on 9090, Grafana API on 3000.
- Produces: one command to start the host application correctly and one read-only runtime verifier returning exit code 0/1.

- [ ] **Step 1: Write runtime checks before relying on the stack**

Create `check-observability.ps1` with parameters for management, Prometheus, Grafana, and Grafana credentials. Implement `Invoke-RestMethod` checks for:

```text
GET http://127.0.0.1:8084/actuator/health
GET http://127.0.0.1:9090/-/ready
GET http://127.0.0.1:9090/api/v1/targets
GET http://127.0.0.1:9090/api/v1/query?query=novel_chapter_cache_lookup_total
GET http://127.0.0.1:9090/api/v1/query?query=novel_book_visit_kafka_send_total
GET http://127.0.0.1:9090/api/v1/rules
GET http://127.0.0.1:3000/api/health
GET http://127.0.0.1:3000/api/datasources/uid/prometheus
GET http://127.0.0.1:3000/api/dashboards/uid/novel-plus-overview
```

Assert health `UP`, active target health `up`, nonempty metric results, seven alert names, datasource UID, and dashboard UID. Never mutate application, Kafka, Redis, MySQL, or Grafana state.

- [ ] **Step 2: Run the smoke test and capture RED**

With monitoring containers stopped, run:

```powershell
& '.\performance\check-observability.ps1'
```

Expected: nonzero exit with a clear unavailable Prometheus message.

- [ ] **Step 3: Add the startup script**

Create `start-front-monitoring.ps1` that resolves the worktree from `$PSScriptRoot`, resolves the shared checkout from Git's common directory for the external `config`, changes to the worktree, and launches Maven with `spring.profiles.active=dev,monitoring` inside the JVM arguments:

```powershell
mvn -pl novel-front `
  -DskipTests `
  '-Dspring-boot.run.jvmArguments=-XX:TieredStopAtLevel=1 -Duser.dir=<git-common-parent> -Dspring.profiles.active=dev,monitoring -Dspring.data.redis.port=6380 -Dspring.data.redis.password=123456' `
  spring-boot:run
```

Do not hard-code `D:\offer\novel-plus` as `user.dir`; derive the shared checkout from `git rev-parse --path-format=absolute --git-common-dir`.

- [ ] **Step 4: Start and validate the live stack**

Run:

```powershell
docker compose -f '.\compose.local.yml' up -d
& '.\performance\start-front-monitoring.ps1'
```

Run the application command in its own terminal, then in another terminal:

```powershell
& '.\performance\check-observability.ps1'
```

Expected: every check prints PASS and the script exits 0.

- [ ] **Step 5: Verify a cache signal changes**

Delete only one known chapter cache key, request the same chapter 100 times, wait one scrape interval, and query Prometheus. Expected: one miss/DB load and approximately 99 hits, with no cache error increase.

- [ ] **Step 6: Verify a Kafka signal changes**

Run `check-book-visit-kafka.ps1` against a valid BookId, wait one scrape interval, and query send/consume/update counters and consumer lag. Expected: counters increase and lag returns to zero.

- [ ] **Step 7: Commit runtime tooling**

```powershell
git add -- performance/start-front-monitoring.ps1 performance/check-observability.ps1
git commit -m "test: add observability runtime verification"
```

### Task 5: Documentation, fault drill, and full verification

**Files:**
- Modify: `performance/README.md`
- Modify: `docs/learning/novel-plus-evolution-guide.md`

**Interfaces:**
- Consumes: all commands and endpoints delivered by Tasks 1-4.
- Produces: learner-facing runbook and current project-stage record.

- [ ] **Step 1: Add the local observability runbook**

Document exact commands to set an optional Grafana password, start Compose, start `novel-front` with the script, run the smoke test, and open:

```text
Prometheus targets: http://127.0.0.1:9090/targets
Prometheus alerts:  http://127.0.0.1:9090/alerts
Grafana:            http://127.0.0.1:3000
```

Explain what each dashboard section answers, why request-level hit ratio differs from Redis command hit rate, and why local alert thresholds are not production thresholds.

- [ ] **Step 2: Update the learning guide**

Mark completed cache, SQL, Kafka, and metrics stages accurately. Add the monitoring stage learning objectives, architecture, verification evidence, and the next boundary: deployment hardening rather than more local metrics.

- [ ] **Step 3: Run the down-alert drill**

Stop only the Windows `novel-front` process, leave Prometheus running, wait longer than one minute, and confirm `NovelFrontDown` is firing. Restart through `start-front-monitoring.ps1`, then confirm the alert returns to inactive. Do not stop MySQL, Redis, or Kafka for this drill.

- [ ] **Step 4: Run all verification commands**

```powershell
& '.\performance\test-observability-config.ps1'
docker compose -f '.\compose.local.yml' config
docker run --rm --entrypoint /bin/promtool -v "${PWD}/monitoring/prometheus:/etc/prometheus:ro" prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml
& '.\performance\check-observability.ps1'
mvn -pl novel-front -am -Dmaven.test.skip=false test
git diff --check
git status --short
```

Expected: static contract, Compose, Prometheus, runtime smoke checks, and all 46 Maven tests pass. Git status contains only planned monitoring/docs files plus the user's three pre-existing YAML modifications.

- [ ] **Step 5: Commit documentation**

```powershell
git add -- performance/README.md docs/learning/novel-plus-evolution-guide.md
git commit -m "docs: add local observability runbook"
```

- [ ] **Step 6: Perform branch completion review**

Use `superpowers:verification-before-completion`, then `superpowers:requesting-code-review`. Report exact commands, pass counts, live endpoints, remaining user-owned changes, and any alert threshold that still needs production calibration.
