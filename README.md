# Novel-Plus 高并发与生产化改造实践

本仓库基于 Novel-Plus 5.3.3 进行二次开发，目标不是继续堆叠页面功能，而是把传统小说 CMS 改造成一个可压测、可观测、可安全部署的后端工程项目。

改造覆盖章节缓存、Kafka 异步统计、阅读行为采集、动态推荐、账户安全、线程池治理、监控告警和 2C4G 云服务器生产部署。仓库按实际开发顺序保留设计、测试、实现、故障演练与上线修复记录，便于复盘每项改造解决了什么问题。

## 项目目标

- 降低热门章节查询对 MySQL 的直接压力。
- 将点击统计、阅读聚合等非核心逻辑从 HTTP 请求链路中解耦。
- 在不记录正文和原始 IP 的前提下采集有效阅读时长。
- 用真实阅读数据驱动首页推荐。
- 将历史 MD5 密码平滑迁移到 Argon2id。
- 为缓存、消息队列、线程池和认证链路建立可观测性。
- 在 2 vCPU、4 GiB 内存的云服务器上完成最小公网暴露面的容器化部署。

## 整体架构

```text
Internet
   │
   ▼
Nginx :80/:443
   │
   ▼
novel-front (Spring Boot)
   │
   ├── Redis
   │    ├── 章节缓存与防击穿锁
   │    ├── 阅读心跳原子校验
   │    └── 验证码、登录限制与 JWT 撤销
   │
   ├── Kafka
   │    ├── 小说点击事件
   │    └── 阅读时长事件
   │
   └── MySQL
        ├── 核心业务数据
        ├── 阅读事件幂等记录
        └── 每日阅读聚合

Prometheus ──采集──► Actuator / 业务指标
Grafana    ──展示──► JVM、HTTP、缓存、Kafka、线程池与认证指标
```

生产环境只允许 Nginx 暴露公网端口。Spring Boot、MySQL、Redis、Kafka、Prometheus、Grafana 和 Actuator 均限制在 Docker 内网或宿主机回环地址。

## 核心改造

### 1. 小说章节缓存与缓存击穿治理

- 使用 Cache-Aside 模式缓存章节正文，命中时直接返回 Redis 数据。
- 缓存未命中时使用 Redis `SET NX` 锁，避免热点章节并发回源 MySQL。
- 抢锁成功后进行二次读取，防止等待期间其他请求已经完成回填。
- 锁值使用随机 token，解锁时通过 Lua 原子比较并删除，避免误删其他线程持有的新锁。
- TTL 加入随机抖动，降低大量章节缓存同时失效造成的瞬时压力。
- Redis 写入 OOM、序列化异常或缓存不可用时降级返回数据库结果，不让缓存故障阻断阅读。
- 增加命中、未命中、回源、锁竞争、写入成功和写入失败等 Micrometer 指标。

### 2. Kafka 异步点击统计

- 将访问量累加从同步数据库更新改为 Kafka 事件投递，缩短请求关键路径。
- Producer 记录发送成功与失败，Consumer 批量消费并更新 MySQL。
- 配置固定 Topic、消费组、重试和死信队列，保留失败证据。
- 提供并发验收脚本，验证 HTTP 接收数、Producer 成功数、数据库增量和 Consumer Lag 一致。

### 3. 隐私友好的阅读时长采集

- 匿名用户通过随机 `reader_session` Cookie 区分，不使用 IP 直接充当用户身份。
- 原始 IP 只用于风险控制，经过可信代理解析和 HMAC 哈希后使用，不写入业务事件。
- 阅读页面签发短期 page-visit token，只有可阅读章节才能产生有效心跳。
- 浏览器仅在页面可见且获得焦点时累计时间；每完成一个 10 秒活跃区间才发送一次心跳。
- 页面隐藏、失焦或提前离开时丢弃不足 10 秒的片段，避免虚增阅读时长。
- Snowflake ID 在浏览器端始终保持字符串，避免 JavaScript 浮点数精度丢失。
- Redis Lua 脚本原子完成 token 校验、序列去重、会话限流、IP 限流和每日上限判断。
- 接受的事件写入 Kafka，消费者按 `bookId + naturalDay` 聚合后批量更新 MySQL。
- 通过 `eventId` 幂等表防止 Kafka 重放导致重复计费，并定期清理过期幂等记录。
- 消费失败支持有限重试和 DLT，MySQL、Redis、Kafka 故障均有独立演练脚本。

### 4. 阅读数据驱动的首页推荐

- “本周强推”统计包含当天在内的最近 7 天有效阅读时长。
- “热门推荐”使用最近 15 天阅读时长，拉长观察窗口以降低短期波动。
- 阅读时长优先排序，累计点击量作为同分条件，不改变新书榜、点击榜等独立榜单语义。
- 推荐结果生成 Redis 快照，首页读取快照而不是每次执行聚合 SQL。
- 刷新失败时保留上一个可用快照，避免推荐任务故障拖垮首页。
- 精品推荐继续保留人工配置，为未来评分体系预留空间。

### 5. 用户认证安全升级

- 新注册密码使用带随机 salt 的 Argon2id，不再创建新的 MD5 密码。
- 历史用户登录成功后使用原始输入密码生成 Argon2id，并进行无感知懒迁移。
- 迁移失败不影响当次正确登录，且任何路径都不保存或记录明文密码。
- 密码算法通过独立服务分派，为后续接入 PBKDF2、SCrypt 等算法预留扩展点。
- 注册、重置密码和修改邮箱验证码使用不同 Redis Key，避免跨用途复用。
- 验证码有效期 10 分钟，成功消费后立即删除，并限制邮箱和 IP 的发送频率。
- 登录失败按账号维度限制；IP 维度采用延迟、验证码和限流，避免直接封禁共享网络。
- 登录成功清除失败记录，退出登录后将 JWT 加入 Redis 撤销集合。
- SMTP、JWT、数据库和 Redis 密钥全部通过环境变量注入，不提交生产凭据。

### 6. 有界线程池与过载保护

- 将前台异步任务放入显式有界线程池，避免默认线程无限增长。
- 根据 2 核 CPU 调整核心线程数、最大线程数和队列容量。
- 监控拒绝次数、活跃线程、线程池大小、已完成任务和队列剩余容量。
- 线程池饱和时保留明确的拒绝行为，避免静默堆积最终触发 OOM。

### 7. Prometheus 与 Grafana 可观测性

- 接入 Spring Boot Actuator、Micrometer、Prometheus 和 Grafana。
- 覆盖 HTTP 吞吐、P95/P99、5xx、章节缓存、Kafka、阅读聚合、线程池和认证安全指标。
- 配置告警规则检查缓存异常、Kafka 发送失败、消费重试、DLT、线程池拒绝和认证风险。
- 提供自动化契约测试，验证 Prometheus 配置、告警规则、Grafana 数据源和 Dashboard。
- 监控端口仅绑定 `127.0.0.1`，不直接暴露公网。

### 8. 2C4G 生产部署

- 使用多阶段 Dockerfile 构建 `novel-front`，运行阶段采用非 root 用户。
- 通过 `compose.prod.yml` 隔离开发与生产配置，敏感值从 `.env.prod` 注入。
- MySQL、Redis、Kafka 使用独立 Docker 内网，不发布宿主机端口。
- 为 Spring Boot、MySQL、Redis、Kafka、Prometheus、Grafana 和 Nginx 设置内存上限。
- 限制 Kafka retention、Prometheus TSDB 保留量以及 Docker、Spring Boot、Nginx 日志大小。
- 配置健康检查、自动重启、数据库迁移、Kafka Topic 初始化、备份恢复和运行时巡检脚本。
- 公网只开放 SSH、HTTP 和预留的 HTTPS 端口，Actuator 由 Nginx 明确拒绝。

## 性能与验证结果

### 章节读取异步基线

| 并发线程 | 吞吐量 | 平均响应 | P95 | P99 | 错误率 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 115.8 req/s | 8 ms | 11 ms | 15 ms | 0% |
| 10 | 588.2 req/s | 14 ms | 20 ms | 23 ms | 0% |
| 30 | 896.0 req/s | 29 ms | 40 ms | 47 ms | 0% |

该结果用于记录本地测试环境下的行为，不代表公网服务器容量承诺。

### 自动化与线上验收

- Java 全量回归：328 个测试，0 failure，0 error。
- JavaScript 阅读心跳测试：10 个测试全部通过。
- 1000 次并发点击请求全部进入 Kafka，MySQL 最终增量为 1000，Consumer Lag 回到 0。
- 线上阅读 30 秒产生 3 个有效心跳，MySQL 聚合为 `30 seconds / 3 heartbeats`。
- Redis、Kafka、MySQL 和 SMTP 故障演练验证了对应的降级、重试或失败安全策略。
- Prometheus、Grafana、告警规则、生产网络边界和容器资源限制均有自动化巡检。

## 项目演进

```text
章节性能基线
  → Redis 章节缓存与防击穿
  → Kafka 异步点击统计
  → 缓存与消息链路可观测性
  → 阅读时长采集与每日聚合
  → 阅读数据驱动推荐
  → Argon2id 与认证安全体系
  → 2C4G 生产部署
  → Snowflake ID 精度修复
  → 10 秒阅读心跳优化
```

仓库的 Git 提交按上述顺序保留，可以从设计文档、失败测试、最小实现一直查看到运行验收和生产修复。

## 文档入口

- [项目演进与学习指南](docs/learning/novel-plus-evolution-guide.md)
- [性能测试与验收脚本](performance/README.md)
- [生产部署与运维手册](deploy/README.md)
- [阅读行为采集设计](docs/superpowers/specs/2026-09-10-reading-engagement-design.md)
- [可观测性设计](docs/superpowers/specs/2026-09-09-observability-design.md)
- [香港轻量云生产部署计划](docs/superpowers/plans/2026-09-22-hong-kong-production-deployment.md)

## 技术栈

Java 21、Spring Boot 3.4、MyBatis、MySQL 8、Redis 7、Kafka、Thymeleaf、Docker Compose、Nginx、Prometheus、Grafana、JUnit 5、PowerShell。

## 来源与许可证

本项目的初始代码基于 [201206030/novel-plus](https://github.com/201206030/novel-plus) 的 `d9f3f46` 版本进行学习和二次开发。仓库保留原项目的 Apache License 2.0；后续高并发、可靠性、安全、可观测性和生产部署改造以独立提交记录在本仓库中。
