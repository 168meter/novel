# Kafka 访问统计最终集成验收

宿主机 PowerShell 7 单入口：`./Run-KafkaFinalAcceptance.ps1`。
`-Offline` 只编译、运行 33 项原回归与 12 项测试保护检查，并验证实际打包后的 Java classpath；不调用 Docker。
没有宿主机 A/B/C 实测时只能报告 READY_TO_RUN。

## 范围与资源

- 固定既有 `novel-cache-ab-20261016` Kafka/MySQL 容器 ID、项目标签及网络。
- Broker 只提供 Docker 内网 `kafka:19092`。复用 G1 固定本地镜像中的 JRE，
  用 `--entrypoint java` 运行一个临时测试客户端，不运行镜像内的旧应用 JAR。
  禁止 pull；客户端 384 MiB、1 CPU，测试堆 192 MiB；不调整任何现有容器/JVM。
- 先只读检查资源、健康字段（允许无 Health 配置）、OOM/重启、Broker 安装版本；
  Windows 可用物理内存和提交余量均至少 1536 MiB，Docker VM MemAvailable 至少 768 MiB。
  Kafka/MySQL 的 Docker CLI working-set 占比必须低于 90%；不将它冒充原始 cgroup usage。
  这些仅是小型功能验收的安全预检，不解除 G2 阻塞或产生容量结论。
- 从受限 `verify.env` 读取 `cv_<identity>`，仅创建 `cv_it_<identity>_kv_<随机run>`。
  Topic/Group 使用 `cv-visit-<随机run>-*`。先确认全部不存在，写归属账本，再创建。

## A/B/C 与证据

| 场景 | 生产执行路径 | 必须满足的结果 |
|---|---|---|
| A | 实际批监听 → 聚合 → 事务 Writer → 完整生产 Mapper XML | 3 条合法消息，两个 book 分别 +2/+1；两次已提交 UPDATE；DLT 0；committed/end 均 3 |
| B | 同分区预置 A/B/C，再启动真实监听，必须记录首批确实含三条 | A/C 各 +1，B delta=0 单独 DLT；合法消息指标 2，无重复；committed/end 均 3 |
| C | 测试 MyBatis 拦截器在真实 UPDATE 后抛出临时 SQL 异常 | 按实际 bookId/delta 验证单条 UPDATE（可能 +2 或 +1），独立连接读仍为 0；恢复前 committed 不超过初始位置；释放后 +2/+1，无 DLT，committed/end 3 |

错误处理器直接使用本轮生产 `BookVisitKafkaConfig`，保留 5 秒持续重试；不改为测试专用重试/DLT 策略。
测试数据、故障注入及持久化账本只存在于测试代码与独立 schema。

每轮 `artifacts/kafka-final-<run>/` 保存：

- `summary.json`、Git 差异、`source-inputs.json`、`bundle-sha256.json`：当前源码/编译输入标识。
- `test-reports/`：本轮新生成的 45 项单测报告，不能替代真实 Broker 验收。
- `offline-classpath-probe.json` / `client-classpath-probe.json`：宿主机与容器实际加载类/Mapper 字节哈希对比。
- `versions.json`：真实 MySQL、Spring Kafka、clients、Broker cluster ID、Java；Broker 安装版本另见 summary 引用的原始 Docker 输出。
- `A/B/C-published.json`、`A/B/C-deliveries.json`、`A/B/C-result.json`：发送确认、实际批次、前后 DB/committed/end、指标。
- `A/B/C-dlt.json`：DLT end offset、实际记录、原始值与 Header Base64。
- `C-rollback.json`：事务内真实变更、回滚后值、恢复前 offset。
- `ownership.json`、`cleanup.json`、`cleanup-retry.json`：本轮归属与资源清理结果；原始进程日志全部保留。

## 有限执行及清理

单次 Java 验收上限 240 秒；每个轮询/数据库调用/管理请求另有有限超时。
正常或异常结束均清理本轮 Topic、Group、schema；外层先移除本轮带专属标签的客户端，
再执行独立 cleanup-only 路径（150 秒上限），不会重新发送 A/B/C。
Broker 不可达不会压制独立 schema 的清理；无法确认的清理状态为 UNVERIFIED/FAILED。
不删除历史 Topic/Group/证据/卷、不 reset offset、不停止共享服务；不使用旧即时回滚脚本。
失败请返回本轮最后一行与 exception.txt/summary.json，保留现场，不盲目重跑。

只有 A/B/C、清理、共享依赖稳定性、源码指纹一致性全部通过才输出 KAFKA_READY_TO_FREEZE。
数据库提交和 Kafka offset 提交仍非原子；本验收不证明崩溃/重平衡窗口内 exactly-once。
