# Hong Kong 2C4G Production Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reproducible, resource-bounded Novel-Plus production deployment for one 2 vCPU/4 GiB Hong Kong Linux server, with only Nginx public and all data/monitoring services isolated.

**Architecture:** Keep `compose.local.yml` unchanged and add one standalone `compose.prod.yml`. Nginx and `novel-front` share an edge network; `novel-front`, MySQL, Redis, and Kafka share an internal backend network; `novel-front`, Prometheus, and Grafana share an internal monitoring network. Production secrets live only in an ignored `.env.prod`, and a non-root front entrypoint renders the ShardingSphere datasource file into a private runtime directory before Java starts.

**Tech Stack:** Docker Compose v2, Nginx 1.28 Alpine, Eclipse Temurin Java 21, Spring Boot 3.4, MySQL 8.0, Redis 7, Kafka 4.3 KRaft, Prometheus 3.5, Grafana 12.1, PowerShell contract tests, POSIX shell operations scripts.

---

## File Structure

### Production orchestration

- `compose.prod.yml` — complete production topology; never merged with local Compose.
- `.env.prod.example` — required variable names with no usable secrets.
- `.dockerignore` — excludes Git metadata, secrets, logs, targets not needed by the image build, and backups.
- `.gitignore` — excludes real production environment, rendered configs, backups, and certificates.

### Front runtime

- `deploy/novel-front/Dockerfile` — reproducible multi-stage production image with a bounded runtime user. This intentionally leaves the legacy module packaging Dockerfile unchanged so local packaging behavior cannot regress.
- `deploy/novel-front/entrypoint.sh` — validates secrets, renders ShardingSphere config, and starts Java.
- `deploy/shardingsphere/shardingsphere-jdbc.yml.template` — environment-substituted physical datasource and existing sharding rule.

### Infrastructure configuration

- `deploy/mysql/conf.d/novel.cnf` — low-memory MySQL settings.
- `deploy/redis/redis.conf` — bounded cache/AOF settings; password is supplied at runtime.
- `deploy/prometheus/prometheus.yml` — Docker service target and 30-second scrape interval.
- `deploy/nginx/nginx.conf` — worker, logging, connection, gzip, and rate-limit defaults.
- `deploy/nginx/conf.d/novel-front.conf` — public reverse proxy and Actuator denial.

### Operations

- `deploy/scripts/init-database.sh` — seed import and ordered schema migrations.
- `deploy/scripts/init-kafka-topics.sh` — exact topic partitions and retention.
- `deploy/scripts/backup-mysql.sh` — bounded compressed logical backup.
- `deploy/scripts/restore-mysql.sh` — explicit restore into a healthy target database.
- `deploy/README.md` — server bootstrap, operation, HTTPS handoff, backup, restore, and migration.

### Application configuration

- `novel-front/src/main/resources/application-prod.yml` — container service names, Kafka, Tomcat, executors, upload limit, proxy trust, and feature boundaries.
- `novel-front/src/main/resources/application-monitoring.yml` — internal management port and minimal endpoint exposure.
- `novel-common/src/main/resources/application-common-prod.yml` — production Redis environment boundary.
- `novel-front/src/main/resources/logback-boot.xml` — production log level and bounded rolling files.
- `novel-front/src/main/resources/application-alipay.yml` — environment-only payment configuration.
- `novel-front/src/main/resources/application-oss.yml` — environment-only OSS configuration.

### Verification

- `performance/test-production-deployment-config.ps1` — static security/resource contracts and rendered Compose validation.
- `performance/check-production-deployment.sh` — Linux runtime ports, health, resources, lag, disk, and backup smoke checks.

## Task 1: Establish the production environment boundary

**Files:**
- Create: `.env.prod.example`
- Modify: `.gitignore`
- Create: `performance/test-production-deployment-config.ps1`

- [ ] **Step 1: Write the failing environment contract test**

Create `performance/test-production-deployment-config.ps1` with the initial checks:

```powershell
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$example = Join-Path $root '.env.prod.example'
$gitignore = Join-Path $root '.gitignore'

if (-not (Test-Path -LiteralPath $example -PathType Leaf)) {
    throw '.env.prod.example is missing.'
}
$exampleText = Get-Content -LiteralPath $example -Raw
$required = @(
    'MYSQL_ROOT_PASSWORD=', 'MYSQL_PASSWORD=', 'REDIS_PASSWORD=',
    'GRAFANA_ADMIN_PASSWORD=', 'JWT_SECRET=', 'CACHE_MANAGER_PASSWORD=',
    'NOVEL_AUTH_HMAC_SECRET=', 'NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET=',
    'MAIL_USERNAME=', 'MAIL_PASSWORD=', 'PUBLIC_DOMAIN='
)
foreach ($entry in $required) {
    if ($exampleText -notmatch ('(?m)^' + [regex]::Escape($entry))) {
        throw "Production environment variable is undocumented: $entry"
    }
}
if ($exampleText -match '(?i)(=123456|=admin$|=change-me|=password$)') {
    throw '.env.prod.example contains an unsafe usable default.'
}
$ignoreText = Get-Content -LiteralPath $gitignore -Raw
foreach ($entry in @('.env.prod', 'deploy/runtime/', 'deploy/backups/', 'deploy/certbot/')) {
    if ($ignoreText -notmatch [regex]::Escape($entry)) {
        throw "Production secret/runtime path is not ignored: $entry"
    }
}
Write-Output 'Production deployment configuration contracts passed.'
```

- [ ] **Step 2: Run the contract and verify RED**

Run:

```powershell
& .\performance\test-production-deployment-config.ps1
```

Expected: FAIL with `.env.prod.example is missing.`

- [ ] **Step 3: Add the ignored paths**

Append to `.gitignore`:

```gitignore
.env.prod
deploy/runtime/
deploy/backups/
deploy/certbot/
```

- [ ] **Step 4: Add the environment example**

Create `.env.prod.example`:

```dotenv
COMPOSE_PROJECT_NAME=novel-plus-prod
TZ=Asia/Shanghai
PUBLIC_DOMAIN=
MYSQL_DATABASE=novel_plus
MYSQL_USER=novel_app
MYSQL_PASSWORD=
MYSQL_ROOT_PASSWORD=
REDIS_PASSWORD=
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=
JWT_SECRET=
CACHE_MANAGER_PASSWORD=
NOVEL_AUTH_HMAC_SECRET=
NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET=
MAIL_HOST=smtp.163.com
MAIL_PORT=465
MAIL_USERNAME=
MAIL_PASSWORD=
NOVEL_AI_ENABLED=false
NOVEL_AI_API_KEY=
ALIPAY_APP_ID=
ALIPAY_MERCHANT_PRIVATE_KEY=
ALIPAY_PUBLIC_KEY=
ALIPAY_NOTIFY_URL=
ALIPAY_RETURN_URL=
OSS_ENDPOINT=
OSS_KEY_ID=
OSS_KEY_SECRET=
OSS_BUCKET_NAME=
OSS_FILE_HOST=pic
OSS_WEB_URL=
```

- [ ] **Step 5: Verify GREEN and commit**

Run:

```powershell
& .\performance\test-production-deployment-config.ps1
git diff --check -- .env.prod.example .gitignore performance/test-production-deployment-config.ps1
```

Expected: `Production deployment configuration contracts passed.`

Commit:

```powershell
git add -- .env.prod.example .gitignore performance/test-production-deployment-config.ps1
git commit -m "test: define production deployment boundary"
```

## Task 2: Remove tracked external credentials

**Files:**
- Modify: `performance/test-production-deployment-config.ps1`
- Modify: `novel-front/src/main/resources/application-alipay.yml`
- Modify: `novel-front/src/main/resources/application-oss.yml`
- Modify: `novel-admin/src/main/resources/application-dev.yml`
- Modify: `novel-admin/src/main/resources/application-prod.yml`

- [ ] **Step 1: Extend the test with a literal-secret scan**

Add this before the success output:

```powershell
$secretContracts = @{
    'novel-front/src/main/resources/application-alipay.yml' = @(
        '${ALIPAY_APP_ID:}', '${ALIPAY_MERCHANT_PRIVATE_KEY:}',
        '${ALIPAY_PUBLIC_KEY:}', '${ALIPAY_NOTIFY_URL:}', '${ALIPAY_RETURN_URL:}'
    )
    'novel-front/src/main/resources/application-oss.yml' = @(
        '${OSS_ENDPOINT:}', '${OSS_KEY_ID:}', '${OSS_KEY_SECRET:}',
        '${OSS_BUCKET_NAME:}', '${OSS_WEB_URL:}'
    )
    'novel-admin/src/main/resources/application-dev.yml' = @('${NOVEL_ADMIN_DEMO_USERNAME:}', '${NOVEL_ADMIN_DEMO_PASSWORD:}')
    'novel-admin/src/main/resources/application-prod.yml' = @('${NOVEL_ADMIN_DEMO_USERNAME}', '${NOVEL_ADMIN_DEMO_PASSWORD}')
}
foreach ($relativePath in $secretContracts.Keys) {
    $text = Get-Content -LiteralPath (Join-Path $root $relativePath) -Raw
    foreach ($reference in $secretContracts[$relativePath]) {
        if ($text -notmatch [regex]::Escape($reference)) {
            throw "Environment secret reference is missing from ${relativePath}: $reference"
        }
    }
}
```

- [ ] **Step 2: Run and verify RED**

Expected: FAIL on the first literal Alipay, OSS, or admin demo-login property.

- [ ] **Step 3: Replace literals with environment references**

Use these exact property boundaries:

```yaml
# application-alipay.yml
alipay:
  app-id: ${ALIPAY_APP_ID:}
  merchant-private-key: ${ALIPAY_MERCHANT_PRIVATE_KEY:}
  public-key: ${ALIPAY_PUBLIC_KEY:}
  notify-url: ${ALIPAY_NOTIFY_URL:}
  return-url: ${ALIPAY_RETURN_URL:}
  sign-type: RSA2
  charset: utf-8
  gateway-url: ${ALIPAY_GATEWAY_URL:https://openapi-sandbox.dl.alipaydev.com/gateway.do}
```

```yaml
# application-oss.yml
novel:
  file:
    endpoint: ${OSS_ENDPOINT:}
    key-id: ${OSS_KEY_ID:}
    key-secret: ${OSS_KEY_SECRET:}
    bucket-name: ${OSS_BUCKET_NAME:}
    file-host: ${OSS_FILE_HOST:pic}
    web-url: ${OSS_WEB_URL:}
```

For every sensitive YAML key, require exactly one non-comment occurrence whose value is the specified environment expression; reject duplicate or literal credential keys without printing their values. Replace the admin demo-login username and password with semantically separate `NOVEL_ADMIN_DEMO_USERNAME` and `NOVEL_ADMIN_DEMO_PASSWORD` environment expressions;
do not change unrelated user-owned database edits in those files.

- [ ] **Step 4: Verify and document external rotation**

Run the contract test and `git diff --check`. Record in `deploy/README.md` later that the
old Alipay and OSS credentials must be revoked at their providers before deployment, and the tracked admin demo credentials must never be reused.

- [ ] **Step 5: Commit**

```powershell
git add -- performance/test-production-deployment-config.ps1 `
  novel-front/src/main/resources/application-alipay.yml `
  novel-front/src/main/resources/application-oss.yml `
  novel-admin/src/main/resources/application-dev.yml `
  novel-admin/src/main/resources/application-prod.yml
git commit -m "security: remove tracked external credentials"
```

## Task 3: Add resource-bounded Spring production configuration

**Files:**
- Modify: `performance/test-production-deployment-config.ps1`
- Modify: `novel-front/src/main/resources/application-prod.yml`
- Modify: `novel-front/src/main/resources/application-monitoring.yml`
- Modify: `novel-common/src/main/resources/application-common-prod.yml`
- Modify: `novel-front/src/main/resources/logback-boot.xml`

- [ ] **Step 1: Add failing production property assertions**

Assert the production files contain these exact values/references:

```powershell
$frontProd = Get-Content (Join-Path $root 'novel-front/src/main/resources/application-prod.yml') -Raw
foreach ($value in @(
    'bootstrap-servers: kafka:19092', 'max-threads: 48', 'min-spare: 4',
    'max-connections: 512', 'accept-count: 100', 'core-pool-size: 2',
    'maximum-pool-size: 6', 'queue-size: 100', 'max-file-size: 10MB',
    'max-request-size: 10MB', '172.30.0.2'
)) {
    if ($frontProd -notmatch [regex]::Escape($value)) {
        throw "Bounded front production property is missing: $value"
    }
}
$commonProd = Get-Content (Join-Path $root 'novel-common/src/main/resources/application-common-prod.yml') -Raw
foreach ($value in @('host: redis', 'password: ${REDIS_PASSWORD}', 'timeout: 3000ms')) {
    if ($commonProd -notmatch [regex]::Escape($value)) {
        throw "Production Redis property is missing: $value"
    }
}
$monitoring = Get-Content (Join-Path $root 'novel-front/src/main/resources/application-monitoring.yml') -Raw
foreach ($value in @('port: 8084', 'include: health,prometheus,metrics', 'show-details: never')) {
    if ($monitoring -notmatch [regex]::Escape($value)) {
        throw "Internal management property is missing: $value"
    }
}
```

- [ ] **Step 2: Run and verify RED**

Expected: FAIL on `bootstrap-servers: kafka:19092`.

- [ ] **Step 3: Implement production application settings**

In `application-prod.yml`, preserve template and picture settings and add:

```yaml
server:
  tomcat:
    threads:
      max: 48
      min-spare: 4
    max-connections: 512
    accept-count: 100

spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
  kafka:
    bootstrap-servers: kafka:19092
    producer:
      acks: all
      key-serializer: org.apache.kafka.common.serialization.LongSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      properties:
        enable.idempotence: true
        max.block.ms: 500
        request.timeout.ms: 1000
        delivery.timeout.ms: 3000
    consumer:
      enable-auto-commit: false
      key-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      max-poll-records: 100
    listener:
      type: batch
      ack-mode: batch

thread:
  pool:
    core-pool-size: 2
    maximum-pool-size: 6
    keep-alive-time: 10
    queue-size: 100

novel:
  auth:
    trusted-proxy-addresses:
      - 172.30.0.2
  kafka:
    book-visit:
      topic: novel-book-visit-v1
      dlt-topic: novel-book-visit-dlt
      group-id: novel-book-visit-writer-v1
      max-poll-records: 100
    reading-engagement:
      max-poll-records: 100
      cleanup-batch-size: 1000
```

In `application-common-prod.yml`, use Redis service discovery and the required secret:

```yaml
spring:
  data:
    redis:
      host: redis
      port: 6379
      password: ${REDIS_PASSWORD}
      timeout: 3000ms
```

In `application-monitoring.yml`, configure the internal management server:

```yaml
management:
  server:
    port: 8084
    address: 0.0.0.0
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
  endpoint:
    health:
      show-details: never
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

- [ ] **Step 4: Bound production logging**

Use Spring profile sections in `logback-boot.xml`: keep the current developer console/file
behavior outside `prod`; for `prod`, set root `INFO`, `com.java2nb` `INFO`, 10 MiB files,
seven days, and a `256MB` total cap. Do not leave a production DEBUG logger.

- [ ] **Step 5: Run focused Java/config tests**

```powershell
& .\performance\test-production-deployment-config.ps1
& .\performance\test-auth-security-config.ps1
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -pl novel-front -am -DskipTests=false `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -Dtest=ThreadPoolConfigTest,AuthSecurityPropertiesTest test
```

Expected: all checks pass.

- [ ] **Step 6: Commit**

```powershell
git add -- performance/test-production-deployment-config.ps1 `
  novel-front/src/main/resources/application-prod.yml `
  novel-front/src/main/resources/application-monitoring.yml `
  novel-common/src/main/resources/application-common-prod.yml `
  novel-front/src/main/resources/logback-boot.xml
git commit -m "feat: bound production front resources"
```

## Task 4: Build a non-root front image and private datasource renderer

**Files:**
- Modify: `performance/test-production-deployment-config.ps1`
- Create: `.dockerignore`
- Create: `deploy/novel-front/Dockerfile`
- Create: `deploy/novel-front/entrypoint.sh`
- Create: `deploy/shardingsphere/shardingsphere-jdbc.yml.template`

- [ ] **Step 1: Add failing image/runtime security assertions**

Require the Dockerfile to use a non-root user, exact Java 21 image, health check, entrypoint,
and no secret ARG. Require the entrypoint to use allowlisted `envsubst`, `umask 077`, and
`exec java`. Require the datasource template to use `mysql`, `${MYSQL_USER}` and
`${MYSQL_PASSWORD}`, with pool size 10/2 and the existing ten-table sharding expression.

- [ ] **Step 2: Run and verify RED**

Expected: FAIL because `deploy/novel-front/Dockerfile` is absent.

- [ ] **Step 3: Add the datasource template**

Create `deploy/shardingsphere/shardingsphere-jdbc.yml.template`:

```yaml
mode:
  type: Standalone
  repository:
    type: JDBC
dataSources:
  ds_1:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://mysql:3306/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&connectTimeout=3000&socketTimeout=5000
    username: ${MYSQL_USER}
    password: ${MYSQL_PASSWORD}
    maximumPoolSize: 10
    minimumIdle: 2
    connectionTimeout: 5000
    validationTimeout: 3000
rules:
  - !SINGLE
    tables:
      - "*.*"
  - !SHARDING
    tables:
      book_content:
        actualDataNodes: ds_1.book_content${0..9}
        tableStrategy:
          standard:
            shardingColumn: index_id
            shardingAlgorithmName: bookContentSharding
    shardingAlgorithms:
      bookContentSharding:
        type: INLINE
        props:
          algorithm-expression: book_content${index_id % 10}
props:
  sql-show: false
```

- [ ] **Step 4: Add the entrypoint**

The entrypoint must:

```sh
#!/bin/sh
set -eu
umask 077
: "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
: "${MYSQL_USER:?MYSQL_USER is required}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"
: "${JWT_SECRET:?JWT_SECRET is required}"
: "${CACHE_MANAGER_PASSWORD:?CACHE_MANAGER_PASSWORD is required}"
: "${NOVEL_AUTH_HMAC_SECRET:?NOVEL_AUTH_HMAC_SECRET is required}"
: "${NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET:?reading HMAC secret is required}"
mkdir -p /app/runtime /app/logs
envsubst '${MYSQL_DATABASE} ${MYSQL_USER} ${MYSQL_PASSWORD}' \
  < /app/config/shardingsphere-jdbc.yml.template \
  > /app/runtime/shardingsphere-jdbc.yml
chmod 600 /app/runtime/shardingsphere-jdbc.yml
export SPRING_DATASOURCE_URL='jdbc:shardingsphere:absolutepath:/app/runtime/shardingsphere-jdbc.yml'
exec java -jar /app/novel-front.jar
```

- [ ] **Step 5: Add the image**

Use a build stage based on `maven:3.9.11-eclipse-temurin-21-alpine` and runtime stage
`eclipse-temurin:21.0.8_9-jre-alpine`. Build `novel-front` from a clean context, install
`gettext` and `wget` only in the runtime stage, create UID/GID 10001, copy the JAR, templates,
entrypoint, and datasource template, then switch to `USER 10001:10001`.

The health check is:

```dockerfile
HEALTHCHECK --interval=20s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8084/actuator/health | grep -q '"status":"UP"' || exit 1
```

- [ ] **Step 6: Add `.dockerignore` and verify**

Exclude `.git`, `.idea`, `.env.prod`, `deploy/runtime`, `deploy/backups`, `logs`, all module
`target` directories, raw performance results, and certificate material. Do not exclude
source, Maven POM files, templates, monitoring, or SQL migrations.

Run the contract test and:

```powershell
docker build -f deploy/novel-front/Dockerfile -t novel-front:prod-test .
docker inspect novel-front:prod-test --format '{{.Config.User}}'
```

Expected user: `10001:10001`.

- [ ] **Step 7: Commit**

```powershell
git add -- .dockerignore performance/test-production-deployment-config.ps1 `
  deploy/novel-front deploy/shardingsphere
git commit -m "feat: build hardened front container"
```

## Task 5: Add bounded MySQL and Redis configuration

**Files:**
- Modify: `performance/test-production-deployment-config.ps1`
- Create: `deploy/mysql/conf.d/novel.cnf`
- Create: `deploy/redis/redis.conf`
- Create: `deploy/scripts/init-database.sh`

- [ ] **Step 1: Add failing middleware configuration assertions**

Require MySQL buffer pool `384M`, `max_connections=60`, disabled binary log, and UTF-8 MB4.
Require Redis `maxmemory 176mb`, `allkeys-lfu`, AOF every second, and 64 MiB rewrite minimum.
Require database initialization to stream the ZIP, apply both 2026 migrations, avoid a
password command-line argument, and refuse a non-empty database unless `--migrate-only`
is supplied.

- [ ] **Step 2: Run and verify RED**

Expected: FAIL because `deploy/mysql/conf.d/novel.cnf` is absent.

- [ ] **Step 3: Create MySQL configuration**

```ini
[mysqld]
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
innodb_buffer_pool_size=384M
innodb_buffer_pool_instances=1
max_connections=60
table_open_cache=400
table_definition_cache=400
tmp_table_size=32M
max_heap_table_size=32M
skip-log-bin
performance_schema=OFF
```

- [ ] **Step 4: Create Redis configuration**

```conf
bind 0.0.0.0
protected-mode yes
port 6379
appendonly yes
appendfsync everysec
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
maxmemory 176mb
maxmemory-policy allkeys-lfu
save 900 1
save 300 10
```

The Compose command adds `--requirepass "$REDIS_PASSWORD"`; the password never enters the
tracked config.

- [ ] **Step 5: Implement idempotent database initialization**

`init-database.sh` loads `.env.prod`, waits for MySQL health, checks whether `book` exists,
streams `unzip -p doc/sql/novel_plus_data.sql.zip novel_plus_data.sql` only for an empty
database, and then applies:

```text
doc/sql/20260911_reading_daily_aggregation.sql
doc/sql/20260917_authentication_security.sql
```

Use `docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql mysql -uroot`; never
put a password after `-p`.

- [ ] **Step 6: Verify and commit**

Parse both config files with the contract test, run `sh -n` through an Alpine container,
then commit:

```powershell
git add -- performance/test-production-deployment-config.ps1 deploy/mysql deploy/redis `
  deploy/scripts/init-database.sh
git commit -m "feat: bound production data services"
```

## Task 6: Add Kafka topic retention and production monitoring

**Files:**
- Modify: `performance/test-production-deployment-config.ps1`
- Create: `deploy/scripts/init-kafka-topics.sh`
- Create: `deploy/prometheus/prometheus.yml`
- Reuse: `monitoring/prometheus/rules/novel-plus-alerts.yml`
- Reuse: `monitoring/grafana/provisioning/**`

- [ ] **Step 1: Add failing retention and scrape assertions**

Require Kafka topic creation for all four exact topic names with three partitions and
replication factor one. Main topics use `retention.ms=86400000` and
`retention.bytes=134217728` per partition; DLT topics use `retention.ms=604800000` and
`retention.bytes=268435456`. Require Prometheus to scrape `novel-front:8084` every 30
seconds and never use `host.docker.internal`.

- [ ] **Step 2: Run and verify RED**

Expected: FAIL because `deploy/scripts/init-kafka-topics.sh` is absent.

- [ ] **Step 3: Implement topic initialization**

Use Kafka's bundled `kafka-topics.sh --create --if-not-exists` for:

```text
novel-book-visit-v1
novel-book-visit-dlt
novel-reading-engagement-v1
novel-reading-engagement-dlt
```

Then use `kafka-configs.sh --alter --add-config` to enforce the exact retention settings
even when topics already exist. The script waits on `kafka-broker-api-versions.sh` and
returns nonzero if any topic description differs.

- [ ] **Step 4: Add production Prometheus configuration**

```yaml
global:
  scrape_interval: 30s
  evaluation_interval: 30s
rule_files:
  - /etc/prometheus/rules/*.yml
scrape_configs:
  - job_name: novel-front
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: [novel-front:8084]
```

- [ ] **Step 5: Validate and commit**

Run `promtool check config` in `prom/prometheus:v3.5.0`, shell syntax validation, and the
PowerShell contract. Commit:

```powershell
git add -- performance/test-production-deployment-config.ps1 `
  deploy/scripts/init-kafka-topics.sh deploy/prometheus/prometheus.yml
git commit -m "feat: bound production messaging and metrics"
```

## Task 7: Add the Nginx public boundary

**Files:**
- Modify: `performance/test-production-deployment-config.ps1`
- Create: `deploy/nginx/nginx.conf`
- Create: `deploy/nginx/conf.d/novel-front.conf`

- [ ] **Step 1: Add failing Nginx security assertions**

Require `client_max_body_size 10m`, Actuator denial, `proxy_pass http://novel-front:8083`,
replacement `X-Real-IP`, forwarded scheme and host, bounded proxy timeouts, rate limiting,
gzip, and no proxy cache for HTML/API responses.

- [ ] **Step 2: Run and verify RED**

Expected: FAIL because the Nginx files are absent.

- [ ] **Step 3: Create global Nginx configuration**

Use at most two workers, `worker_connections 1024`, stdout/stderr logs, MIME types, gzip
for text/CSS/JavaScript/JSON/SVG, `server_tokens off`, and these zones:

```nginx
limit_req_zone $binary_remote_addr zone=public_api:10m rate=20r/s;
limit_conn_zone $binary_remote_addr zone=per_ip:10m;
```

- [ ] **Step 4: Create the front virtual host**

The port 80 server must include:

```nginx
location = /actuator { return 404; }
location ^~ /actuator/ { return 404; }
location / {
    limit_req zone=public_api burst=40 nodelay;
    limit_conn per_ip 30;
    proxy_pass http://novel-front:8083;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_connect_timeout 3s;
    proxy_send_timeout 30s;
    proxy_read_timeout 30s;
}
```

Reserve commented certificate mount paths and a separate documented HTTPS activation
snippet, but do not invent a certificate or redirect HTTP before HTTPS exists.

- [ ] **Step 5: Validate and commit**

Mount the files into `nginx:1.28.0-alpine` and run `nginx -t`. Run the production contract
and commit:

```powershell
git add -- performance/test-production-deployment-config.ps1 deploy/nginx
git commit -m "feat: add production nginx boundary"
```

## Task 8: Assemble the production Compose topology

**Files:**
- Modify: `performance/test-production-deployment-config.ps1`
- Create: `compose.prod.yml`

- [ ] **Step 1: Add a rendered Compose topology test**

The test creates a temporary `.env` containing non-secret fixture values, runs:

```powershell
docker compose --env-file $fixture -f $compose config --format json
```

Parse the JSON and assert:

- Services are exactly `nginx`, `novel-front`, `mysql`, `redis`, `kafka`, `prometheus`, and
  `grafana`.
- Only Nginx publishes a public port; before TLS activation that port is exactly `80`.
  Prometheus and Grafana may bind only to `127.0.0.1`.
- MySQL, Redis, Kafka, front, and Actuator publish no host ports.
- Every service has restart policy, health check, memory limit, and Docker log rotation.
- `backend` and `monitoring` are internal networks.
- Nginx has fixed edge address `172.30.0.2`.
- No service uses `privileged`, host networking, or the Docker socket.
- No image uses `latest`.

- [ ] **Step 2: Run and verify RED**

Expected: FAIL because `compose.prod.yml` is absent.

- [ ] **Step 3: Define common service policies**

For every service use:

```yaml
restart: unless-stopped
logging:
  driver: json-file
  options:
    max-size: 10m
    max-file: "3"
```

Set these image/build identities and memory limits:

```text
nginx: nginx:1.28.0-alpine, 64m
novel-front: build deploy/novel-front/Dockerfile, 896m
mysql: mysql:8.0.46, 768m
redis: redis:7.4.7-alpine, 256m
kafka: apache/kafka:4.3.1, 640m
prometheus: prom/prometheus:v3.5.0, 384m
grafana: grafana/grafana:12.1.0, 256m
```

- [ ] **Step 4: Configure public and internal networking**

Only Nginx publishes host ports. Use:

```yaml
ports:
  - "80:80"
```

Do not publish `443` until the TLS listener and certificate exist. Task 13 adds
`443:443` in the same change that activates TLS. Prometheus and Grafana bind to
`127.0.0.1`. Define edge subnet `172.30.0.0/24`, assign
Nginx `172.30.0.2`, and mark backend and monitoring internal.

- [ ] **Step 5: Configure service-specific limits**

- Front: `JAVA_TOOL_OPTIONS=-Xms256m -Xmx512m -XX:+UseG1GC
  -XX:MaxMetaspaceSize=160m -XX:+ExitOnOutOfMemoryError`, required secrets, SMTP variables,
  `SPRING_PROFILES_ACTIVE=prod,monitoring`, no ports.
- MySQL: official initialization variables, `novel.cnf`, named data volume, no ports.
- Redis: production config plus runtime password, named data volume, no ports.
- Kafka: one internal listener, `KAFKA_HEAP_OPTS=-Xms384m -Xmx384m`, one KRaft node,
  global retention fallback, named data volume, no ports.
- Prometheus: `--storage.tsdb.retention.time=7d` and
  `--storage.tsdb.retention.size=1GB`.
- Grafana: no anonymous access, required password, no plugin installation.

Use health-conditioned dependencies without introducing a circular dependency. Nginx
depends on a healthy front; front depends on healthy MySQL, Redis, and Kafka; monitoring
depends on a healthy front/Prometheus as applicable.

- [ ] **Step 6: Render and inspect the Compose model**

```powershell
& .\performance\test-production-deployment-config.ps1
docker compose --env-file .env.prod.test -f compose.prod.yml config --quiet
```

The test owns and deletes `.env.prod.test`; do not commit it.

- [ ] **Step 7: Commit**

```powershell
git add -- compose.prod.yml performance/test-production-deployment-config.ps1
git commit -m "feat: add 2c4g production compose stack"
```

## Task 9: Add backup and restore operations

**Files:**
- Modify: `performance/test-production-deployment-config.ps1`
- Create: `deploy/scripts/backup-mysql.sh`
- Create: `deploy/scripts/restore-mysql.sh`

- [ ] **Step 1: Add failing backup safety assertions**

Require both scripts to use `set -eu`, load `.env.prod`, use `MYSQL_PWD` rather than `-p`,
write backups under `deploy/backups`, use gzip integrity checks, enforce restrictive
permissions, and never delete files outside the resolved backup directory.

- [ ] **Step 2: Run and verify RED**

Expected: FAIL because the backup script is absent.

- [ ] **Step 3: Implement bounded backups**

`backup-mysql.sh` creates:

```text
deploy/backups/novel_plus-YYYYmmdd-HHMMSS.sql.gz
deploy/backups/novel_plus-YYYYmmdd-HHMMSS.sql.gz.sha256
```

Use `mysqldump --single-transaction --quick --routines --triggers --events`, verify with
`gzip -t`, write SHA-256, and retain the newest seven successful backup pairs. Resolve the
backup directory to an absolute path before deletion and reject paths outside the repository
deployment directory.

- [ ] **Step 4: Implement explicit restore**

`restore-mysql.sh` requires one backup path plus the literal `--confirm-restore`. It verifies
the gzip stream and SHA-256 file, requires healthy MySQL, prints the target database, and
streams decompressed SQL through `docker compose exec -T mysql mysql`. It never drops a
database automatically.

- [ ] **Step 5: Verify syntax and commit**

Run `sh -n`, contract tests, and a behavior test with fake `docker`, `mysqldump`, and `gzip`
commands that proves retention never escapes the isolated temporary backup directory.

```powershell
git add -- performance/test-production-deployment-config.ps1 `
  deploy/scripts/backup-mysql.sh deploy/scripts/restore-mysql.sh
git commit -m "feat: add bounded database backup operations"
```

## Task 10: Add Linux runtime acceptance

**Files:**
- Create: `performance/check-production-deployment.sh`
- Modify: `performance/test-production-deployment-config.ps1`

- [ ] **Step 1: Add a failing contract for the runtime checker**

Require checks for container health, public sockets, Actuator denial, Prometheus target,
Kafka consumer lag, memory limits, current memory/swap, disk free space, and a fresh backup.
Require a read-only default and an explicit `--allow-backup` switch for creating a dump.

- [ ] **Step 2: Run and verify RED**

Expected: FAIL because the Linux checker is absent.

- [ ] **Step 3: Implement read-only checks**

The script must verify:

```text
docker compose ps reports all seven services healthy
ss exposes only configured SSH, 80, and 443 publicly
curl / returns HTTP 200
curl /actuator/health returns HTTP 404 through Nginx
Prometheus target novel-front is UP
book-visit and reading consumer lag are zero or explicitly reported nonzero
docker stats remains under each declared memory limit
free shows physical headroom and no sustained swap growth
df shows at least 10 GiB free
```

Do not print `.env.prod`, passwords, JWTs, email addresses, IP HMAC values, or rendered
ShardingSphere content.

- [ ] **Step 4: Implement optional backup smoke**

With `--allow-backup`, call `backup-mysql.sh`, verify gzip and SHA-256, then report only the
filename and size. Restore testing uses an isolated database/volume and is not performed
against live data by default.

- [ ] **Step 5: Verify and commit**

Run `sh -n`, the PowerShell contract, and fake-command behavior tests.

```powershell
git add -- performance/check-production-deployment.sh `
  performance/test-production-deployment-config.ps1
git commit -m "test: verify production deployment runtime"
```

## Task 11: Write the operational deployment guide

**Files:**
- Create: `deploy/README.md`
- Modify: `docs/learning/novel-plus-evolution-guide.md`
- Modify: `performance/README.md`
- Modify: `performance/test-production-deployment-config.ps1`

- [ ] **Step 1: Add a documentation completeness contract**

Require exact documented commands for prerequisites, `.env.prod` generation, build,
startup, stop, logs, `docker stats`, health, backup, restore, SSH tunnels, HTTPS activation,
migration, rollback, and first-launch acceptance. Require explicit credential rotation and
copyright checks.

- [ ] **Step 2: Run and verify RED**

Expected: FAIL because `deploy/README.md` is absent.

- [ ] **Step 3: Document server initialization**

Document Ubuntu 24.04 setup with Docker Engine/Compose plugin, `unzip`, 4 GiB swap,
`vm.swappiness=10`, timezone, UFW/security-group rules, a non-root deploy user, SSH keys,
and disabled password SSH login. Do not include commands that open middleware ports.

- [ ] **Step 4: Document normal operations**

Include these exact command families:

```bash
docker compose --env-file .env.prod -f compose.prod.yml build
docker compose --env-file .env.prod -f compose.prod.yml up -d
docker compose --env-file .env.prod -f compose.prod.yml down
docker compose --env-file .env.prod -f compose.prod.yml ps
docker compose --env-file .env.prod -f compose.prod.yml logs -f --tail=200 novel-front
docker stats --no-stream
./performance/check-production-deployment.sh
./deploy/scripts/backup-mysql.sh
```

Document SSH tunnels for loopback Grafana/Prometheus and HTTPS activation after DNS and
certificate readiness. State that real login/registration remains disabled or restricted
until HTTPS is active.

- [ ] **Step 5: Document migration and rollback**

Specify drain, lag-zero, final backup, copy, restore, smoke, DNS switch, and rollback order.
State that MySQL is authoritative; Redis and Kafka volumes are not required for migration
after consumer lag reaches zero.

- [ ] **Step 6: Update the learning and performance guides**

Add the final topology, resource table, operational commands, security boundaries, and the
fact that 2C4G capacity results are environment-specific rather than universal.

- [ ] **Step 7: Verify and commit**

```powershell
& .\performance\test-production-deployment-config.ps1
git diff --check -- deploy/README.md docs/learning/novel-plus-evolution-guide.md `
  performance/README.md performance/test-production-deployment-config.ps1
git add -- deploy/README.md docs/learning/novel-plus-evolution-guide.md `
  performance/README.md performance/test-production-deployment-config.ps1
git commit -m "docs: add production deployment runbook"
```

## Task 12: Run the complete pre-server verification gate

**Files:**
- Verify only; modify the owning task's files if a check fails.

- [ ] **Step 1: Run static production contracts**

```powershell
& .\performance\test-production-deployment-config.ps1
& .\performance\test-auth-security-config.ps1
& .\performance\test-observability-config.ps1
```

Expected: all three pass.

- [ ] **Step 2: Validate generated Compose and Nginx**

Use a temporary fixture env generated by the test script:

```powershell
docker compose --env-file .env.prod.test -f compose.prod.yml config --quiet
docker run --rm -v "${PWD}/deploy/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" `
  -v "${PWD}/deploy/nginx/conf.d:/etc/nginx/conf.d:ro" nginx:1.28.0-alpine nginx -t
```

Expected: valid configuration; remove `.env.prod.test` in `finally`.

- [ ] **Step 3: Validate Prometheus and shell scripts**

```powershell
docker run --rm --entrypoint /bin/promtool `
  -v "${PWD}/deploy/prometheus:/etc/prometheus:ro" `
  -v "${PWD}/monitoring/prometheus/rules:/etc/prometheus/rules:ro" `
  prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml
docker run --rm -v "${PWD}:/repo:ro" alpine:3.22.1 sh -c `
  'find /repo/deploy /repo/performance -type f -name "*.sh" -exec sh -n {} \;'
```

Expected: Prometheus success and zero shell syntax failures.

- [ ] **Step 4: Run Java regressions and build the image**

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -DskipTests=false test
docker build -f deploy/novel-front/Dockerfile -t novel-front:prod-test .
```

Expected: Maven BUILD SUCCESS and Docker build success.

- [ ] **Step 5: Inspect repository hygiene**

```powershell
git grep -n -E '(BEGIN (RSA )?PRIVATE KEY|password:[[:space:]]+[^$]|key-secret:[[:space:]]+[^$])' -- `
  '*.yml' '*.yaml' '*.properties'
git diff --check
git status --short
```

Expected: no usable secret finding; only explicitly preserved unrelated user changes remain.

- [ ] **Step 6: Record the pre-server checkpoint**

Append commands and verified results to `deploy/README.md`, rerun its contract, and commit:

```powershell
git add -- deploy/README.md
git commit -m "docs: record production preflight verification"
```

## Task 13: Execute the first Linux deployment after the server exists

**Files:**
- Server runtime state only; commit documentation corrections separately.

- [ ] **Step 1: Initialize the host without opening application ports**

Create the deploy user, install Docker/Compose/unzip, configure 4 GiB swap and
`vm.swappiness=10`, configure SSH-key access, and allow only SSH plus 80. Leave 443 closed
until its Nginx listener and certificate exist.

- [ ] **Step 2: Create production secrets and rotate exposed credentials**

Copy `.env.prod.example` to `.env.prod`, generate independent high-entropy values, set mode
`0600`, and rotate the previously tracked Alipay, OSS, and mail credentials at their
providers. Never paste the completed file into chat or a Git command.

- [ ] **Step 3: Build infrastructure and initialize data**

Build the front image while application services are stopped, start MySQL/Redis/Kafka,
run database initialization, run Kafka topic initialization, and inspect disk/memory before
starting the front.

- [ ] **Step 4: Start the private application and monitoring path**

Start `novel-front`, Prometheus, and Grafana. Verify the internal Actuator target and both
loopback monitoring endpoints before starting Nginx.

- [ ] **Step 5: Start Nginx and run production acceptance**

Start Nginx, run `check-production-deployment.sh`, then run bounded chapter, Kafka,
reading, recommendation, observability, and authentication smoke checks against the
server. Do not run container-stop failure drills until a fresh backup exists.

- [ ] **Step 6: Enable HTTPS and public authentication**

After DNS resolves, obtain the certificate, mount it read-only, activate the TLS listener,
add the `443:443` Nginx port mapping, open 443, redirect 80 to 443, rerun Nginx validation,
and verify secure cookies and proxy scheme handling. Only then permit real registration or
passwords.

- [ ] **Step 7: Create and export the first backup**

Create a compressed backup, verify it, copy it off-server, and record its checksum. Perform
one restore rehearsal into an isolated volume/database before calling deployment complete.

