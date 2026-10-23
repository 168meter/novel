# Novel-Plus 正确性修复与回归

本文记录 2026-10-17 的开发回归阶段。后续真实 MySQL、隔离部署、后台权限及指标采集已完成；最终仍有阅读性能复核项，详见 [部署验收汇总](deployment-acceptance-20261017.md)。下文“未部署/未执行 MySQL DDL”等为该开发阶段的历史边界，不代表最终状态。

开发目录：`C:\Users\26635\Documents\Codex\2026-10-16\novel-plus-redis-a-b-java\correctness-src`。
开发分支：`correctness-hardening-20261017`，基线 `c0925ed1d041d66550e42396cbac8cd1cf84c1bd`；修改尚未提交。
冻结的 `experiment-src` 及三项实验结果没有改写；本轮没有执行 Docker、部署、数据库迁移或性能压测。

## 修改与原因

| 文件（相对上述开发目录） | 原因与修复 |
| --- | --- |
| `novel-front/.../controller/BookController.java` | 章节摘要曾按 chapterId 查元数据，再把客户端 bookId 交给正文缓存；现先验证书章归属，缺失正文/存储类型返回参数错误。 |
| `novel-front/.../service/impl/DbBookContentServiceImpl.java`、`FileBookContentServiceImpl.java` | 在缓存或文件读取前，用章节元数据验证 bookId/chapterId；不匹配不触碰缓存或正文。文件流使用自动关闭。 |
| `novel-front/.../messaging/BookVisitEventPublisher.java` | Kafka 原生异常不足以覆盖 Spring Kafka 同步发送异常；仅在发送与回调注册范围捕获 RuntimeException，计失败并保留访问量上报的尽力而为语义。非法事件参数仍失败。 |
| `novel-front/.../controller/page/PageController.java` | 首页任务提交拒绝曾直接冒泡；现提交及等待中的拒绝统一返回 HTTP 503，单独记录 `novel.home.request.rejected`，不混入章节拒绝指标。 |
| `novel-front/.../service/impl/BookServiceImpl.java` | 评论可绑定客户端主键、作者、审核状态、计数；现覆盖为服务端值，并验证登录、正文长度和父对象。依赖既有 UNIQUE(book_id,create_user_id) 裁决并发重复，映射为 HAS_COMMENTS；计数和插入在同一事务，失败回滚。 |
| `novel-front/.../service/impl/LikeServiceImpl.java` | 原来可给不存在的目标或无效用户生成投票键；现写前校验。Redis 返回 null 不再伪装成功。保留现有 Lua 原子切换，无新锁。 |
| `novel-front/.../mapper/FrontBookMapper.java`、`FrontBookCommentMapper.java` | 计数更新返回受影响行数，用于确认父对象存在。 |
| `novel-front/src/main/resources/mybatis/mapping/{BookMapper,BookCommentMapper,BookCommentReplyMapper}.xml` | 可空计数改用 COALESCE；分页按 create_time DESC,id DESC，消除相同时间的排序不确定性。 |
| `novel-admin/.../dao/BookCommentDao.java`、`service/impl/BookCommentServiceImpl.java` | 原删除遗漏回复和书籍计数；现事务内按 book→comment 加行锁，删除回复、主评论，成功一次才减计数，批量复用相同路径。后台新增同步计数；编辑不允许改归属或回复计数，避免把并发回复的新计数覆盖为旧快照；仅含受保护字段的编辑不执行空 UPDATE。 |
| `doc/sql/20261017_comment_indexes.sql` | 为评论按书籍/用户及回复按评论/用户分页提供四个组合索引；仅交付 DDL，尚未执行。原唯一约束不变。 |
| `performance/correctness/Read-Metrics.ps1`、`Test-Metrics.ps1` | 修正 Invoke-WebRequest.Content 为 byte[] 时的 UTF-8 解码；缺失 measurements 必须报不可用，不填零。 |
| `performance/correctness/Run-HostChecks.ps1`、`.gitignore` | 可复现离线回归及可选真实 Redis 测试入口；按本轮测试名保存报告，避免混入旧的失败 XML。无 Docker 操作。 |

生产 Java 路径中的 `...` 为 `src/main/java/com/java2nb/novel`。

新增测试：

```text
novel-front/src/test/java/com/java2nb/novel/controller/BookControllerChapterOwnershipTest.java
novel-front/src/test/java/com/java2nb/novel/controller/BookControllerCommentHttpTest.java
novel-front/src/test/java/com/java2nb/novel/service/impl/CommentCorrectnessTest.java
novel-front/src/test/java/com/java2nb/novel/service/impl/FileChapterOwnershipTest.java
novel-front/src/test/java/com/java2nb/novel/service/impl/LikeConcurrencyRedisIT.java
novel-admin/src/test/java/com/java2nb/novel/service/CommentDeletionTest.java
```

更新既有测试：`PageControllerHttpSemanticsTest`、`BookVisitEventPublisherTest`、`DbBookContentServiceImplTest`、`LikeServiceImplTest`。

## 已执行验证

最终证据：`performance/correctness/artifacts/20261017-221426/`，由宿主机执行 `Run-HostChecks.ps1 -IncludeRedis` 产生，含 front/admin 日志及所选用例的 Surefire XML/TXT；已读取核对。此前沙箱回归证据 `20261017-213153/` 保留。

| 验证 | 实际结果 |
| --- | --- |
| 前台所选回归 | 80 项（含真实 Redis 3 项），失败 0、错误 0、跳过 0。 |
| 后台评论回归 | 8 项，失败 0、错误 0、跳过 0。 |
| PowerShell 指标解析 | 字符串、UTF-8 byte[]、缺失指标拒绝三个用例通过。 |
| 评论并发与事务 | 真实 MyBatis Mapper + Spring 事务代理 + H2 MySQL 模式：12 个同用户请求仅一行/一次计数；12 个不同用户准确计数；12 个回复准确计数；故障插入回滚；重复删除和批量删除不重复减计数；后台编辑不覆盖期间已提交的新回复数。 |
| 权限与归属 | 六个匿名评论/投票写入口拒绝伪造作者；服务层覆盖客户端作者/审核/计数；后台删除与批量删除权限注解核对；错误书章不进入正文缓存/文件读取。 |
| 缓存与响应回归 | 已有缓存锁、过期、失败降级、提交后失效及章节 HTTP 测试纳入上述 77 项；首页拒绝返回 503，Kafka Spring 同步异常计失败且不传播。 |
| 真实 Redis 并发 | 宿主机补跑 3 项全部通过：连续点赞 1→取消 0；同用户并发 24 次结果 0、再 25 次结果 1；20 个不同用户并发点赞为 20、全部再次切换后为 0。测试仅使用独立 Redis 的随机目标键，目标存在查询使用 Mock，没有新增数据库评论。此前沙箱连接失败不计为通过，也不计作本次业务失败。 |

本次宿主机 Surefire 汇总为 88 项 Java 测试、失败 0、错误 0、跳过 0。PowerShell 指标解析的 3 个用例单独统计，不混入 Java 测试数量。

修复前已用失败用例复现书章摘要、Spring Kafka 异常、首页拒绝、评论并发/输入、后台删除及编辑覆盖等问题，再运行通过；不是仅按代码推测。

实验三最后成功运行的 `metric-novel.reading.heartbeat-result-accepted-missing-measurements.json` 保存的是 `123 34 110 ...` 字节数字。还原后为合法 JSON，包含 COUNT=2.0。该值是当时保存的计数，不是本轮增量；至少这条“缺失”已确认是采集解码问题，不能据此断言全部在线指标均可用。

## 仍存在的边界

- 本轮评论并发使用 H2，未在真实 InnoDB 上重跑锁等待、死锁和跨进程竞争；后台权限检查含注解与匿名入口回归，没有启动后台 Shiro 全链路。
- 点赞接口是 toggle：同用户两次会取消，重复重试按奇偶改变状态，不是幂等的“设为已赞”。赞/踩使用独立集合，未擅自定义互斥产品规则。删除评论不清理历史 Redis 投票键，且数据库存在检查与 Redis 写之间仍有删除竞态。
- 当前列表未按 audit_status 过滤。待审核/审核失败的可见性属于尚未明确的产品策略，本轮未自行改变；也没有修复历史孤儿记录或历史错误计数。
- 四个索引 DDL 尚未在 MySQL 执行，未宣称分页查询计划或性能改善。
- 正文服务增加权威归属查询；三项冻结实验的性能数字不能直接宣称为本开发分支修复后的性能。
- 未部署新代码，也未更改数据库、Redis 配置、线程池、Hikari 参数或实验数据卷。

宿主机 PowerShell 7 已执行以下真实 Redis 回归入口；需要复核时可使用相同命令（仅独立 Redis 的随机测试键，清理前确认该键最初不存在；不调用 Docker、不部署、不改数据库）：

```powershell
& 'C:\Users\26635\Documents\Codex\2026-10-16\novel-plus-redis-a-b-java\correctness-src\performance\correctness\Run-HostChecks.ps1' -IncludeRedis
```

三项分别验证：连续点赞/取消、同用户 24/25 次并发切换的奇偶结果、20 个不同用户并发点赞及取消后的精确计数。
