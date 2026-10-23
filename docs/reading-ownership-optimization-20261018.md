# 阅读归属校验优化与复测

宿主机复测 `reading-fix-20261018140255925` 已完成，并通过原定小范围性能回归阈值。原始 CSV、SQL 快照和监控已离线对账，最终结果见 [阅读归属校验优化验收结果](reading-ownership-result-20261018.md)。以下保留历史问题与准备过程，不将旧测量混入新结果。

旧证据保留于 `performance/correctness/artifacts/deploy-20261017233540699`，最终功能验收引用位于 `deploy-20261018132243336`。

|旧测量版本|P95 均值 [范围] ms|P99 均值 [范围] ms|业务成功|
|---|---|---|---|
|baseline|24.4421 [19.4642,28.0666]|28.3582 [19.8449,34.0771]|180/180|
|修复前 correctness|37.0929 [31.3865,45.2319]|41.1383 [32.3327,50.3048]|180/180|

旧轮次的 SQL show 关闭，没有逐窗物理 SQL 或请求分段耗时；CPU/Heap/GC 为轮后快照，GC 累计包含启动和预热。因此确认存在退化，但不能从这些记录量化数据库、网络、模板或 GC 各自贡献。

## 已确认的重复调用与最小修改

阅读页原链路：线程池任务 → `BookService.findBookIndex(bookId,chapterId)`（带双 ID 条件）→ 书籍查询 → DB/File 正文 Service 再执行 `selectByPrimaryKey(chapterId)` 校验同一归属 → 正文缓存/文件 → 导航 SQL → 原 VIP 判断 → Redis 页面登记 → 原模板。

重复 Mapper 调用已经由红绿回归复现：未优化时验证“一次 Mapper 调用”失败，实际两次。它是否解释全部历史 P95 差异，必须通过下述物理计数与当前三版本对照判定。

现在第一次查询产生只能由服务内部创建的 `BookService.OwnedChapter`，携带防御性复制的章节快照。页面与章节摘要入口将该结果传给正文 Service，复用一次归属判定；客户端不能传入此凭证。原双 ID Service 方法保留独立数据库校验。正文 SQL、缓存 Key、VIP 判断、导航、线程池和连接池未调整。凭证只在请求内使用，不缓存；不声称消除并发修改元数据的一般时间窗口。

修改涉及前台的 `BookService`、`BookContentService`、DB/File 正文实现、`PageController`、`BookController`。相关 Java 回归 25 项通过：错误书籍/章节、缓存无交互、不可篡改的归属与 VIP 快照、页面及摘要只查一次、原 Service 直接调用仍校验、VIP 购买提示与已购买正文。

## 宿主机复测入口

```powershell
& 'C:\Users\26635\Documents\Codex\2026-10-16\novel-plus-redis-a-b-java\correctness-src\performance\correctness\Run-ReadingOptimization.ps1'
```

复用现有 Docker、数据库、私有 Redis、Kafka Topic 和 Compose。仅构建 front 镜像并切换同一个 correctness front；admin、依赖、卷和数据不重建、不删除。每版切换后核对环境变量摘要、内存/CPU/挂载/端口等配置完全一致。不会运行部署恢复入口、MySQL DDL 或完整 88 项测试。

比较 baseline、修复前镜像、优化镜像各三轮，顺序按三个位置均衡轮换；每轮保持并发 2、原四章循环、10 秒独立预热、60 个请求、同一正文哈希与书章归属校验，共 540 个测量请求。三个镜像各另有 8 个 SQL 校准请求，其延迟不混入测量。当前 admin 同时运行，故重新测三个版本，不能拿新旧不同时间的 P95 直接相减作为优化收益。

校准仅临时启用独立 MySQL General Log，保存当前账号的 Query/Execute 实际执行，核对摘要和完整扫描的 prepared instances，确定两种来源是否重叠。随后恢复 General Log OFF 和原 log_output；不清表、不重置计数。正式窗口使用 Performance Schema，检查 MySQL 身份、计数丢失、连接关闭、全局 COM_STMT_EXECUTE 与所有实例执行增量一致，避免遗漏或双计。异常会保留证据并停止；校准日志设置在 finally 恢复。

保留每轮 CSV、SQL 原始快照/分类/数据库语句累计时间、前后 Prometheus、进程 CPU 时间/GC 增量、Heap 和 Hikari 快照及容器 stats。HTTP 指标记录完整请求耗时；SQL 时间仅为服务器语句时间，不能将两者差值直接称为模板耗时。未观测到的指标记为缺失。P99 每轮只有 60 个样本，不能据此推断稳定的生产尾延迟。

输出在新目录 `performance/correctness/artifacts/reading-fix-<时间>`，含 `summary.json`、`rounds.json`、`阅读性能修复复测.md`。分别展示三轮均值、范围、配对差异和物理 SELECT。原性能阈值保持“P95 均值增加超过 25% 且超过 10ms”。若仍触发则为 REVIEW_REQUIRED，保留优化版用于定位，不再自动尝试其他优化；发生执行/业务错误时仅恢复先前 front 镜像并保留原始失败证据。旧验收与三项冻结实验不覆盖。

离线准备验证：25 项 Java 回归、38 项 SQL/指标/日志恢复检查、12 项既有隔离守卫检查通过。真实 SQL 次数及修复后 P95/P99 必须以宿主机生成的新结果为准。

## SQL 守卫误报修复

`reading-fix-20261018135654925` 停在第一组校准，正式测量轮数为 0。原始证据证实 8 个业务成功请求对应 32 次物理 SELECT，General Log 与全实例 COM_STMT_EXECUTE 增量一致，四类查询各 8 次。另有 schema digest 的一次 `SET character_set_results = ?` 被误判为应用写入；摘要只有 schema 归属，不能确定执行账号。现仅豁免该会话设置并单列次数和来源，不计入物理 SELECT；任意其他 SET、真实 DML/DDL 和未知语句继续阻断。异常前先保存 SQL delta，避免丢失直接诊断依据。

新增 `Test-ReadingSqlRecordedEvidence.ps1` 直接读取该失败目录做离线回放：修复前重现异常，修复后 35 项真实快照/SQL 守卫验证通过（含 prepared、digest、General Log 三种来源的写入拒绝）。没有重跑 Java、Docker 或数据库请求；失败原始证据未修改。恢复记录显示旧 front 镜像已恢复，仍用原入口重试。
