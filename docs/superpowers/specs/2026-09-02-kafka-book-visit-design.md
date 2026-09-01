# Kafka小说点击量异步聚合设计

## 目标

把 `POST /book/addVisitCount` 从“每次点击同步执行一次MySQL UPDATE”改成“发送Kafka事件、消费者批量聚合后更新MySQL”。在热门小说短时获得大量访问时，用Kafka吸收流量峰值，用批量聚合减少热点行更新次数，同时不让非关键点击计数影响章节阅读。

本阶段必须用相同环境和负载分别测量同步SQL版本与Kafka版本，证明数据库UPDATE数量下降，而不是仅证明Kafka能够启动。

## 当前链路与问题

当前链路：

```text
页面JavaScript
  -> POST /book/addVisitCount
  -> BookController.addVisitCount
  -> BookServiceImpl.addVisitCount
  -> FrontBookMapper.addVisitCount
  -> UPDATE book SET visit_count = visit_count + 1
```

PC和移动端的小说详情页、章节页都会调用该接口。热门小说的每次访问都会更新同一行，产生大量数据库网络往返和热点行竞争。点击量只是热度指标，允许短暂延迟和极少量误差，不值得使用订单级强一致设计。

## 方案选择

采用Kafka批量消费并按小说聚合：

```text
HTTP请求 -> Kafka生产者 -> novel-book-visit-v1
                              |
                              v
                       批量消费者（最多500条）
                              |
                              v
                  Map<bookId, totalDelta>
                              |
                              v
                 每个bookId执行一次增量UPDATE
```

第一版不使用Kafka + Redis双层计数。Redis计数能进一步承载大规模实时访问量，但会同时引入Redis落库调度和恢复一致性，使第一轮难以明确Kafka本身解决了什么。

第一版也不增加消息去重表。精确去重会把每条点击重新变成数据库写入，不适合近似热度统计。订单、余额、库存不能复用此一致性边界。

## 基础设施

在项目根目录 `compose.local.yml` 增加单节点Kafka：

- 镜像固定为官方JVM镜像 `apache/kafka:4.3.1`，不使用 `latest`。
- 本地端口 `9092`。
- 使用Kafka 4的KRaft模式，不增加ZooKeeper。
- 使用独立Docker volume保存日志数据。
- 增加健康检查；`novel-front` 本地启动不依赖Docker Compose自动拉起。

版本选择依据是Apache Kafka官方发布页与官方Docker说明。实现时使用仓库中明确的固定版本，保证以后可以复现实验。

创建两个Topic：

| Topic | 分区 | 副本 | 用途 |
|---|---:|---:|---|
| `novel-book-visit-v1` | 3 | 1 | 正常点击事件 |
| `novel-book-visit-dlt` | 3 | 1 | 重试耗尽的事件 |

单节点本地学习环境只能使用1副本，不等同于生产高可用。生产环境至少需要多Broker和合适副本数，本阶段不伪装成生产集群。

## 事件契约

事件类型 `BookVisitEvent`：

```json
{
  "eventId": "3c118985-4252-4b2c-b307-a0a9ab73558a",
  "bookId": 2055879962859147264,
  "delta": 1,
  "occurredAt": "2026-09-02T12:00:00Z",
  "version": 1
}
```

约束：

- `eventId` 使用UUID，用于日志追踪和未来升级去重，不在第一版创建去重表。
- `bookId` 必须为正数，同时作为Kafka消息Key。
- `delta` 第一版固定为1；消费者仍按数值求和，便于测试和以后扩展。
- `occurredAt` 使用UTC时间。
- `version` 固定为1，为将来的消息兼容留下边界。

使用 `bookId` 作为Key可以让同一本小说稳定进入同一分区，并保留该小说事件的分区内顺序。聚合正确性不依赖全局顺序。

## 生产端

`BookController.addVisitCount` 保持现有URL和返回结构，避免修改页面JavaScript。Controller调用独立的 `BookVisitEventPublisher`，不再直接调用同步数据库计数方法。

生产者配置：

- `acks=all`。
- 开启Kafka生产者幂等能力，减少生产者重试造成的重复。
- 使用JSON值序列化和Long类型Key序列化。
- 发送采用异步Future回调，HTTP线程不等待MySQL，也不无限等待Broker。
- 发送成功和失败分别记录Micrometer计数器，失败日志包含 `eventId` 与 `bookId`。

Kafka不可用时，不同步回退MySQL。点击统计是非关键功能，宁可丢失少量点击，也不能在Kafka故障时把全部高峰请求重新打向数据库。接口保持成功响应，章节阅读不被计数失败阻断；运维通过失败指标发现问题。

## 消费端与数据库事务

消费者使用Spring Kafka批量监听，一次最多拉取500条：

1. 校验并反序列化一批消息。
2. 使用 `Map<Long, Long>` 按 `bookId` 汇总 `delta`。
3. 在一个数据库事务内，对每个不同 `bookId` 执行一次原子增量UPDATE。
4. 整批数据库事务成功后，才允许提交Kafka消费进度。
5. 事务失败则整批回滚，Kafka稍后重新投递。

原MyBatis SQL改为参数绑定：

```sql
UPDATE book
SET visit_count = visit_count + #{visitCount}
WHERE id = #{bookId}
```

不能继续使用 `${visitCount}` 文本替换。尽管当前参数是内部整数，参数绑定的边界更安全，也便于代码审查。

消费语义为“至少一次”。如果数据库已提交但进程在Kafka进度提交前崩溃，该批消息可能再次消费，产生少量重复计数。此误差已被业务边界接受，并必须在学习文档和面试说明中明确，不能宣称严格Exactly Once。

## 重试与死信

- 消费失败使用有限次数、固定退避的重试，不进行无限快速重试。
- 重试耗尽后将原消息及异常信息发布到 `novel-book-visit-dlt`。
- 坏消息进入死信后允许正常分区继续前进，避免单条消息永久堵塞。
- DLT第一版只提供查看、计数和人工重新投递说明，不自动无限回放。
- MySQL短暂不可用时，正常Topic保留积压；消费者恢复速度受批量大小和并发限制，不能无上限冲击刚恢复的数据库。

批量数据库操作必须有事务，避免一个批次只更新一部分后重试，造成正常异常路径下的系统性重复。

## 可观测性

通过现有Actuator/Micrometer暴露：

- 生产发送成功数与失败数。
- 消费批次数和事件数。
- 每批原始消息数与聚合后不同 `bookId` 数。
- 数据库更新成功数。
- 消费重试数和DLT数量。
- Kafka客户端已有的生产、消费和Lag指标。

日志只记录批次摘要和失败上下文，不为每一次正常点击打印INFO日志，避免日志本身成为高并发瓶颈。

## 测试设计

### 单元测试

- Publisher生成完整事件，并使用 `bookId` 作为Key发送。
- 聚合器输入A、A、B、A，输出A=3、B=1。
- 空批次不访问数据库。
- 非法 `bookId`、`delta` 和反序列化失败走受控异常路径。
- Controller不再调用同步数据库计数。
- 消费事务成功和失败行为分别覆盖。

### Docker集成测试

- Kafka、MySQL、Redis健康后发送1000条事件。
- 健康状态下所有被Kafka确认的事件最终反映到数据库增量。
- 同一热门小说的数据库UPDATE次数相较事件数下降至少90%。
- 消费完成后正常Topic无持续Lag。

### 故障测试

- 停止MySQL，继续发送事件；确认Kafka产生积压且消费进度不错误前进。
- 恢复MySQL；确认积压继续消费并最终清空。
- 停止Kafka；确认章节正文页面仍能打开，计数发送失败指标增加，并且没有同步MySQL回退洪峰。
- 构造坏消息；确认有限重试后进入DLT，后续正常消息仍能处理。

### JMeter前后对比

为 `POST /book/addVisitCount` 建立独立JMeter计划。同步版本和Kafka版本必须使用同一台电脑、同一本小说、相同线程阶梯、持续时间和JVM参数。

记录：

- 请求吞吐、P95、P99、错误率。
- Kafka成功发送数、失败数和最大Lag。
- MySQL实际UPDATE次数。
- 测试开始与最终稳定后的 `visit_count` 差值。
- Kafka版本每批事件数与聚合后SQL数。

验收目标：

| 指标 | 目标 |
|---|---|
| 点击接口错误率 | `< 0.1%` |
| Kafka健康时最终计数 | 与Kafka确认成功事件数一致 |
| 热门小说UPDATE次数 | 相较同步版本减少 `>= 90%` |
| 接口P99 | 在相同负载下明显低于同步版本 |
| MySQL短暂故障 | 消息积压，恢复后继续消费 |
| Kafka故障 | 不阻断章节正文，不回退形成MySQL洪峰 |

压测前记录目标书的初始点击量，测试后等待Lag归零再记录最终值。压测报告必须区分“HTTP已接受请求数”“Kafka确认成功数”和“最终数据库增量”，不能把三者混为一谈。

## 实施边界与产物

预期产物：

- `compose.local.yml` 中的Kafka服务和数据卷。
- Spring Kafka依赖与本地配置。
- `BookVisitEvent`、Publisher、批量Consumer、Topic与错误处理配置。
- 参数绑定的访问量增量Mapper。
- 单元与集成测试。
- 独立点击量JMeter计划、运行脚本和前后对比报告。
- 更新 `docs/learning/novel-plus-evolution-guide.md`。

非目标：

- 不拆微服务。
- 不引入ZooKeeper。
- 不增加Redis点击计数层。
- 不保证金融级Exactly Once。
- 不把阅读历史、收藏、搜索行为同时塞进第一阶段。
- 不在本阶段实现推荐算法。

## 官方参考

- [Apache Kafka Downloads](https://kafka.apache.org/community/downloads/)：确认固定镜像版本 `apache/kafka:4.3.1`。
- [Apache Kafka Docker Quickstart](https://kafka.apache.org/quickstart/)：官方JVM Docker镜像和本地9092启动方式。
