# Novel-Plus 本地可观测性设计

## 1. 背景与目标

章节读取链路已经完成 Redis 缓存防护、异步任务收敛、相邻章节 SQL 合并、Kafka 访问量聚合，以及缓存和 Kafka 自定义指标埋点。下一阶段需要把这些零散指标变成一套可以启动、观察、压测和验证的本地监控环境。

本阶段采用方案 A：Java 应用继续运行在 Windows 主机，Prometheus 和 Grafana 运行在 Docker 中。Prometheus 通过 `host.docker.internal:8084` 抓取 Spring Boot Actuator 的 Prometheus 端点。

目标如下：

- 使用一条明确的本地启动流程拉起 Prometheus 和 Grafana。
- 自动配置 Prometheus 数据源和 Novel-Plus 总览 Dashboard，不依赖手工点击配置。
- 展示应用、章节缓存、Kafka、JVM 和数据库连接池的核心指标。
- 提供本地告警规则，能够发现应用不可用、缓存异常、Kafka 发送失败和消费积压等问题。
- 提供可重复的验证流程，证明采集、展示和告警链路真实有效。

## 2. 本阶段边界

### 2.1 包含内容

- Prometheus 抓取 Spring Boot Actuator 指标。
- Grafana 自动配置数据源和 Dashboard。
- Prometheus 本地告警规则。
- 仅在显式启用时开放给 Docker 抓取的 Spring `monitoring` Profile。
- 配套启动说明、验证脚本和学习文档更新。

### 2.2 不包含内容

- 不在本阶段把 Java 应用容器化，也不做云服务器部署。
- 不引入 Alertmanager、邮件、短信或企业微信通知；告警只在 Prometheus/Grafana 页面可见。
- 不引入 Loki、ELK 或分布式链路追踪。
- 不部署 Redis、MySQL 专用 Exporter；第一版使用应用自身、JVM、Kafka 客户端和 HikariCP 已暴露的指标。
- 不修改生产环境的 Actuator 暴露范围或网络绑定。

## 3. 总体架构

```text
Windows 主机
┌────────────────────────────────────────────┐
│ novel-front                                │
│ 业务端口 8083                              │
│ 管理端口 8084（monitoring Profile 下 0.0.0.0）│
└──────────────────────┬─────────────────────┘
                       │ /actuator/prometheus
                       │ host.docker.internal:8084
Docker                 ▼
┌────────────────┐    查询    ┌────────────────┐
│ Prometheus     │◄───────────│ Grafana        │
│ 127.0.0.1:9090 │            │ 127.0.0.1:3000 │
│ 抓取 + 告警计算 │            │ Dashboard      │
└────────────────┘            └────────────────┘
```

Prometheus 和 Grafana 端口只绑定到主机回环地址，不暴露到局域网。应用管理端口只有在显式启用 `monitoring` Profile 时才绑定 `0.0.0.0`，用于 Docker 到 Windows 主机的访问。该 Profile 是本地开发能力，不得用于公网生产部署。

## 4. 配置与文件布局

计划增加或修改以下文件：

```text
compose.local.yml
monitoring/
├── prometheus/
│   ├── prometheus.yml
│   └── rules/novel-plus-alerts.yml
└── grafana/
    ├── dashboards/novel-plus-overview.json
    └── provisioning/
        ├── dashboards/dashboards.yml
        └── datasources/prometheus.yml
novel-front/src/main/resources/application-monitoring.yml
performance/start-front-monitoring.ps1
performance/check-observability.ps1
performance/README.md
docs/learning/novel-plus-evolution-guide.md
```

`application-monitoring.yml` 覆盖 `management.server.address` 为 `0.0.0.0`，并仅在该 Profile 中为 `http.server.requests` 开启百分位直方图。现有 `application-dev.yml` 仍保持 `127.0.0.1`，生产配置不受影响。启动脚本显式组合现有开发 Profile 与 `monitoring` Profile，避免开发者忘记必要参数。

`compose.local.yml` 增加 Prometheus 和 Grafana 服务，并挂载仓库内配置。两者使用命名卷保存时间序列和 Dashboard 运行数据。Grafana 本地默认账号为 `admin`，密码从 `GRAFANA_ADMIN_PASSWORD` 环境变量读取；未提供时使用仅供本地开发的默认值 `admin`，文档中明确提示修改。

## 5. Prometheus 采集设计

Prometheus 新增 `novel-front` 抓取任务：

- 抓取目标：`host.docker.internal:8084`
- 指标路径：`/actuator/prometheus`
- 建议抓取周期：15 秒
- 建议规则计算周期：15 秒
- 应用标签沿用 Micrometer 的 `application="novel-front"`

容器不依赖 Java 应用先启动。应用未启动时 Prometheus 保持运行，并将 `up{job="novel-front"}` 标记为 `0`，使“应用不可用”本身也可观察和告警。

## 6. Dashboard 设计

Dashboard 名称为 `Novel-Plus Overview`，默认时间范围为最近 15 分钟，自动刷新周期为 10 秒。面板按下列区域组织。

### 6.1 应用状态

- Prometheus 抓取状态：`up{job="novel-front"}`。
- HTTP 请求速率、平均耗时、P95/P99 和 5xx 比例：使用 Spring Boot 的 `http_server_requests_seconds_*` 指标。
- `monitoring` Profile 为 `http.server.requests` 开启百分位直方图，P95/P99 使用 `histogram_quantile` 计算；若桶指标不存在，面板应明确显示“无数据”，不用平均值冒充分位数。

### 6.2 章节缓存

- 请求级缓存命中率：

```promql
sum(rate(novel_chapter_cache_lookup_total{result="hit"}[5m]))
/
clamp_min(sum(rate(novel_chapter_cache_lookup_total[5m])), 1e-9)
```

- 命中、未命中、读取异常速率：`novel_chapter_cache_lookup_total` 按 `result` 分组。
- Redis 写入成功与失败速率：`novel_chapter_cache_write_total`。
- 分布式锁获取、竞争与异常速率：`novel_chapter_cache_lock_total`。
- MySQL Loader 调用次数和平均耗时：`novel_chapter_cache_load_seconds_count/sum`。

这里展示的是完整查询请求的结果，而不是只展示 Redis GET 次数，因此能够直接回答“用户请求有多少命中了缓存”。

### 6.3 Kafka 访问量聚合

- 生产成功/失败速率：`novel_book_visit_kafka_send_total`。
- 消费消息速率：`novel_book_visit_kafka_consumed_total`。
- 数据库更新速率：`novel_book_visit_kafka_db_updates_total`。
- 每批消息数和每批聚合书籍数：`novel_book_visit_kafka_batch_size_*`、`novel_book_visit_kafka_aggregated_books_*`。
- Topic 分区消费滞后：汇总 `kafka_consumer_fetch_manager_records_lag{topic="novel-book-visit-v1"}`。

使用每个分区的原始 `records_lag` 汇总，不依赖空闲时可能为 `NaN` 的客户端平均值。

### 6.4 JVM 与连接池

- JVM Heap 已用量及使用率：`jvm_memory_used_bytes` 与 `jvm_memory_max_bytes`。
- GC 暂停次数与耗时：`jvm_gc_pause_seconds_*`。
- 活跃线程数：`jvm_threads_live_threads`。
- HikariCP 活跃、空闲、最大、等待连接数：`hikaricp_connections_*`。
- 连接获取耗时：`hikaricp_connections_acquire_seconds_*`。

## 7. 告警规则

第一版告警以本地学习和演示为目的，阈值强调可解释、可复现，后续上线前必须结合真实基线重新调整。

| 告警 | 条件 | 持续时间 | 意义 |
|---|---|---:|---|
| `NovelFrontDown` | `up{job="novel-front"} == 0` | 1 分钟 | 应用未启动或指标端点无法访问 |
| `ChapterCacheErrors` | 缓存 lookup/write/lock 任一 error 在 5 分钟内增加 | 立即 | Redis 或缓存处理出现降级/失败 |
| `LowChapterCacheHitRate` | 5 分钟请求数至少 100 且命中率低于 90% | 2 分钟 | 避免空闲期和少量冷启动造成误报 |
| `KafkaPublishFailures` | Kafka send failed 在 5 分钟内增加 | 立即 | 生产端发送失败，可能触发业务降级 |
| `KafkaConsumerLagHigh` | 指定 Topic 总 Lag 大于 10000 | 2 分钟 | 消费速度持续落后于生产速度 |
| `HikariPendingConnections` | 等待数据库连接数大于 0 | 1 分钟 | 连接池开始成为请求瓶颈 |
| `JvmHeapUsageHigh` | Heap 使用率高于 85% | 5 分钟 | 存在内存压力或泄漏风险 |

缓存错误规则覆盖三类带 `result="error"` 的计数器。低命中率规则必须同时满足最小流量门槛，避免冷启动的一次 miss 直接告警。

## 8. 启动流程

推荐流程如下：

1. 使用现有 `compose.local.yml` 启动 MySQL、Redis、Kafka、Prometheus 和 Grafana。
2. 使用 `performance/start-front-monitoring.ps1` 启动 Windows 主机上的 `novel-front`，脚本显式激活开发环境和 `monitoring` Profile。
3. 打开 Prometheus Targets 页面确认 `novel-front` 为 `UP`。
4. 打开 Grafana，确认 Prometheus 数据源和 `Novel-Plus Overview` Dashboard 已自动加载。
5. 使用现有压测脚本产生章节和 Kafka 流量，再观察面板。

启动脚本只负责构造可复现的本地启动参数，不写入或改动用户已有的 dev/prod 配置文件。

## 9. 自动验证设计

`performance/check-observability.ps1` 提供只读验证，失败时返回非零退出码。它至少检查：

- `http://127.0.0.1:8084/actuator/health` 为 `UP`。
- Prometheus `/-/ready` 可访问。
- Prometheus Targets API 中 `novel-front` 为 `UP`。
- 关键指标 `novel_chapter_cache_lookup_total` 和 `novel_book_visit_kafka_send_total` 能通过 Prometheus 查询到。
- Grafana 健康检查成功，且已配置 Prometheus 数据源和总览 Dashboard。
- Prometheus 已载入预期告警规则。

人工验收场景：

1. 对一个未缓存章节连续请求 100 次，预期出现 1 次 miss、约 99 次 hit、1 次 DB load，且无缓存 error。
2. 产生书籍访问事件，确认 Kafka send、consume、DB update 和 Lag 面板发生变化。
3. 临时停止应用超过 1 分钟，确认 `NovelFrontDown` 进入 firing；重新启动后恢复。
4. 使用全新 Prometheus/Grafana 命名卷启动，确认数据源、Dashboard 和规则均无需手工配置。

## 10. 测试与交付标准

实现完成前必须满足：

- `docker compose -f compose.local.yml config` 校验通过。
- Prometheus 配置和规则文件通过 `promtool check config`、`promtool check rules`。
- Grafana 能自动加载数据源与 Dashboard，Dashboard JSON 格式有效。
- 自动验证脚本全部通过。
- 原有 Maven 测试保持通过，不能破坏已完成的 46 个测试。
- 三份用户已有的环境配置修改不进入本阶段提交。
- 学习文档补充启动、观察、压测和故障演练步骤，能够解释每个面板对应的业务问题。

## 11. 风险与后续演进

- `management.server.address=0.0.0.0` 会扩大监听范围，因此只能由 `monitoring` Profile 显式开启；Prometheus 和 Grafana 的宿主机端口仍只绑定 `127.0.0.1`。
- 本地默认 Grafana 密码只适用于回环地址开发环境，部署时必须改为外部密钥并增加访问控制。
- 本地阈值不能直接当成生产阈值。上线后应依据流量、容量和错误预算重新校准。
- 第一版没有 Redis/MySQL Exporter，只能从应用侧判断依赖影响。后续部署阶段可增加 Redis 内存、淘汰、命中率、MySQL 慢查询和连接指标。
