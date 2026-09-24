# Novel-Plus Hong Kong 2C4G Production Deployment Design

## 1. Objective

Deploy the current Novel-Plus front site to one temporary Hong Kong Linux server with
2 vCPU, 4 GiB physical memory, approximately 50 GiB system disk, limited transfer, and
a planned lifetime of one to three months. The deployment must preserve the chapter
cache, asynchronous book-visit counting, reading-time aggregation, MySQL persistence,
and Prometheus/Grafana observability while keeping the public attack surface minimal.

The first release optimizes for reproducibility, bounded resource usage, safe failure,
and easy migration. It does not attempt to provide multi-node high availability.

## 2. Scope

### Included

- Nginx as the only public entry point.
- `novel-front` with the `prod,monitoring` Spring profiles.
- MySQL, Redis, and a single KRaft Kafka broker on Docker-internal networking.
- Prometheus and Grafana reachable only through host loopback and an SSH tunnel.
- Existing Kafka book-visit and reading-engagement topics and consumers.
- Existing authentication, chapter-cache, recommendation, and observability behavior.
- Bounded logs, metrics retention, Kafka retention, and Redis memory.
- Environment-file based production secrets.
- MySQL initialization, schema upgrades, backup, restore, and host-to-host migration.
- A later HTTPS activation path that does not require redesigning the container network.

### Excluded

- Public or production deployment of `novel-admin`.
- Deployment of `novel-crawl`.
- Mailpit in production.
- Kubernetes, service discovery, ELK, distributed tracing, managed middleware, or a
  separate API gateway.
- Multi-broker Kafka, MySQL replication, Redis Sentinel, and automatic failover.
- Public authentication over plain HTTP. Before HTTPS is enabled, access is limited to
  deployment smoke testing and must not use real user passwords.

## 3. Current-State Findings

The existing `compose.local.yml` is a development stack. It publishes MySQL on 3307,
Redis on 6380, and Kafka on 9092 to every host interface. It does not run Nginx or
`novel-front`. Prometheus and Grafana bind to loopback, and Mailpit is a local-only test
dependency.

The current production Spring configuration still points Redis and ShardingSphere at
`localhost`; Kafka configuration exists only in the development profile. The packaged
ShardingSphere files use the MySQL root account. The front Dockerfile has no JVM limit,
health check, templates, runtime configuration renderer, or non-root user. The custom
business executor is configured as 10 core threads, 20 maximum threads, and a queue of
1000, while Tomcat retains its much larger defaults.

Prometheus has no storage retention limit, Kafka has no deployment-specific log
retention, Redis has no memory limit, and Docker logging has no rotation. Spring logging
writes both console output and rolling files, including an explicit DEBUG logger that
overrides the intended production log level.

Tracked configuration contains literal payment, OSS, mail, database, and cache
credentials. The payment private key and OSS credentials must be considered compromised,
revoked at their providers, and replaced. Removing them only from the current Git tree
does not make the old values safe.

## 4. Selected Deployment Model

The production deployment uses one standalone `compose.prod.yml`. The existing
`compose.local.yml` remains unchanged for Windows development.

This is safer than layering a production override on the local file because an omitted
override cannot accidentally retain development port publications or default passwords.
It is simpler and more portable than installing MySQL, Redis, Kafka, or Nginx directly on
the host.

The real `.env.prod` is created on the server with mode `0600` and is ignored by Git.
Only `.env.prod.example`, containing variable names and validation guidance, is tracked.

## 5. Target Topology

```text
Internet
   |
   +-- TCP 80
   `-- TCP 443 (enabled after a domain and certificate are ready)
           |
        [nginx]
           | edge network
           v
   [novel-front:8083]
           |
           +---------------- backend (internal) ----------------+
           |                         |                           |
           v                         v                           v
      [mysql:3306]             [redis:6379]               [kafka:19092]

   [novel-front:8084] -- monitoring (internal) --> [prometheus:9090]
                                                     |
                                                     v
                                                [grafana:3000]
                                                     |
                                      loopback-access (non-internal bridge)
                                                     |
                              127.0.0.1:9090 / 127.0.0.1:3000
```

Only Nginx publishes ports on public interfaces. Prometheus and Grafana may publish to
`127.0.0.1` for SSH port forwarding. MySQL, Redis, Kafka, `novel-front:8083`, and
`novel-front:8084` have no host port mapping.

The `edge` network contains Nginx and `novel-front`. The `backend` network is marked
`internal: true` and contains `novel-front`, MySQL, Redis, and Kafka. The `monitoring`
network is also internal and contains `novel-front`, Prometheus, and Grafana.

Prometheus and Grafana additionally join a dedicated, non-internal `loopback-access`
bridge network. This network exists only because Docker does not create host port
listeners for containers attached exclusively to an `internal: true` network. Published
ports remain explicitly bound to host `127.0.0.1`, so the additional network enables SSH
tunnels without creating a public listener. `novel-front` does not join this network.

Nginx receives a fixed address on a declared private subnet. The application trusts only
that exact proxy address because the existing trusted-proxy implementation intentionally
accepts IP literals rather than arbitrary proxy headers. Nginx replaces `X-Real-IP` and
sets `X-Forwarded-Proto` and `Host`; it does not preserve a client-supplied forwarding
chain.

## 6. Container Resource Budget

| Service | Memory limit | Primary internal limit | CPU guidance |
|---|---:|---|---:|
| novel-front | 896 MiB | `-Xms256m -Xmx512m`, G1 GC | 1.50 CPU |
| MySQL | 768 MiB | 384 MiB InnoDB buffer pool | 1.00 CPU |
| Kafka | 640 MiB | 384 MiB JVM heap | 0.75 CPU |
| Redis | 256 MiB | 176 MiB `maxmemory` | 0.35 CPU |
| Prometheus | 384 MiB | 7 days and 1 GiB TSDB retention | 0.40 CPU |
| Grafana | 256 MiB | no automatic plugin installation | 0.30 CPU |
| Nginx | 64 MiB | two workers at most | 0.25 CPU |

The CPU limits are ceilings, not reservations, so their sum may exceed two CPUs. Memory
limits are hard containment boundaries; expected steady use is lower than the sum. The
target is approximately 2.2 to 2.8 GiB steady physical memory, leaving room for Linux,
Docker, page cache, and bursts.

The host uses approximately 4 GiB swap with low swappiness as last-resort OOM protection.
No normal operating state or acceptance test may rely on active swap growth.

## 7. Spring Boot Production Settings

The front container receives JVM options through `JAVA_TOOL_OPTIONS`:

```text
-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxMetaspaceSize=160m
-XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom
```

The container itself is limited to 896 MiB to leave native memory for threads, direct
buffers, class metadata, Kafka clients, and ShardingSphere.

Production server settings are bounded as follows:

- Tomcat maximum threads: 48.
- Tomcat minimum spare threads: 4.
- Tomcat accept queue: 100.
- Tomcat maximum connections: 512.
- Business executor: 2 core threads, 6 maximum threads, queue 100.
- Mail executor remains at two threads and queue 100; its bounded queue and failure
  isolation already satisfy the 2C4G deployment budget.
- Multipart upload limits are reduced from 100 MiB to 10 MiB.
- Hikari maximum pool size is 10 and minimum idle is 2 for the application-facing pool.
- The ShardingSphere physical datasource uses a maximum pool size of 10, minimum idle of
  2, a 5-second connection timeout, and a 3-second validation timeout.

The `prod,monitoring` profiles explicitly configure Kafka at `kafka:19092`, Redis at
`redis:6379`, the rendered ShardingSphere file, SMTP, trusted Nginx address, and the
existing topic names. Development configuration continues to use host ports and is not
changed by production values.

The management server listens on container port 8084 and exposes only `health`,
`prometheus`, and the minimum metric endpoint needed by the existing checks. Health
details are not public. Nginx returns 404 for `/actuator` and `/actuator/*` before proxying
any request.

Spring AI is either supplied a real environment-injected API key or explicitly disabled
for the first deployment. A dummy key is not used in production.

## 8. MySQL Design

The official MySQL image is pinned to a concrete 8.0 patch version. The production
configuration includes:

- `innodb_buffer_pool_size=384M`.
- `max_connections=60`.
- Bounded table and temporary-table caches.
- UTF-8 MB4 server character set and collation.
- Slow-query logging disabled initially to avoid unbounded diagnostic writes; it may be
  enabled temporarily during a controlled investigation.
- Binary logging disabled for this short-lived single-node deployment. Recovery is based
  on scheduled logical backups rather than point-in-time recovery.

The official `MYSQL_DATABASE`, `MYSQL_USER`, and `MYSQL_PASSWORD` initialization path
creates a dedicated application account scoped to `novel_plus`. The application never
uses the root account. `MYSQL_ROOT_PASSWORD` is separate and used only for administration,
initialization, migration, and backup recovery.

The 177 MiB uncompressed seed file is not mounted from an assumed local extraction
directory. The deployment provides an explicit, repeatable initialization step that
extracts the tracked archive or imports the schema-only file. The reading aggregation and
authentication migration scripts are applied in chronological order and verified for
idempotence before the application starts.

## 9. Redis Design

Redis has no published port and requires a strong environment-provided password. The
configuration uses:

- Container memory limit: 256 MiB.
- `maxmemory`: 176 MiB.
- `maxmemory-policy`: `allkeys-lfu`.
- AOF with `appendfsync everysec`.
- Automatic AOF rewrite with a 64 MiB minimum size.

Eviction is acceptable because Redis contains caches, bounded reading gates, captcha
state, and rate-limit state rather than the primary durable book database. Eviction can
temporarily reduce abuse-history continuity, so Redis memory usage and eviction counters
remain monitored. MySQL stays the source of truth.

## 10. Kafka Design

Kafka remains one KRaft process with broker and controller roles and replication factor
one. Only the internal listener `PLAINTEXT://kafka:19092` exists; no host listener or
published port is configured.

The Kafka JVM heap is fixed at 384 MiB. Broker defaults are bounded for this deployment:

- Small segment files to permit timely deletion.
- Short retention for primary click and reading topics because healthy consumers normally
  drain them within seconds.
- Longer but bounded retention for DLT topics to preserve investigation evidence.
- A disk-size ceiling as well as a time ceiling.
- Replication and ISR settings remain one for the single broker.

Topic names, partition counts, producer idempotence, consumer groups, retries, DLT
routing, and database transaction semantics remain unchanged. Development Kafka keeps
its current host listener and is not affected by production broker settings.

## 11. Prometheus and Grafana Design

Prometheus scrapes `novel-front:8084/actuator/prometheus` over the monitoring network.
The scrape and rule-evaluation intervals increase from 15 to 30 seconds. Existing JVM,
HTTP, executor, cache, Kafka, reading, recommendation, and authentication metrics remain
available.

Prometheus retention is bounded by both seven days and approximately 1 GiB. Grafana uses
the existing provisioned datasource and dashboard, requires an environment-provided admin
password, disables anonymous access, and does not install plugins automatically.

Loopback bindings permit these SSH tunnels without exposing either service publicly:

```text
localhost:9090 -> server 127.0.0.1:9090
localhost:3000 -> server 127.0.0.1:3000
```

The production configuration contract must verify both halves of this boundary:
Prometheus and Grafana remain on the internal `monitoring` network for service traffic,
also join `loopback-access` for Docker port publication, and publish only to
`127.0.0.1`. Runtime acceptance verifies actual host listeners rather than relying only
on rendered Compose configuration.

## 12. Nginx Design

Nginx publishes port 80 and reserves a separate HTTPS server block for later certificate
activation. Initial settings remain intentionally small:

- Proxy only to `novel-front:8083`.
- Replace forwarding headers and pass the original host and scheme.
- Reject `/actuator` paths.
- Client request-body limit of 10 MiB.
- Bounded connect, send, and read timeouts.
- Basic request-rate and connection-rate limits for obviously abusive traffic.
- Static asset cache headers and gzip for text assets to reduce limited transfer use.
- No response cache for personalized or authenticated pages.

Before HTTPS exists, port 80 is used only for restricted smoke testing. Once a domain and
certificate are available, HTTP redirects to HTTPS and secure cookies are enabled through
the existing environment-aware application configuration.

## 13. Secrets and Configuration

`.env.prod.example` documents every required variable without providing usable defaults
for secrets. At minimum it includes:

- MySQL root and application credentials.
- Redis password.
- Grafana administrator credentials.
- JWT signing secret.
- Authentication HMAC secret.
- Reading-engagement IP HMAC secret.
- Cache-manager password.
- SMTP account and authorization secret.
- Optional AI, payment, and OSS credentials.
- Public domain and trusted proxy address.

The real `.env.prod`, rendered ShardingSphere YAML, backups, certificates, and private
keys are ignored by Git and stored with restrictive filesystem permissions. Secret values
are not placed in Docker image layers, command-line arguments, health-check output, logs,
Prometheus labels, or documentation.

The existing literal payment, OSS, mail, MySQL, Redis, and Grafana credentials are removed
or replaced with environment references. Any externally valid credential already committed
to Git is rotated before deployment. Git-history rewriting is a separate deliberate action
and is not performed automatically.

## 14. Logging and Disk Budget

Every long-running container uses Docker `json-file` rotation with a 10 MiB file and three
files. Nginx logs to stdout and stderr so it inherits the same rotation. Spring keeps
production logs at INFO/WARN with DEBUG disabled, seven days of file history, 10 MiB per
file, and a total file cap of 256 MiB.

Kafka retention, Prometheus retention, Redis AOF rewrite, and compressed MySQL backup
retention provide independent disk bounds. At least 10 GiB free disk is maintained. A
disk check is part of the operational checklist before load testing or importing a new
dataset.

Public load tests use explicit duration and request caps because the server has limited
transfer. They are not left running unattended.

## 15. Health, Startup, and Failure Behavior

All services have health checks. Dependency ordering uses health status rather than fixed
sleeps:

1. MySQL, Redis, and Kafka start and become healthy.
2. Database initialization and migrations complete successfully.
3. `novel-front` starts and its internal health endpoint becomes healthy.
4. Nginx begins proxying only after `novel-front` is healthy.
5. Prometheus and Grafana start on the monitoring network.

Restart policy is `unless-stopped`. A dependency failure does not cause an uncontrolled
container restart loop: MySQL and Redis failures produce the already-tested safe
application responses; Kafka failures retain the existing asynchronous failure semantics.
Resource-limit OOM exits remain visible through container status and logs.

## 16. Backup, Restore, and Migration

A backup script runs `mysqldump` inside the MySQL container with a credentials file rather
than a password command-line argument. Output is compressed, timestamped, permission
restricted, and retained locally for a small fixed count. At least one current backup is
copied off the server because a same-disk backup does not protect against disk loss.

Redis and Kafka volumes are operational state, not the authoritative migration source.
Migration to another server consists of:

1. Stop public writes through Nginx.
2. Wait for Kafka consumer lag to reach zero.
3. Create and verify a final compressed MySQL dump.
4. Copy the repository revision, `.env.prod`, certificates, optional picture volume, and
   verified dump to the new server over a secure channel.
5. Start infrastructure on the new server and restore MySQL.
6. Start `novel-front`, monitoring, and Nginx; run smoke checks.
7. Change DNS only after the new instance passes acceptance.

## 17. Verification Strategy

Deployment changes are driven by configuration contract tests before Compose files are
implemented. Automated checks verify:

- Only Nginx publishes public ports.
- MySQL, Redis, Kafka, `novel-front`, Actuator, Prometheus, and Grafana are not publicly
  bound.
- Required images use explicit versions.
- Every long-running service has a health check, restart policy, memory limit, and log
  rotation.
- Production secrets have no literal values or insecure fallbacks.
- Production Spring, Kafka, Redis, datasource, proxy, and management settings reference
  the intended container services.
- Prometheus and Kafka retention limits exist.
- The Nginx configuration denies Actuator and supplies trusted proxy headers.
- `docker compose config` succeeds with a generated non-secret fixture environment.

Runtime acceptance on Linux verifies service health, application pages, chapter cache,
both Kafka pipelines, reading aggregation, authentication, Prometheus targets, Grafana
provisioning, container memory, open host sockets, disk usage, backup, and restore. The
existing destructive dependency drills are run only on an isolated deployment and retain
their explicit opt-in switches.

## 18. Planned Files

### Create

- `compose.prod.yml`
- `.env.prod.example`
- `.dockerignore`
- `deploy/nginx/nginx.conf`
- `deploy/nginx/conf.d/novel-front.conf`
- `deploy/mysql/conf.d/novel.cnf`
- `deploy/prometheus/prometheus.yml`
- `deploy/shardingsphere/shardingsphere-jdbc.yml.template`
- `deploy/scripts/render-config.sh`
- `deploy/scripts/init-database.sh`
- `deploy/scripts/backup-mysql.sh`
- `deploy/scripts/restore-mysql.sh`
- `deploy/README.md`
- `performance/test-production-deployment-config.ps1`

### Modify

- `.gitignore`
- `novel-front/src/main/build/docker/Dockerfile`
- `novel-front/src/main/resources/application-prod.yml`
- `novel-front/src/main/resources/application-monitoring.yml`
- `novel-front/src/main/resources/application-alipay.yml`
- `novel-front/src/main/resources/application-oss.yml`
- `novel-front/src/main/resources/logback-boot.xml`
- `novel-common/src/main/resources/application-common-prod.yml`
- `docs/learning/novel-plus-evolution-guide.md`

Business Java code is not modified unless a failing production configuration test proves
that the fixed trusted-proxy or executor boundary cannot be implemented safely through
configuration alone.

## 19. Acceptance Criteria

The first production release is accepted only when all of the following are true:

- A clean Linux host can be deployed from the documented repository revision and a newly
  created `.env.prod`.
- Only SSH, 80, and later 443 are externally reachable.
- No container uses root database credentials for application queries.
- No tracked file contains a usable private credential.
- All containers remain within their memory limits during idle use and bounded smoke load;
  swap is not required for steady operation.
- Kafka, Prometheus, Docker, Spring, Nginx, Redis, and backup storage all have explicit
  retention or size bounds.
- Chapter caching, click counting, reading-time aggregation, MySQL persistence,
  authentication, recommendations, and monitoring pass their production smoke tests.
- MySQL backup and restore are demonstrated with a disposable database or isolated volume.
- The deployment can be moved to another server using only the documented artifacts and
  a verified database dump.
