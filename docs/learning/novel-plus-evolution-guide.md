# novel-plus 二次开发学习与演进档案

> 用途：记录本项目每一次改进的原因、代码位置、验证方法和实测指标。以后继续学习或换对话时，先读本文，再读对应代码。每完成一个阶段，都要更新本文。

## 1. 已确定的项目路线

我们选择的是“方案 A”：暂时不拆微服务，以现有 Spring Boot 单体项目为基础进行实战级后端改造。

总体顺序：

1. 把项目、Docker、MySQL、Redis、Git 和 JMeter 跑通。
2. 对章节阅读核心链路建立可靠压测基线。
3. 使用 Redis 和代码结构优化解决热点章节、高并发失败问题。
4. 再使用 Kafka 处理适合异步化的写操作，而不是为了使用 Kafka 强行引入。
5. 完善搜索和推荐；先做可解释的规则/行为推荐，再考虑 AI 增强。
6. 后端优先，逐步形成个人特色；最后学习 Linux、Docker 和云服务器部署。

当前进度在第 3 步。Kafka、搜索、推荐、AI、CentOS 部署尚未开始。

## 2. 工作区与 Git 状态

- 主项目：`D:\offer\novel-plus`
- 性能改进 worktree：`D:\offer\novel-plus\.worktrees\chapter-performance`
- 当前分支：`feature/chapter-performance`
- 本轮起点：`c816f4b`
- 当前“3任务 + 上下章单 SQL”改动尚未提交或合并。

已提交的阶段记录：

| Commit | 含义 |
|---|---|
| `dece9eb` | 建立章节阅读原始压测基线 |
| `05d13da` | 新增带击穿保护的章节缓存 |
| `fb5bea4` | 把数据库章节读取接入缓存，并补写后失效 |
| `a1aa4ae` | 明确 Spring 应选择的缓存构造器 |
| `c816f4b` | 整理章节缓存压测结论和操作说明 |

学习 Git 时，先掌握：`status` 看状态、`diff` 看改动、`add` 选择暂存内容、`commit` 保存一个可回退节点、`log` 看历史、`branch/worktree` 隔离功能开发。不要把 Git 理解成单纯上传 GitHub，它首先是本地版本管理工具。

## 3. 本地基础设施与配置来源

Docker 不是虚拟机镜像的同义词。镜像是只读模板，容器是镜像启动后的运行实例，volume 保存容器删除后仍需保留的数据。

主目录 `D:\offer\novel-plus\compose.local.yml` 定义：

| 服务 | 容器名 | 宿主机端口 | 容器端口 | 密码 | 数据卷 |
|---|---|---:|---:|---|---|
| MySQL 8 | `novel-mysql` | 3307 | 3306 | `123456` | `novel-mysql-data` |
| Redis 7 | `novel-redis` | 6380 | 6379 | `123456` | `novel-redis-data` |

数据库初始化文件：`D:\offer\novel-plus\doc\sql\novel_plus_data.sql`。

需要特别注意配置优先级：

- Docker端口和密码：`D:\offer\novel-plus\compose.local.yml`
- 实际MySQL连接：`D:\offer\novel-plus\config\shardingsphere-jdbc.yml`，当前为 `localhost:3307`、密码 `123456`
- 仓库默认Redis开发配置：`novel-common/src/main/resources/application-common-dev.yml`
- 性能测试启动时通过 JVM 参数覆盖Redis端口和密码：`-Dspring.data.redis.port=6380 -Dspring.data.redis.password=123456`
- 通过 `-Duser.dir=D:\offer\novel-plus` 让独立 worktree 使用主目录的外部 `config`。

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
