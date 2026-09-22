# novel-plus 二次开发学习与演进档案

> 用途：记录本项目每一次改进的原因、代码位置、验证方法和实测指标。以后继续学习或换对话时，先读本文，再读对应代码。每完成一个阶段，都要更新本文。

## 1. 已确定的项目路线

我们选择的是“方案 A”：暂时不拆微服务，以现有 Spring Boot 单体项目为基础进行实战级后端改造。

总体顺序：

1. 把项目、Docker、MySQL、Redis、Git 和 JMeter 跑通。
2. 对章节阅读核心链路建立可靠压测基线。
3. 使用 Redis 和代码结构优化解决热点章节、高并发失败问题。
4. 再使用 Kafka 处理适合异步化的写操作，而不是为了使用 Kafka 强行引入。
5. 建立 Prometheus + Grafana 可观测闭环，用指标证明优化并定位故障。
6. 完善搜索和推荐；先做可解释的规则/行为推荐，再考虑 AI 增强。
7. 后端优先，逐步形成个人特色；最后学习 Linux、Docker 和云服务器部署。

当前已完成章节缓存、任务收敛、相邻章节 SQL 合并、Kafka 点击量聚合、Micrometer
指标埋点和线程池容量闭环。搜索、推荐、AI 和服务器部署尚未开始。

## 2. 工作区与 Git 状态

- 主项目：`D:\offer\novel-plus`
- 性能改进 worktree：`D:\offer\novel-plus\.worktrees\chapter-performance`
- 当前分支：`feature/chapter-performance`
- 本轮起点：`c816f4b`
- 当前 worktree 已包含章节性能和 Kafka 聚合改造，尚未合并回主开发分支。

已提交的阶段记录：

| Commit | 含义 |
|---|---|
| `dece9eb` | 建立章节阅读原始压测基线 |
| `05d13da` | 新增带击穿保护的章节缓存 |
| `fb5bea4` | 把数据库章节读取接入缓存，并补写后失效 |
| `a1aa4ae` | 明确 Spring 应选择的缓存构造器 |
| `c816f4b` | 整理章节缓存压测结论和操作说明 |
| `53d4d0e` | 暴露章节缓存指标，供 Actuator/Prometheus 采集 |

学习 Git 时，先掌握：`status` 看状态、`diff` 看改动、`add` 选择暂存内容、`commit` 保存一个可回退节点、`log` 看历史、`branch/worktree` 隔离功能开发。不要把 Git 理解成单纯上传 GitHub，它首先是本地版本管理工具。

## 3. 本地基础设施与配置来源

Docker 不是虚拟机镜像的同义词。镜像是只读模板，容器是镜像启动后的运行实例，volume 保存容器删除后仍需保留的数据。

主目录 `D:\offer\novel-plus\compose.local.yml` 定义：

| 服务 | 容器名 | 宿主机端口 | 容器端口 | 密码 | 数据卷 |
|---|---|---:|---:|---|---|
| MySQL 8 | `novel-mysql` | 3307 | 3306 | `123456` | `novel-mysql-data` |
| Redis 7 | `novel-redis` | 6380 | 6379 | `123456` | `novel-redis-data` |
| Kafka 4.3.1 | `novel-kafka` | 9092 | 9092 | 无 | `novel-kafka-data` |
| Prometheus | `novel-prometheus` | 9090 | 9090 | 无 | `novel-prometheus-data` |
| Grafana | `novel-grafana` | 3000 | 3000 | 本地环境变量 | `novel-grafana-data` |

数据库初始化文件：`D:\offer\novel-plus\doc\sql\novel_plus_data.sql`。

需要特别注意配置优先级：

- Docker端口和密码：`D:\offer\novel-plus\compose.local.yml`
- 实际MySQL连接：`D:\offer\novel-plus\config\shardingsphere-jdbc.yml`，当前为 `localhost:3307`、密码 `123456`
- 仓库默认Redis开发配置：`novel-common/src/main/resources/application-common-dev.yml`
- 性能测试启动时通过 JVM 参数覆盖Redis端口和密码：`-Dspring.data.redis.port=6380 -Dspring.data.redis.password=123456`
- `performance/start-front-monitoring.ps1` 根据自身位置找到 worktree，再通过 Git common directory
  定位共享主仓库中的外部 `config`；这样不会误读 worktree 内尚未同步的数据库连接配置。

常用Docker命令：

```powershell
docker compose -f 'D:\offer\novel-plus\compose.local.yml' up -d
docker compose -f 'D:\offer\novel-plus\compose.local.yml' ps
docker logs novel-mysql
docker logs novel-redis
docker stop novel-mysql novel-redis
docker start novel-mysql novel-redis
```

## 4. 章节阅读接口原始流程

入口位于：

- `novel-front/src/main/java/com/java2nb/novel/controller/page/PageController.java`
- 方法：`bookContent(Long bookId, Long bookIndexId, Model model, HttpServletRequest request)`

Tomcat线程负责接收HTTP请求并调用Controller。原代码又把书籍、章节目录、上一章、下一章、正文和购买状态拆成多个 `CompletableFuture`，放进自定义后台线程池，最后仍然调用 `get()` 等待结果后才能返回页面。

因此它不是“请求发出去立刻返回”的纯异步接口，而是“Tomcat线程等待后台任务”的并行聚合。后台任务过多时，自定义线程池先被冲满，Tomcat线程则一直等不到结果。

线程池定义：

- `novel-front/src/main/java/com/java2nb/novel/core/config/ThreadPoolConfig.java`
- `novel-front/src/main/resources/application-dev.yml`
- 开发配置：核心线程 10、最大线程 20、队列 100、拒绝策略 `AbortPolicy`

原接口一次请求会提交6个后台任务。50个并发请求可能快速制造约300个任务，而线程池最多同时执行20个、等待100个，其余任务被拒绝。

## 5. 第一阶段：建立可信压测基线

相关文件：

- `performance/jmeter/chapter-baseline.jmx`
- `performance/run-chapter-stages.ps1`
- `performance/README.md`
- `performance/results/chapter-performance-summary.md`

关键修正：不能只判断HTTP状态码。原项目发生异常时可能返回HTTP 200的自定义404页，因此JMeter同时断言响应中必须有真实章节标记 `id="contentIdHidden"`。

核心指标：

- Threads：同时循环请求的虚拟用户数，不等于QPS。
- Throughput：每秒完成请求数。
- P99：99%的请求不超过这个耗时。
- Error %：HTTP错误、超时或断言失败比例。
- 容量拐点：继续加线程后吞吐不再增长，但P99或错误率迅速上升。

原始版本关键结果：

| 并发 | 成功吞吐量 | P99 | 错误率 |
|---:|---:|---:|---:|
| 30 | 719.15 req/s | 59 ms | 0% |
| 50 | 215.16 req/s | 88 ms | 93.49% |

结论：原版本最后稳定点约为30并发；50并发时显示的高总QPS大部分是快速返回的错误页，不是真实业务能力。

## 6. 第二阶段：热点章节Redis缓存

核心文件和方法：

| 位置 | 作用 |
|---|---|
| `novel-front/src/main/java/com/java2nb/novel/service/cache/ChapterContentCache.java` | 章节缓存完整实现 |
| `ChapterContentCache#getOrLoad` | 查缓存；未命中时回源数据库 |
| `ChapterContentCache#evict` | 删除指定章节缓存 |
| `ChapterContentCache#evictAfterCommit` | 数据库事务提交成功后再删缓存 |
| `novel-front/src/main/java/com/java2nb/novel/service/impl/DbBookContentServiceImpl.java#queryBookContent` | 数据库正文读取接入缓存 |
| `BookServiceImpl` 约第700、878行 | 章节写入后安排缓存失效 |
| `ChapterContentCacheTest` | 缓存、锁、空值、事务和Redis故障测试 |
| `DbBookContentServiceImplTest` | 正文服务接入缓存测试 |

缓存键：`novel:chapter:v1:{bookId}:{bookIndexId}`。

这里包含的知识点：

1. Cache Aside：先查缓存，未命中再查数据库并回填。
2. TTL随机抖动：正常正文约1800–2100秒，避免大量Key同一时刻过期。
3. 空值缓存：不存在的章节短时间缓存 `__NULL__`，避免重复穿透数据库。
4. 热点保护：Redis `SET NX` 锁让大量并发只产生一个数据库加载者。
5. 安全解锁：锁值使用唯一token，Lua脚本只删除属于自己的锁。
6. Redis故障降级：缓存不可用时有限时间内回源数据库，不能无限重试。
7. 写后失效：数据库事务成功后删除缓存；回滚时不删，避免产生错误状态。

实测：固定热点章节在一次冷加载后由Redis提供正文；100线程冷Key测试中，MySQL正文物理查询只有1次。

缓存后关键结果：

| 并发 | 成功吞吐量 | P99 | 错误率 |
|---:|---:|---:|---:|
| 30 | 938.20 req/s | 42 ms | 0% |
| 50 | 218.96 req/s | 74 ms | 93.61% |

结论：缓存显著减少正文SQL，但50并发仍失败，证明瓶颈已经从正文数据库读取转移到每个请求创建过多后台任务。

## 7. 第三阶段：一次请求从6个任务缩减到3个任务

改动位置：`PageController#bookContent`，约第230–280行。

新结构：

1. 任务1查询书籍信息。
2. 任务2查询当前章节目录。
3. 当前章节目录完成后，任务3顺序完成上下章、正文和购买判断，返回 `ChapterPageData`。

这里没有把接口改成“纯异步”，也没有无限增加线程。它是在一个后台任务里完成原来拆成四个小任务的后续步骤，用少量串行时间换取更低的线程调度、排队和拒绝风险。

对应测试：`novel-front/src/test/java/com/java2nb/novel/controller/page/PageControllerChapterTaskTest.java`。测试使用计数执行器确认一次页面请求只提交3次。

任务缩减后结果：

| 并发 | 成功吞吐量 | P99 | 错误率 |
|---:|---:|---:|---:|
| 30 | 540.51 req/s | 74 ms | 0% |
| 50 | 530.73 req/s | 131 ms | 0% |

结论：稳定性问题解决了，50并发错误率从93%以上降为0；但任务3中的数据库调用串行执行，吞吐和延迟仍有优化空间。这也验证了“串行可能变慢”的担忧是正确的。

## 8. 第四阶段：上下章两次SQL合并成一次SQL

这部分已提交为独立 Git 检查点，可与 Kafka 改动分开查看。

| 位置 | 作用 |
|---|---|
| `PageController#bookContent` 约第246行 | 聚合任务调用新的导航查询 |
| `novel-front/src/main/java/com/java2nb/novel/mapper/FrontBookIndexMapper.java#queryNavigation` | 一条SQL查询上一章和下一章 |
| `novel-front/src/main/java/com/java2nb/novel/vo/BookIndexNavigationVO.java` | 保存两个章节ID |
| `BookService#queryBookIndexNavigation` | Service接口 |
| `BookServiceImpl#queryBookIndexNavigation` 约第313行 | 调用Mapper |
| `FrontBookIndexMapperTest` | 验证章节间断、首章、末章和不同小说隔离 |
| `PageControllerChapterTaskTest` | 验证页面结果和任务数量 |

“一条SQL”表示只进行一次应用到数据库的网络往返；SQL内部仍执行两个索引子查询。MySQL `EXPLAIN` 已确认两个子查询都使用联合索引 `(book_id, index_num)`，没有全表扫描。

最新结果：

| 并发 | 合并前吞吐 | 合并后吞吐 | 合并前P99 | 合并后P99 | 错误率 |
|---:|---:|---:|---:|---:|---:|
| 30 | 540.51 | 687.73 req/s | 74 ms | 48 ms | 0% |
| 50 | 530.73 | 664.91 req/s | 131 ms | 84 ms | 0% |

相对3任务初版：30并发吞吐提升27.24%，50并发提升25.28%；两档P99分别下降约35%。

相对最初6任务版本：50并发成功吞吐从218.96升至664.91 req/s，错误率从93.61%降为0。但新版本30并发吞吐仍低于缓存后6任务版本的938.20 req/s，因此不能宣称所有并发档位都更快；它目前是稳定性和速度之间更可靠的平衡。

原始报告（本地、被Git忽略）：

- `performance/results/raw/fanout-3/`
- `performance/results/raw/navigation-1sql/threads-30/report/index.html`
- `performance/results/raw/navigation-1sql/threads-50/report/index.html`

## 9. 当前测试与审查结论

最终Maven测试：

- `novel-common`：2个测试通过。
- `novel-front`：34个测试通过（加上novel-common共36个）。
- 失败0、错误0。
- `git diff --check` 无格式错误。
- 代码审查发现Kafka失败逐条打印WARN会产生日志风暴；已按TDD改为首条及每1000条告警一次，指标仍逐条计数。
- 次要测试建议：后续补VIP章节的匿名、已购买、未购买场景，并明确断言新导航方法只调用一次、旧上下章方法不再调用。

## 10. 如何自己重新验证

1. 启动Docker数据库：

```powershell
docker compose -f 'D:\offer\novel-plus\compose.local.yml' up -d
```

2. 在性能worktree启动前端服务，并使用主目录外部配置：

```powershell
cd 'D:\offer\novel-plus\.worktrees\chapter-performance'
mvn -pl novel-front -DskipTests '-Dspring-boot.run.jvmArguments=-XX:TieredStopAtLevel=1 -Duser.dir=D:\offer\novel-plus -Dspring.data.redis.port=6380 -Dspring.data.redis.password=123456' spring-boot:run
```

3. 健康检查：`http://127.0.0.1:8084/actuator/health` 应显示 `UP`。

4. 使用相同书籍和章节运行JMeter：

```powershell
& '.\performance\run-chapter-stages.ps1' `
  -BookId 2055879962859147264 `
  -BookIndexId 2055884263706857472 `
  -Label my-check `
  -DurationSeconds 60
```

5. 比较成功吞吐、P99和错误率。不要只看JMeter显示的总吞吐。

## 11. 第五阶段：Kafka异步聚合小说点击量

已确认下一阶段让Kafka处理 `POST /book/addVisitCount`：请求只发送点击事件，消费者批量拉取并按 `bookId` 求和，再执行 `visit_count = visit_count + delta`。目标是把热门小说的大量同步更新变成少量聚合SQL。

已确认的边界：

- 点击量允许1–3秒延迟，以及极端宕机场景下少量重复。
- Kafka不可用时不把流量同步打回MySQL，不能让非关键计数拖垮阅读功能。
- MySQL不可用时不提交消费进度，恢复后继续处理积压。
- 消费者批量聚合；健康状态下发送成功的消息最终应全部计入。
- 使用JMeter比较接口P99、错误率和MySQL实际UPDATE数量，SQL数量至少减少90%。
- 第一版不增加Redis计数层，不增加精确去重表，不处理订单类强一致业务。

正式设计：`docs/superpowers/specs/2026-09-02-kafka-book-visit-design.md`。

上面的边界已经实现并完成真实 Docker 故障测试和 JMeter 对比，不再是待办方案。

### 11.1 用最短的话理解 Kafka

- Broker：Kafka 服务本身，本地就是 novel-kafka 容器。
- Topic：消息队列的名字，本项目是 novel-book-visit-v1。
- Partition：Topic 内的有序分片。同一 bookId 用作 key，所以同一本书进入同一分区。
- Offset：消息在分区中的位置，消费者提交 offset 表示此前消息已处理。
- Producer：BookVisitEventPublisher，把一次点击发送到 Kafka。
- Consumer group：novel-book-visit-writer-v1；同组消费者分工处理分区。
- Lag：Topic 最新 offset 减去已提交 offset，即尚未完成的消息数。
- DLT：死信 Topic。格式非法且重试无意义的消息进入 novel-book-visit-dlt。

完整数据流：

    浏览器 POST /book/addVisitCount
      -> BookController
      -> BookVisitEventPublisher
      -> novel-book-visit-v1
      -> BookVisitEventConsumer（最多拉取500条）
      -> BookVisitBatchAggregator（按bookId求和）
      -> BookVisitBatchWriter（事务）
      -> UPDATE book SET visit_count = visit_count + delta

Tomcat 线程只负责把消息交给 Kafka 生产者，不再等待热门 book 行完成更新。数据库
更新转移到 Kafka 消费线程中，并把同一本书的一批点击合成一个 delta。

### 11.2 具体代码位置

| 位置 | 学习重点 |
|---|---|
| compose.local.yml | MySQL、Redis、Kafka 4.3.1 KRaft 三容器 |
| novel-front/src/main/resources/application-dev.yml | producer/consumer、序列化、批消费、超时 |
| event/BookVisitEvent.java | 带 eventId、版本和时间的消息契约 |
| config/BookVisitKafkaProperties.java | Topic、DLT、Group、批大小绑定 |
| config/BookVisitKafkaConfig.java | Topic 创建、有限重试、DLT 路由 |
| messaging/BookVisitEventPublisher.java | 异步发送、成功/失败指标、失败日志限流 |
| controller/BookController.java | 原同步 SQL 入口改为 publish |
| messaging/BookVisitBatchAggregator.java | 校验并按 bookId 汇总 delta |
| messaging/BookVisitBatchWriter.java | 一个 Kafka 批次的数据库事务边界 |
| messaging/BookVisitEventConsumer.java | 批监听、聚合、写入和消费指标 |
| mapper/FrontBookMapper.java | Long 类型增量接口 |
| resources/mybatis/mapping/BookMapper.xml | 安全参数绑定的原子加法 SQL |
| messaging 和 config 下对应 Test | 发布、聚合、事务、消费、重试与 DLT 单测 |
| performance/check-book-visit-kafka.ps1 | 不重置数据的端到端正确性检查 |
| performance/jmeter/book-visit-count.jmx | 前后完全相同的点击压测计划 |
| performance/results/book-visit-kafka-summary.md | 所有实测数字和限制 |

### 11.3 实测结果

| 指标 | 同步 MySQL | Kafka 聚合 |
|---|---:|---:|
| 1线程吞吐 | 209.14 req/s | 1,753.46 req/s |
| 1线程P99 | 8 ms | 1 ms |
| 局部1000点击SQL | 1000 | 20 |
| 完整压测数据库UPDATE | 每次点击一条 | 18,623 |
| SQL减少 | 0% | 99.6326% |
| 峰值Lag | 不适用 | 1,965,279 |
| 压测后排空时间 | 不适用 | 约190秒 |

按完整压测比例计算，10,000 次计入数据库的点击约变成 37 条 UPDATE，而不是
10,000 条。含金量就在这里：接口线程不争抢同一 MySQL 热行，数据库的写锁和网络
往返减少约两个数量级。

### 11.4 故障边界

- MySQL停机：100个请求全部由Kafka确认，Lag变成100；MySQL恢复后增量补齐100，
  Lag回到0，没有进入DLT。
- Kafka停机：真实章节页仍在578 ms内读取成功；10个点击请求没有回退写MySQL，
  生产失败指标增加10。
- 非法消息：bookId=0 的事件进入DLT，随后合法事件仍正常加1。
- 完整高压：125,094个发送超过本地3秒producer超时；另有480个发送虽然客户端
  报超时但最终出现在Broker并计入数据库。这是确认结果不确定性，不是精确一次。
- 这是允许近似的热度计数。订单、余额等业务不能照搬，必须使用Outbox、幂等键或
  去重表解决强一致问题。

压测还暴露了两个新瓶颈：同一热门bookId只能使用一个分区保持顺序；本机JMeter
在约两万请求每秒附近出现Windows临时端口耗尽。因此当前结果不能解释为无限并发。

### 11.5 自己观察

    docker compose -f '.\compose.local.yml' ps
    docker exec novel-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic novel-book-visit-v1
    docker exec novel-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group novel-book-visit-writer-v1
    docker exec novel-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic novel-book-visit-dlt --from-beginning
    Invoke-RestMethod 'http://127.0.0.1:8084/actuator/metrics/novel.book.visit.kafka.send'
    Invoke-RestMethod 'http://127.0.0.1:8084/actuator/metrics/novel.book.visit.kafka.batch_size'
    Invoke-RestMethod 'http://127.0.0.1:8084/actuator/metrics/novel.book.visit.kafka.db_updates'
    & '.\performance\check-book-visit-kafka.ps1' -BookId 2055879962859147264 -RequestCount 1000 -MaxConcurrency 100

正式设计和实施步骤分别保存在：

- docs/superpowers/specs/2026-09-02-kafka-book-visit-design.md
- docs/superpowers/plans/2026-09-02-kafka-book-visit.md

## 12. 第六阶段：把性能优化变成可观测闭环

前面的缓存、SQL 和 Kafka 改造解决了具体问题，但只有运行指标才能持续回答：优化是否
仍然有效、瓶颈现在在哪里、依赖失败时系统发生了什么。本阶段采用方案 A：Java 应用
继续运行在 Windows，Prometheus 和 Grafana 运行在 Docker。

数据流：

    novel-front /actuator/prometheus
      -> Prometheus 每15秒抓取并计算告警
      -> Grafana 查询 Prometheus 并展示 Dashboard

为避免影响生产环境，`application-monitoring.yml` 只有显式启用时才把管理端口绑定到
`0.0.0.0:8084`，供 Docker 使用 `host.docker.internal` 访问。Prometheus 9090 和
Grafana 3000 在宿主机上仍只绑定 `127.0.0.1`。

### 12.1 观察的四组问题

1. HTTP：请求速率、5xx 比例、P95/P99 是否异常。
2. 缓存：业务请求命中率、Redis 读写错误、锁竞争、MySQL 回源次数和耗时。
3. Kafka：发送成功/失败、消费和落库速度、批量聚合效果、Consumer Lag。
4. 资源：Heap、GC、线程和 HikariCP 连接池是否接近容量上限。

缓存命中率的分母是 `ChapterContentCache#getOrLoad` 的完整查询次数，因此能直接回答
“100 次章节请求有多少次没有访问 MySQL”。它和 Redis 实例上所有命令的全局命中率
不是同一个指标。

### 12.2 告警的意义

- `NovelFrontDown`：应用或 Actuator 连续一分钟不可抓取。
- `ChapterCacheErrors`：缓存读取、写入或锁操作发生错误。
- `LowChapterCacheHitRate`：五分钟至少100次查询且命中率持续低于90%。
- `KafkaPublishFailures`：点击事件发送失败。
- `KafkaConsumerLagHigh`：点击 Topic 积压超过本地演示阈值。
- `HikariPendingConnections`：请求开始等待数据库连接。
- `JvmHeapUsageHigh`：Heap 长时间超过85%。
- `FrontExecutorRejectedTasks`：共享前台线程池在五分钟内拒绝过任务。
- `FrontExecutorQueueSaturated`：共享前台线程池队列连续一分钟没有剩余容量。

这里的阈值用于学习和本地故障演练，不能原样复制到生产。生产阈值必须结合稳定流量、
机器规格、错误预算和历史基线重新校准。

### 12.3 验证入口

    docker compose -f '.\compose.local.yml' up -d
    & '.\performance\start-front-monitoring.ps1'
    & '.\performance\test-observability-config.ps1'
    & '.\performance\check-observability.ps1'

页面入口：Prometheus Targets 为 `http://127.0.0.1:9090/targets`，Alerts 为
`http://127.0.0.1:9090/alerts`，Grafana 为 `http://127.0.0.1:3000`。Grafana 的
Prometheus 数据源和 `Novel-Plus Overview` Dashboard 都由仓库文件自动创建。

完成这一阶段后，项目形成“压测产生流量 → 指标采集 → Dashboard 观察 → 告警发现
异常 → 根据数据继续优化”的闭环。下一步再进入部署加固，而不是继续无边界地增加本地
监控组件。

## 13. 第七阶段：章节线程池容量闭环

本阶段先给前台共享的自定义 `ThreadPoolExecutor` 接入 Micrometer，暴露活动线程数、线程池大小、
排队任务数、剩余队列容量、完成任务数和拒绝次数；Grafana 增加线程池利用率与
队列/拒绝面板，Prometheus 增加拒绝和持续饱和告警。拒绝处理仍保留原来的
`AbortPolicy` 语义，发生拒绝时先计数再抛出 `RejectedExecutionException`。

第一次使用原来的“三任务/请求”代码测试100并发时，线程池达到20个活动线程，
队列达到100/100，约10万次任务提交被拒绝。JMeter共记录361,582个响应，其中
349,855个是只有591字节的HTTP 200自定义404页，业务错误率96.76%。表面的
6,029.8 req/s主要来自快速错误页，不是有效吞吐。

根因不是Redis或MySQL变慢，而是100个请求会瞬间向同一线程池提交约200个初始任务，
随后还会产生依赖任务，超过“20个最大线程 + 100个队列槽位”的接纳容量。修复没有
扩大队列，而是把书籍、目录、上下章、正文和购买判断组合成一个完整页面任务，让
一次请求只提交一次，同时保留线程池作为后端并发边界。

最终使用同一书籍 `2055879962859147264`、同一章节 `2055884263706857472`，每档持续
60秒：

| 并发 | 请求数 | 吞吐 | 平均 | P95 | P99 | 业务错误率 | 队列峰值 | 最低剩余容量 | 拒绝 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 52,220 | 870.4 req/s | 52.6 ms | 62 ms | 68 ms | 0% | 38 | 62 | 0 |
| 100 | 50,898 | 847.7 req/s | 98.4 ms | 130 ms | 145 ms | 0% | 85 | 15 | 0 |

100并发时队列采样稳定在83–85附近，没有持续上升。结果说明当前代码在本机测试环境
下的稳定边界至少覆盖100个并发阅读用户；并发翻倍后吞吐不再增长且尾延迟上升，已经
接近容量平台期，后续不能仅靠增加线程数或队列宣称性能继续提升。

## 14. 第八阶段：有效阅读心跳与可观测性

点击章节和真正停留阅读是两种信号。本阶段在桌面/移动阅读页渲染一次性页面访问 token，
浏览器只有连续可见且有焦点满 30 秒才发送心跳。失焦、隐藏或离开页面会舍弃不足一个
周期的时间；网络失败不会立即重试，避免把重试风暴当成阅读时间。两个模板共用 ES module，
其九项 Node 测试覆盖完整周期、失焦/隐藏恢复、退出清理及合法/非法 bootstrap。

服务端从 Cookie 建立匿名浏览器 session，从可信代理覆盖的 X-Real-IP 生成 HMAC。
Redis Lua gate 在一次原子执行中校验页面绑定、递增 sequence、60 秒 session 上限 2、
IP 上限 120、每个 session/书/章节每天 1800 秒额度。仅 accepted 计入 30 秒并发 Kafka；
重复、非法 token、超过限流或日额度不加时。每次检查、去重和额度变更必须原子进行，
否则多个标签页并发会绕过额度。页面 token 不证明真实人在阅读，这仍是可解释的近似指标。

7 天 Cookie 身份与服务端日聚合相互独立：身份可跨日沿用，statDate 依据服务器
Asia/Shanghai 日期自然切换。清 Cookie/换浏览器使同人被分开，共享浏览器使多人被合并，
所以不能把匿名浏览器数等同于真实人数。IP 只用于粗粒度防滥用，共享出口会影响限流。
Kafka 消息只含事件和书/章节/秒数/时间/统计日，不含 Cookie、session hash、IP HMAC 或原始 IP；
身份散列只留在短期 Redis 状态，不能出现在日志或指标标签中。

启动入口 `performance/start-front-monitoring.ps1` 依参数、已有环境、32-byte CSPRNG 顺序
注入 HMAC secret，值只经子进程环境传递，不输出。公网必须注入稳定高熵密钥、所有实例
一致使用；应用端口仅允许可信 Nginx 到达，Nginx 覆盖 X-Real-IP，限制 Actuator 访问。
本地随机密钥重启会改变 IP 分桶，不能用作公网密钥管理方案。

监控把“收到请求”“接受 credit”“Kafka 确认”分开观察。heartbeat 的 result 只允许
accepted/duplicate/invalid_page/session_rate_limited/ip_rate_limited/daily_cap_reached/redis_error，
Kafka result 只允许 success/failed；credited_seconds 累计接受秒数，Redis gate timer 观察耗时。
Grafana 三个 Reading 面板分别展示结果速率、计入秒数速率、发送结果；Prometheus 的
ReadingEngagementRedisErrors 对五分钟错误信号持续一分钟告警，Kafka 失败告警没有额外等待。
所有查询仅使用有限 result 标签，不以书籍、章节、匿名身份或 IP 作为维度。

### 首页阅读推荐：快照可观测性与无副作用验收

首页的 type 0/1/4 仍由人工配置决定；type 2 最多 5 本、type 3 最多 6 本，按照最近
7 天和 15 天的阅读秒数排序。type 3 优先避开 type 2，独立候选不足时允许跨组重复。
刷新任务五分钟一次，Redis 快照 TTL
十五分钟。为了区分“算法没有候选”和“监控链路没有上报”，Dashboard 不用
`or vector(0)` 填补推荐指标缺失值，而是直接展示真实 series。

四个面板精确对应 `novel_home_recommendation_source_total`、
`novel_home_recommendation_refresh_total`、
`novel_home_recommendation_generation_seconds` 和
`novel_home_recommendation_local_age_seconds`。其中 age 是单个 `novel-front` 进程的
最后一次成功本地快照年龄，最多允许本地回退 24 小时；它不是 Redis 的全局分布式新鲜度。
Prometheus 在五分钟内出现刷新 `db_error` 时触发
`HomeRecommendationRefreshDbErrors`，并在 age 超过 900 秒持续五分钟后触发
`HomeRecommendationSnapshotStale`。

真实本地验收入口如下；它只读首页和排行接口、只对 Redis 单键 GET，并使用 MySQL
CTE/SELECT 重算同一个 `generatedAt` 窗口，绝不 DEL/SET Redis、修改 `book_setting` 或
插入伪造阅读积分：

这里执行的是脚本内固定的两条 SQL。正则校验负责防止维护误改，但不能替代数据库权限；
共享或公网环境仍应使用只有 `SELECT` 权限的独立验收账号。

```powershell
& '.\performance\test-home-recommendation-script.ps1'
& '.\performance\check-home-recommendation.ps1' -MySqlPassword '123456' -RedisPassword '123456'
& '.\performance\check-observability.ps1' -GrafanaPassword 'change-me'
```

脚本会确认所有 group、上限、动态 sort、组内去重、独立 click/new/update 排行端点，
并在前后两次读取到不同快照或统计源，或当前数据无法重建快照生成时刻排序时返回
`INCONCLUSIVE`。这表示现场不足以作确定判断，必须在稳定窗口重试，不能误判为算法
通过或失败。

真实接口验收入口：

```powershell
& '.\performance\start-front-monitoring.ps1'
& '.\performance\check-reading-engagement.ps1' -BookId 2055879962859147264
& '.\performance\check-observability.ps1' -GrafanaUser 'admin' -GrafanaPassword '123456'
& '.\performance\check-reading-engagement.ps1' -BookId 2055879962859147264 -VerifyIpLimit
& '.\performance\check-reading-engagement.ps1' -BookId 2055879962859147264 -FailureDrill Redis
& '.\performance\check-reading-engagement.ps1' -BookId 2055879962859147264 -FailureDrill Kafka
```

启动在单独窗口运行。普通 smoke 只读查询第一个非 VIP 章节，以同一 Cookie 和 token
发送 1/1/2，期望 accepted +2、duplicate +1、60 秒、Kafka success +2。
IP 演练随后创建 121 个独立 session，固定测试 X-Real-IP，60 秒内应得到 120/1；
重跑至少间隔 61 秒，不能靠删除 Redis 状态重置额度。所有脚本不打印身份值，不修改 MySQL。

Redis 停机时章节仍应 HTTP 200 且没有 token，心跳失败不阻断阅读；Kafka 停机时已接受
心跳仍 HTTP 200，但 failed 增长，credit 不回滚，也不回退到同步 MySQL。每个故障演练
用 finally 恢复依赖并确认 healthy，不停止 MySQL，不做广泛 Redis 清理。具体操作、
隐私边界和桌面切窗/手机切后台的 30 秒人工焦点验收见 `performance/README.md`。

阅读日表消费者现已实现：事件的 `statDate` 必须与 `occurredAt` 在上海时区的日期相符，
同一本书不同日期分别聚合。accepted、发送成功和最终落库是三个不同阶段，不能混为一谈。
Kafka 发送失败仍可能丢失已接受的 credit，目前不提供端到端零丢失承诺。

### 阅读日统计：事务和消息重放

消费者调用独立注入的 `ReadingDailyBatchWriter` Bean，经过 Spring 事务代理，避免 `this`
自调用绕过事务。去重登记和日表累加在同一事务中提交；数据库失败时一起回滚，Kafka 重试。
批次 `batch_token` 用于区分哪些登记属于当前事务；同一 UUID 的规范化业务指纹必须相同，
相同载荷的重放不再累加，载荷不同则视为冲突，不应静默计入统计。

无效事件进入阅读专用死信队列；批次中失败位置之前的正常前缀先持久化，防止 Kafka
提交前缀 offset 后丢失有效事件。数据库异常不包装为无效消息，保留重试语义。

去重登记默认保留 14 天，并在上海时间 03:15 分批清理，每批最多 5000 条。
清理通过独立 Bean 的 `REQUIRES_NEW` 事务逐批提交，只删除去重记录，不删除日统计。
超过保留窗口的历史消息重新投递可能再次计入；长期回放须另行制定策略。
点击量是近似统计，阅读消费端是在有限去重保留窗口内按事件 ID 幂等，不代表匿名阅读
身份可靠，也不代表消息生产、网络和消费全链路精确一次。

本地验收使用 `performance/check-reading-daily-aggregation.ps1`，包含重复、分日期/书籍、
死信和 MySQL 故障恢复模式。它生成独立测试书籍 ID，仅只读使用输入 BookId 查询章节，
使用 2000 年固定日期避免混入实时统计。已有测试 ID 就拒绝运行；验收失败则保留证据，
不撤掉尚可能被重放消息使用的去重保护。真实链路通过前，不能将脚本编排测试当作上线验收。

## 15. 第九阶段：认证安全闭环

认证升级不是简单地把 MD5 替换成一个更长的字符串，而是同时处理密码验证、历史兼容、
验证码状态、邮件投递、暴力破解、JWT 撤销和依赖故障。新账号只写 Argon2id；历史 MD5
账号用旧算法验证成功后，使用本次内存中的原始输入生成带随机 salt 的 Argon2id，并通过
“旧哈希 + 旧算法”条件更新避免并发覆盖。迁移写入失败只记录指标，本次已正确验证的登录
仍可成功，下一次登录继续尝试；未知算法或损坏哈希始终失败关闭。

验证码由 Redis Lua 原子完成签发、冷却、小时额度、错误次数和单次消费。注册、重置密码、
修改邮箱各自拥有用途隔离的 HMAC key；Redis 中保存验证码摘要而非明文。邮件通过有界线程池
异步发送，失败或队列拒绝时按“当前 code 摘要仍匹配”条件撤销验证码，避免旧失败任务删除
后来重新签发的验证码。公开接口对账号存在与否保持相同响应，降低邮箱枚举风险。

登录保护把账户与 IP 分开：账户连续失败达到阈值后临时限制；共享出口 IP 先升级图片验证码，
再做短窗口请求限流，不做长期整网封禁。客户端地址只接受明确可信代理覆盖的转发头。密码重置
和登录后改密会递增数据库 `token_version`，JWT 中旧版本随即失效；数据库无法确认版本时拒绝
认证，不能继续相信旧 Token。

本地验收引入只绑定回环地址的 Mailpit，真实走 SMTP 但不投递公网邮件。脚本从 Mailpit API
读取随机测试邮箱的验证码，精确计算 HMAC Redis key 验证 TTL，并用隔离账号检查注册、迁移、
限流和 Token 撤销。Redis/MySQL/SMTP 故障必须失败关闭；Kafka 与认证无关，其停机不应阻断
登录。所有停容器演练都要求显式开关和 `finally` 恢复，不能在共享或生产环境执行。

Argon2 成本不是越高越好，也不能拍脑袋固定。`benchmark-argon2.ps1` 在固定允许列表和总内存
预算内测 encode/verify 的单线程与目标并发 p50/p95、吞吐。开发机结果只能证明代码和测量链路，
上线前必须在目标 Linux 规格上重跑，结合登录峰值、CPU、内存和延迟预算选择参数，同时保留
代码规定的安全下限。认证指标只使用 purpose/outcome/operation 等有限枚举标签，绝不把邮箱、
IP、用户 ID、异常消息或 Token 放进 Prometheus。
