# Novel-Plus 阅读时长按日聚合设计

## 1. 背景与目标

匿名阅读时长采集链路已经能够在有效阅读满 30 秒后，经 Redis 原子闸门向 Kafka Topic `novel-reading-engagement-v1` 发布隐私安全事件。当前事件尚未被持久化，因此应用重启、Kafka 保留期结束后无法查询长期热度，也不能稳定地为后续推荐排序提供特征。

本阶段新增独立 Kafka 批量消费者，将阅读事件严格幂等地聚合到 MySQL 的“书籍 + 自然日”统计表。核心目标如下：

- 批量消费和批量 SQL，避免每次心跳直接更新数据库。
- Kafka 至少一次投递下，同一 `eventId` 无论重放多少次都只计入一次。
- 去重登记与日统计更新处于同一事务，不产生“登记成功但统计未增加”或相反的中间状态。
- 坏消息进入阅读链路自己的 DLT，不污染现有点击量消费链路。
- MySQL 暂时故障时重试；最终失败进入 DLT，且不提交为成功消费。
- 只保存书籍日聚合，不保存 Cookie、IP、匿名身份或章节阅读轨迹。
- 产出足够的指标、自动化测试和运行验证，为下一阶段推荐接入提供可信数据。

## 2. 范围与非目标

### 2.1 本阶段包含

- 阅读事件专用 Kafka 消费配置、消费者组、容器工厂和 DLT。
- 事件格式与语义校验。
- MySQL 阅读事件去重表和书籍日聚合表。
- 单事务批量登记、筛选、聚合和 UPSERT。
- 14 天去重记录定时分批清理。
- 消费、去重、落库、重试、DLT、清理和积压监控。
- 单元测试、数据库集成测试、Kafka/运行验收脚本和学习文档。

### 2.2 本阶段不包含

- 不修改首页或榜单的推荐排序；推荐在日统计稳定后单独接入。
- 不按章节、匿名会话或真实用户保存长期明细。
- 不把阅读时长用于计费、VIP、权益或结算。
- 不实现跨设备身份识别，也不建设用户画像。
- 不追求 Kafka 与 MySQL 的分布式恰好一次事务；通过数据库唯一键实现业务幂等。
- 不改造现有 `BookVisitEvent` 点击量消费语义。

## 3. 总体架构

```text
novel-reading-engagement-v1
        |
        v
ReadingEngagementConsumer（批量监听，专用容器工厂）
        |
        v
ReadingEventValidator（逐条校验）
        |
        v
ReadingDailyBatchWriter（单个 MySQL 事务）
        |-- INSERT IGNORE reading_event_dedup
        |-- SELECT 本批真正新登记的 event_id
        |-- Java 按 stat_date + book_id 聚合
        `-- UPSERT book_reading_daily

异常重试耗尽/非法事件 --> novel-reading-engagement-dlt
定时任务 -------------> 分批清理 14 天前的去重记录
```

数据库事务成功返回后，Kafka 批监听器才允许当前批次 offset 提交。若数据库事务已经提交但 offset 提交失败，Kafka 会重新投递；唯一 `event_id` 会阻止重复累计。

## 4. 数据模型

项目当前使用 `doc/sql` 中的人工 SQL 脚本管理结构变化，不引入 Flyway。新增脚本建议为 `doc/sql/20260911_reading_daily_aggregation.sql`。

### 4.1 书籍日聚合表

```sql
CREATE TABLE `book_reading_daily` (
  `stat_date` date NOT NULL COMMENT 'Asia/Shanghai 统计日期',
  `book_id` bigint NOT NULL COMMENT '书籍 ID',
  `credited_seconds` bigint unsigned NOT NULL DEFAULT 0 COMMENT '有效阅读秒数',
  `heartbeat_count` bigint unsigned NOT NULL DEFAULT 0 COMMENT '已计入心跳数',
  `first_event_at` datetime(3) NOT NULL COMMENT '首次事件时间',
  `last_event_at` datetime(3) NOT NULL COMMENT '最近事件时间',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`stat_date`, `book_id`),
  KEY `idx_book_reading_daily_book_date` (`book_id`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='书籍每日有效阅读聚合';
```

设计说明：

- 唯一统计粒度是 `stat_date + book_id`，本阶段不建立章节日表。
- `credited_seconds` 和 `heartbeat_count` 使用 `BIGINT UNSIGNED`，Java 聚合使用 `Math.addExact` 或等价溢出检查；异常批次不得写入截断值。
- UPSERT 时累计秒数和心跳数，`first_event_at` 取 `LEAST`，`last_event_at` 取 `GREATEST`。
- 不设置指向书籍表的外键，避免热门事件写入与书籍管理事务耦合；事件校验只保证 ID 为正数。
- 该表只保留汇总事实，不足以还原单个读者或单章轨迹。

### 4.2 事件去重表

```sql
CREATE TABLE `reading_event_dedup` (
  `event_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event_fingerprint` binary(32) NOT NULL COMMENT '规范化业务载荷 SHA-256',
  `batch_token` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `stat_date` date NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`event_id`),
  KEY `idx_reading_event_dedup_batch` (`batch_token`),
  KEY `idx_reading_event_dedup_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阅读事件短期幂等登记';
```

设计说明：

- `event_id` 是全局幂等键；`INSERT IGNORE` 只有首次出现时能插入。
- `event_fingerprint` 是版本、事件 ID、书籍/章节 ID、秒数、事件时间和统计日期按固定格式编码后的 SHA-256；它不含身份数据，用于发现同一 ID 对应不同载荷的污染消息。
- 每次数据库写入生成一个全新的随机 `batch_token`。随后只查询该 token 对应的 `event_id`，即可准确识别本批新增事件。
- `stat_date` 方便诊断和受控清理，但统计仍以 Kafka 事件中的已校验值为准。
- `created_at` 使用数据库时间，避免应用节点时钟影响 14 天保留期。
- 去重表不保存事件载荷、会话、Cookie、IP、章节或匿名身份。

## 5. Kafka 拓扑与配置隔离

### 5.1 为什么必须使用专用容器工厂

现有 `application-dev.yml` 的全局消费者配置把 JSON 默认类型固定为 `com.java2nb.novel.event.BookVisitEvent`，并关闭类型头：

```yaml
spring.json.value.default.type: com.java2nb.novel.event.BookVisitEvent
spring.json.use.type.headers: false
```

现有 `BookVisitKafkaConfig` 的错误恢复器也固定把失败消息投递到 `novel-book-visit-dlt`。如果阅读消费者直接复用默认监听器，它可能把阅读 JSON 反序列化成错误类型，或者把阅读坏消息送进点击量 DLT。

因此新增 `ReadingEngagementKafkaConfig`，显式创建并命名专用的：

- `ConsumerFactory<Long, ReadingEngagementEvent>`；
- `ConcurrentKafkaListenerContainerFactory<Long, ReadingEngagementEvent>`；
- `DefaultErrorHandler`；
- 阅读 Topic 与阅读 DLT Topic。

`@KafkaListener` 必须通过 `containerFactory` 指向该专用工厂，不能依赖默认工厂。

### 5.2 配置项

配置前缀使用 `novel.kafka.reading-engagement`：

```yaml
novel:
  kafka:
    reading-engagement:
      topic: novel-reading-engagement-v1
      dlt-topic: novel-reading-engagement-dlt
      group-id: novel-reading-engagement-writer-v1
      max-poll-records: 500
      retry-interval: 5s
      max-retries: 12
      dedup-retention: 14d
      cleanup-batch-size: 5000
```

生产端和消费端共享 Topic 名称来源，避免同一属性在两个配置类中重复定义。Kafka 基础连接、SSL/SASL 等基础设施配置继续来自 `spring.kafka`；专用 ConsumerFactory 在此基础上覆盖阅读事件的 JSON 目标类型、批监听和手动批次提交语义。

Topic 规划：

- 主 Topic：`novel-reading-engagement-v1`，3 分区，副本数 1（本地环境）。
- DLT：`novel-reading-engagement-dlt`，分区数与主 Topic 相同。
- 消费组：`novel-reading-engagement-writer-v1`。
- 消息 key：`bookId`，保持同一本书在稳定分区内的顺序。

公网生产环境应按 Kafka 集群规模提高副本数；代码不能把本地副本数 1 当作可靠性保证。

## 6. 事件校验

`ReadingEventValidator` 在进入数据库写入前逐条验证：

- 事件对象非空。
- `version == 1`。
- `eventId` 是规范 UUID 字符串。
- `bookId > 0`、`chapterId > 0`。
- `creditedSeconds == 30`，消费者不接受客户端或旧生产者自报的任意秒数。
- `occurredAt` 和 `statDate` 非空。
- `statDate` 必须等于 `occurredAt` 按 `Asia/Shanghai` 换算出的日期；消费者不根据自己的当前日期重算历史事件日期。

消费者允许正常的 Kafka 积压和历史重放，因此不因事件“不是今天”而拒绝。`occurredAt` 仅用于聚合表的首末时间；明显无法解析或超出数据库时间范围的值视为非法。

同一 Kafka 批次若出现相同 `eventId`：

- 载荷完全一致时，只保留一份参与后续登记。
- 同一 `eventId` 对应不同业务载荷时，判定为数据完整性错误，不能任意选择其中一条。

非法事件属于不可重试错误，定位到失败记录后发送至阅读 DLT。批监听错误处理必须保留失败索引，使该条进入 DLT 后，后续合法消息仍能继续处理，不能因为一个坏事件永久阻塞整个分区。

2026-09-14 修正：`BatchListenerFailedException` 会让错误处理器提交失败索引之前的 offset。因此消费者必须先通过独立事务 writer 成功处理该前缀，才能抛出失败索引，不能“整批不写却报告中间索引”。同批异载荷定位到第一次不同的后续记录；历史指纹冲突定位到该 ID 的第一次出现。完整事务因冲突回滚后，前缀另行调用代理 writer。若前缀内部存在更早冲突，则优先报告更早索引；若前缀数据库写入失败，则原样抛出数据库异常，不提交尚未落库的前缀。正常批次仍只有一次 writer 调用；故障批次允许前缀独立事务，幂等键保障重放安全。

## 7. 严格幂等批量写入

### 7.1 单事务算法

`ReadingDailyBatchWriter.write(events)` 由 Spring 代理对象调用，并以 `@Transactional(rollbackFor = Exception.class)` 包裹下列全部步骤：

1. 为本次调用生成随机 UUID `batchToken`。
2. 为每个事件计算规范化 `eventFingerprint`，单条批量 `INSERT IGNORE` 将 `eventId`、指纹、相同 `batchToken` 和各自 `statDate` 写入 `reading_event_dedup`。
3. 用本批最多 500 个 `eventId` 批量查询已登记行，比较已存指纹；发现同一 ID 的指纹不一致时抛出带 `eventId` 的不可重试冲突异常，整个事务回滚，消费者据此定位原批次索引并送入阅读 DLT。
4. 查询结果中 `batch_token` 等于本次 token 的记录，才是本批真正首次登记的 `eventId`；其他指纹一致的记录属于正常重放。
5. 只保留首次登记事件，在 Java 内按 `(statDate, bookId)` 聚合：累计 `creditedSeconds`、`heartbeatCount`，计算最早和最晚 `occurredAt`。
6. 使用批量 UPSERT 写入 `book_reading_daily`；若本批没有新事件，则跳过 UPSERT。
7. 方法正常返回时提交事务；任一步异常都回滚去重登记和日统计更新。

不能把 `@Transactional` 方法通过同一个对象的 `this.write(...)` 自调用；消费者应注入独立 Spring Bean `ReadingDailyBatchWriter`，调用的是带事务拦截的代理对象。

### 7.2 SQL 语义

日统计 UPSERT 的核心语义为：

```sql
INSERT INTO book_reading_daily (...)
VALUES (...), (...)
ON DUPLICATE KEY UPDATE
  credited_seconds = credited_seconds + VALUES(credited_seconds),
  heartbeat_count = heartbeat_count + VALUES(heartbeat_count),
  first_event_at = LEAST(first_event_at, VALUES(first_event_at)),
  last_event_at = GREATEST(last_event_at, VALUES(last_event_at)),
  update_time = CURRENT_TIMESTAMP(3);
```

实际实现使用 MyBatis 动态批量 SQL，并限制单批最多 500 条 Kafka 记录，避免 SQL 参数和报文无限增长。不能对每个事件执行一次 `SELECT` 或 `UPDATE`。

### 7.3 故障窗口证明

| 故障发生点 | 数据库结果 | Kafka 重投后的结果 |
|---|---|---|
| 去重登记前失败 | 无变化 | 正常重新处理 |
| 去重登记后、聚合前失败 | 整个事务回滚 | 正常重新处理 |
| UPSERT 中失败 | 去重和聚合均回滚 | 正常重新处理 |
| 数据库提交后、offset 提交前失败 | 去重和聚合均已提交 | 重投时 `eventId` 已存在，不重复累计 |
| offset 提交后 | 已完成 | 不再重投 |

这不是 Kafka/MySQL 分布式事务的物理“恰好一次”，而是在去重记录保留期内实现统计结果的业务恰好一次。

## 8. 重试、DLT 与降级

- 参数、版本、UUID、固定秒数或冲突载荷错误：不可重试，直接进入 `novel-reading-engagement-dlt`。
- 可恢复的 MySQL 异常：固定间隔 5 秒，最多重试 12 次。
- 重试耗尽：当前失败记录或批次按明确的恢复策略进入阅读 DLT，并记录指标。
- key/value 反序列化失败：由 `ErrorHandlingDeserializer` 捕获。批消费者接收原始 `ConsumerRecord` 并检查双方的异常头，将失败记录交给阅读错误处理器，不能进入事务写入逻辑；失败索引前缀仍须先持久化。
- DLT 发布失败：必须记录错误并保持清晰的运行告警，不能伪装成消费成功。
- 阅读统计消费失败不影响章节 HTTP 请求；生产与消费已通过 Kafka 解耦。
- 阅读错误处理 Bean 使用独立名称并只安装到阅读容器工厂，不能替换现有点击量错误处理器。
- 阅读 DLT 使用独立、非默认候选的 producer factory 与 KafkaTemplate，继承基础连接及安全配置，按类型序列化 Long/原始字节 key 与阅读事件/原始字节 value。反序列化失败的原始字节必须原样保留，不能被全局 JsonSerializer 编成 Base64；不改变现有点击量模板或恢复器。

批监听的“坏消息隔离”需用 Spring Kafka 支持的失败索引机制实现，并通过真实 Kafka 集成测试验证 offset 行为。实现阶段不得仅凭单元测试假设一条坏消息之后的合法记录会被继续处理。

## 9. 去重记录清理

去重记录保留 14 天，用于覆盖 Kafka 的常规保留与重放窗口。清理任务按 `Asia/Shanghai` 每日凌晨执行：

```sql
DELETE FROM reading_event_dedup
WHERE created_at < :cutoff
ORDER BY created_at
LIMIT 5000;
```

任务循环执行小批删除，直到本轮删除数小于 5000；每批独立提交，避免超大事务、长时间持锁和一次性产生大量 binlog。删除条件只依赖数据库 `created_at`，不能按应用传入的 `statDate` 清理。

第一版部署为单应用实例，因此直接使用 Spring 定时任务。未来横向扩容到多个应用实例前，必须增加 ShedLock、数据库抢锁或独立调度器，防止多个节点同时清理。清理失败只记录指标和受控日志，下次调度继续，不影响在线消费。

14 天之后再次人为重放同一旧事件可能重新计数，这是有意接受的边界。生产 Kafka 的消息保留时间与人工回放流程必须不长于幂等保留承诺；若未来需要长期审计或任意时间回放，应永久保存事件账本，而不是无限扩大当前短期去重表。

## 10. 可观测性

新增 Micrometer 指标，标签只使用有限枚举，禁止使用 `bookId`、`eventId`、日期或批次 token 作为标签：

- `novel.reading.kafka.consumed`：进入有效批次的事件数。
- `novel.reading.kafka.invalid`：非法事件数，按有限原因枚举。
- `novel.reading.kafka.deduplicated`：已存在而跳过的事件数。
- `novel.reading.kafka.persisted_seconds`：真正新增的阅读秒数。
- `novel.reading.kafka.daily_rows_updated`：UPSERT 的日聚合行数。
- `novel.reading.kafka.batch_size`：批大小分布。
- `novel.reading.kafka.retry`：重试次数。
- `novel.reading.kafka.dlt`：进入阅读 DLT 的事件数。
- `novel.reading.dedup.cleanup.deleted`：清理删除数。
- `novel.reading.dedup.cleanup.failures`：清理失败数。

Grafana 增加阅读消费速率、持久化秒数、去重数、批大小、重试、DLT、日表更新数、清理数和专用消费组 lag。告警至少覆盖：

- 阅读消费组持续积压。
- 阅读 DLT 持续增长。
- MySQL 重试持续出现或重试耗尽。
- 去重清理连续失败。

日志不得输出完整事件 JSON，以免未来事件扩展后误泄露字段；只记录必要的事件 ID、异常类型和批次统计，并对重复故障限频。

## 11. 安全与隐私

- 两张新表都不保存用户 ID、Cookie、匿名会话摘要、IP、IP HMAC 或章节轨迹。
- DLT 会保留原始阅读事件，但当前事件本身已经过隐私最小化，只含事件 ID、书籍/章节 ID、30 秒、时间和版本。
- 数据库账号只授予新表所需的读写权限；公网不得暴露 MySQL、Kafka、Redis 或管理端口。
- `eventId`、时间和数字字段均通过类型及范围校验后才拼入批量参数；SQL 必须使用 MyBatis 参数绑定，不拼接用户输入。
- Topic ACL 应限制为前台应用生产、指定消费组消费；生产环境使用认证和加密连接。
- 阅读时长是近似热度数据，不能据此做权益处罚、财务结算或个人行为判断。

## 12. 测试策略

### 12.1 单元测试

- 配置属性默认值、边界和专用 Bean 命名。
- 阅读容器工厂使用 `ReadingEngagementEvent`，不继承 `BookVisitEvent` 默认类型。
- 验证器覆盖所有合法与非法字段。
- 同批相同事件只保留一次；相同 ID 不同载荷被拒绝。
- 聚合器按书籍和日期分组，正确累计秒数、计数和首末时间。
- 空的新事件集合不执行日表 UPSERT。
- 指标只在事务成功后按真实新增数量更新，回滚不得记录为已持久化。
- 清理任务正确计算上海时区 cutoff，并按 5000 条循环终止。

### 12.2 MySQL 集成测试

- 第一次写入增加 30 秒和 1 次心跳。
- 同一 `eventId` 再写一次，统计不变且去重数增加。
- 不同事件、同书同日正确相加。
- 不同书籍或不同日期写入不同聚合行。
- 较早/较晚乱序事件正确更新 `first_event_at`/`last_event_at`。
- 模拟 UPSERT 失败时，去重登记与日统计全部回滚；恢复后重试能计入一次。
- 两个并发批次包含同一 `eventId` 时最终只累计一次。
- 清理只删除 14 天前记录，不删除边界内记录或日聚合表。

### 12.3 Kafka 集成与回归测试

- 专用 Topic 能反序列化阅读事件并批量落库。
- 非法消息进入阅读 DLT，后续合法消息仍被处理。
- MySQL 暂时故障触发阅读重试，恢复后只落一次。
- 重试耗尽进入阅读 DLT。
- 点击量 Topic 继续由 `BookVisitEventConsumer` 处理，阅读错误不会进入 `novel-book-visit-dlt`。
- 阅读 Topic 错误不会影响现有点击量消费者。
- 原有 119 个 Java 测试、9 个 Node 心跳测试、可观测性契约与 Prometheus 配置继续通过。

## 13. 本地运行验收

使用唯一测试书籍和可清理的测试事件 ID，执行以下验收；脚本只能精确删除自己生成的数据，不能使用无条件或宽范围删除：

1. 发送一个合法事件，确认去重表新增 1 行，日表增加 30 秒和 1 次。
2. 重发完全相同事件，确认日表不再增长。
3. 给两个书籍、两个日期发送不同事件，确认分组结果准确。
4. 发送一个字段非法事件，确认它进入阅读 DLT，随后合法事件仍落库。
5. 临时停止 MySQL，确认出现重试且 offset 不被当作成功；恢复 MySQL 后只计入一次。
6. 确认阅读消费组 lag 最终回到 0。
7. 确认 Grafana 面板和 Prometheus 指标出现消费、持久化、去重、重试/DLT 数据。
8. 再运行点击量 Kafka 验收，确认原链路的最终增量和 lag 仍正确。

## 14. 交付顺序

实现阶段按以下依赖顺序推进：

1. SQL 结构与 MyBatis 数据访问契约。
2. 阅读 Kafka 属性和专用容器工厂。
3. 事件验证与纯内存批次归并。
4. TDD 实现事务性去重和日聚合写入。
5. 批量消费者、失败索引、重试和阅读 DLT。
6. 去重清理任务。
7. 指标、Grafana、Prometheus 告警和验收脚本。
8. 全量回归与实际依赖故障演练。

每一步先写失败测试，再实现最小代码使其通过。数据库结构、Kafka 隔离和幂等故障窗口均完成验证后，下一阶段才允许读取 `book_reading_daily` 接入推荐。

## 15. 方案取舍与已知边界

选择“Kafka 批消费 + MySQL 短期去重表 + 日聚合表”，而不是直接更新书籍主表，原因是：

- 日表能保留趋势，推荐可使用最近 1/7/30 天窗口，而主表单一累计值无法衰减。
- 独立表降低热门书籍主行的更新竞争，也便于回滚和验证。
- 短期唯一事件登记用较低存储成本覆盖 Kafka 至少一次投递。
- 批量 SQL 把大量 30 秒事件合并为少量书籍日行更新。

接受的边界包括：

- Kafka 事件在生产端发送失败时仍可能少记；前一阶段已选择阅读可用性优先。
- 14 天之后的人为旧消息重放不再保证去重。
- 单实例定时清理尚不支持多实例互斥。
- 本地 Kafka 单副本不代表生产高可用。
- 数据反映“页面有效聚焦时长”的近似值，不等于真人注意力或真实阅读完成度。
