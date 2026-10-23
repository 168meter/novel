# correctness-src 真实数据库与部署验收

最终状态（2026-10-18 13:23:16，按验收文件）：**功能与隔离部署验收通过；总体 REVIEW_REQUIRED，阅读性能门槛未通过**。不标为完整性能验收通过。

最终证据：`performance/correctness/artifacts/deploy-20261018132243336/`；数据库原始证据关联 `deploy-20261017231415443`，前台及阅读原始证据关联 `deploy-20261017233540699`。本次仅分析已保存证据、更新文档，没有重新运行测试。

| 核验项 | 实际结果 |
| --- | --- |
| 真实 MySQL 8 / InnoDB | 前台 11 项、后台 8 项，失败/错误/跳过均 0；覆盖重复/不同用户并发评论、回复计数、删除/重复删除/批量删除及事务回滚。 |
| 锁等待 | 捕获到按测试 schema 过滤的真实阻塞记录；受控写等待耗时 1515ms。窗口内服务器全局 row_lock_waits 0→28、累计等待时间 0→1794ms、deadlocks 0→0；全局值不等于每个测试独占，不能据此宣称无死锁风险。 |
| 前台 HTTP | 匿名/伪造身份拒绝、服务端归属/审核/计数覆盖、评论回复计数、点赞 1→0、错误书章页面 404 与摘要拒绝通过。 |
| Kafka 功能链路 | HTTP 接受 + Producer success 增量 1 + 数据库 visit_count 增量 1 + 最终 committed lag=0；不把该单请求结果当批聚合性能结论。 |
| 后台部署测试 | `DeployedAdminIT` 1 项，失败/错误/跳过均 0；真实登录、后台首页、无权限删除拒绝（HTTP 200/business code 403）、新增/编辑受保护字段、删除/重复删除及批量删除的最终数据和计数通过。匿名删除实际为 302。 |
| Actuator | UTF-8 JSON measurements 与 Prometheus 可采集。最终快照 heap 合计 245.75MiB，Hikari active/pending=0/0，GC pause 2 次、累计 9ms；均为重启后的单点/进程累计值，不代表负载峰值。 |
| 隔离与保留 | 验收结束时 front/admin/私有 Redis 均 Running、无 OOM，端口分别 127.0.0.1:28183/28184、28180、26381。原实验源库所选计数前后证据哈希一致；未删除数据库、卷或 Topic。 |

四个组合索引已在验证 schema 执行，并核对添加前后查询结果文件 SHA256 一致。下面是本次固定样本的 EXPLAIN ANALYZE 中 t1 读取节点实际 rows，不是整条语句的物理总扫描数，也不是吞吐提升百分比：

| 分页条件 | 添加后使用的索引 | 读取节点实际 rows，前→后 | filesort，前→后 |
| --- | --- | --- | --- |
| 评论按书籍 | idx_comment_book_page | 60→5 | true→false |
| 评论按用户 | idx_comment_user_page | 1200→5 | true→false |
| 回复按评论 | idx_reply_comment_page | 2400→2 | true→false |
| 回复按用户 | idx_reply_user_page | 2400→5 | true→false |

**尚未通过/剩余边界：**

- 阅读回归 c2、每版三轮各 60 请求，共 360 次业务成功、0 失败；P95 均值 baseline 24.4421ms、candidate 37.0929ms，增加 12.6508ms（51.76%），超过既定 25% 且 10ms 门槛。不能声称没有明显退化，不能借用冻结 Redis A/B 数字描述本版本。新增正文服务归属检查包含额外章节元数据查询；其对 12.65ms 差异的具体贡献尚无分段计时证明。
- 评论/回复 `audit_status=0/1/2` 均可见，计数包含所有状态；审核可见性策略未完成产品确认。
- 点赞仍是非幂等 toggle，重复请求按奇偶改变状态；删除与 Redis 写仍存在跨存储竞态，未新增分布式锁或改接口语义。
- 验证只覆盖隔离环境的有限并发和已列入口。后台验证码值由测试从专用 Redis 会话读取；前台使用签名测试令牌，不声称覆盖验证码识别、密码登录或生产容量。
- 10 月 18 日依赖恢复发生在阅读测量之后；本次后台验证没有重测阅读，不假定重启后的 Redis 保留历史预热/会话状态。

验收结束时访问入口：前台 `http://127.0.0.1:28183`，后台 `http://127.0.0.1:28180`。独立开发分支仍为 `correctness-hardening-20261017`。现有状态已完成后台验收，无需再次执行带固定测试账号的续跑入口。

以下保留原执行方案和故障修复记录，描述各阶段当时状态。

执行范围：

- 复用 `novel-cache-ab-20261016` 的 MySQL 8、Kafka、Mailpit 和独立网络。MySQL 原 `novel_ab` 仅用于身份核验、只读快照及前后计数对照；所有 DDL 和业务写入位于新建 `cv_app_<运行号>` 或 `cv_it_<运行号>_*` schema。账号仅获得这些 schema 的权限。
- 原 Redis 不写入。为避免后台 Shiro 固定键前缀和前台缓存跨版本污染，新项目 `novel-correctness-20261017` 仅增加前台、后台及专用验证 Redis，端口分别为 28183/28184、28180、26381，全部绑定 127.0.0.1。Redis 无持久化配置；镜像可能自动创建匿名 `/data` 卷，本入口不清理它。
- 前台限额 896 MiB、堆 256–512 MiB，与原实验前台一致；后台限额 640 MiB、堆 128–384 MiB；验证 Redis 限额 128 MiB。启动前检查 Docker 当前使用量及额外 1664 MiB + 512 MiB 余量；不自行调整宿主机资源。该多应用验收不能用于证明 2C4G 生产容量。
- MySQL 测试复用已有评论用例，6 个工作线程处理 12 个同用户/不同用户评论请求及 12 个回复请求；增加真实 InnoDB 受控锁等待。每个用例保留独立 schema、最终行数据、Surefire，采集 `data_lock_waits`、InnoDB 状态、锁等待/死锁计数；全局计数需结合按测试 schema 过滤的等待记录解读。
- 从合成实验数据创建独立应用快照，增加 1200 条评论和 2400 条回复作为组合索引的固定样本。四条真实 Mapper 等价分页 SQL 保存原始结果、EXPLAIN JSON 及 EXPLAIN ANALYZE；添加四个索引后重跑并核对结果一致。是否合理、是否出现 filesort 和扫描减少，应据执行结果判断，不因建索引成功宣称加速。
- 本机 Maven 构建两个模块，再创建只包含本次 JAR 的专用镜像；保留 JAR SHA256、镜像 ID、开发差异和配置的脱敏快照。构建跳过单元测试，仅运行本次 MySQL 与部署验收用例，不重复全部 88 项。
- 已执行的离线打包因未缓存 `maven-jar-plugin:3.4.1` 失败，不能报告构建成功。宿主机入口的 package 阶段允许按现有 Maven 配置下载固定依赖，并编译测试而不运行全部测试；需要宿主机网络。后续选定的验收测试仍离线执行，不修改全局 Maven 配置。
- 阅读回归在相同应用快照、资源限制、Redis 和访问顺序下交替运行旧镜像/新镜像三对，每版每轮预热 10 秒后测量 60 次、并发 2；每条测量请求验证 HTTP、书籍 ID、章节 ID 和正文 SHA256。单独保存 CSV、分位数、资源/GC/连接池指标；General Log 必须为关闭状态。本轮不改变全局采集配置，不混用原 Redis A/B 数字。均值 P95 同时恶化超过 25% 和 10ms 时标记 REVIEW_REQUIRED；小样本不能排除高并发退化。
- 新版前台/后台使用十分表 ShardingSphere 配置；接口验证匿名/签名伪造、服务器作者字段、评论/回复、点赞 1→0、错配书章。Kafka 使用本轮独立 Topic/Group，验证发送确认、数据库访问增量、最终 committed lag 及 DLT。最终保留新版应用运行，不替换原前台。
- 后台使用真实验证码生成、登录、Shiro 权限和 HTTP 增删改路径；测试程序仅从专用 Redis 的对应会话读取验证码值，再提交正常登录请求，不增加验证码旁路接口。该测试不验证验证码识别能力。前台使用仅本次配置的签名测试令牌及真实数据库 token_version 校验，不声称覆盖密码登录。
- 后台仅本次快照中的验证账号为 `cvuser1`（有评论权限）与 `cvuser2`（无评论权限）；随机密码仅保存在 Git 忽略的 `performance/correctness/runtime/deploy-<运行号>/verify.env` 的 `CV_ADMIN_TEST_PASSWORD` 字段，不写入验收报告或接口证据。该文件还含验证数据库凭据，不应作为普通结果共享。
- Actuator 验证 UTF-8 JSON、measurements、JVM/Hikari/正文缓存指标与 Prometheus。GC pause 若未发生导致 404，明确记为未观察到，不填零。

当前业务行为已核对，并由验收脚本补留运行证据：

- `book_detail.html`、`book_comment.html` 的赞/踩 AJAX 分别调用四个 toggle 接口；回复页面调用回复 toggle。未配置请求幂等键、点击合并或显式目标状态，重复点击/重试会反向切换，异步响应乱序还可能暂时显示旧计数。本轮不改接口语义。
- 评论和回复新建默认 `audit_status=0`；原表含义是 0 待审、1 通过、2 不通过。前台列表 SQL 没有状态过滤，因此这三类当前均会展示，计数也未按审核通过过滤。脚本将验证列表实际行为。此项仍需产品确定审核策略，不能宣称已实现审核可见性闭环。

结果位于 `performance/correctness/artifacts/deploy-<运行号>/`：

- `acceptance.json`、`部署验收结果.md`：通过、失败、边界及最终部署状态。
- `real-mysql-*.log`、Surefire、`mysql-front-*`、`mysql-admin-*`、`innodb-lock-waits.jsonl`、`mysql-*.txt`：事务、最终数据、等待和死锁证据。未捕获到等待时不会伪造记录。
- `explain-before/after-*`、`query-result-*`、`indexes-after.tsv`：索引前后查询与执行计划。
- `deployed-*-http.*`、`audit-visibility-list.json`、`reply-audit-visibility-list.json`、Kafka 及指标原文：部署业务验证。
- `reading-*.csv`、`reading-rounds.json`、`reading-summary.json`：本次版本对照，独立于前三项冻结实验。

脚本不自动删除旧运行容器或覆盖旧运行的 `runtime/deploy-<运行号>/verify.env`。若已有本验证项目容器，则停止以保留失败证据；应按失败位置修复后受控续跑，不执行删除实验卷的恢复流程。

## 2026-10-17 首轮导入故障修复

`deploy-20261017225309077` 的 Maven 打包与前后台镜像构建已完成，但 SQL 阶段失败，尚未创建验证应用容器。保存的 dump 出现与 UTF-8 被 936 解码相同的乱码，例如 `COMMENT 'QQ或微信账号',` 变成 `COMMENT 'QQ鎴栧井淇¤处鍙?,`，SQL 引号已损坏。旧脚本没有保存 MySQL stderr，不能追溯该轮确切 SQL 错误号。

现已改为 .NET Process 的原始字节流导出/导入；文本查询也明确使用 UTF-8，并逐次保存原生 stderr 和异常调用栈。导入后以原冻结 manifest 校验 32 个章节的归属、免费属性、存储类型及正文 SHA256。失败收尾只采集已存在的本项目容器，避免不存在容器的干扰报错。另区分 BuildKit 构建摘要与 Docker inspect 返回的镜像 ID，避免最终镜像核验混用两者。

可复用该轮镜像重试，跳过 Maven 打包与 Docker Build：

```powershell
& 'C:\Users\26635\Documents\Codex\2026-10-16\novel-plus-redis-a-b-java\correctness-src\performance\correctness\Run-DeploymentAcceptance.ps1' -ReuseBuildFrom 'deploy-20261017225309077'
```

复用前核对保留的 JAR SHA256、镜像输入修改时间及镜像构建关系；不满足则拒绝复用。重试创建新的验证 schema、账号和运行证据，失败 schema、旧 dump、已有数据卷及 Topic 保留，不覆盖旧证据。此次没有修改业务 Java 或原实验脚本。

离线回归已通过：模拟 936 控制台时超过 1 MiB SQL 字节流往返哈希相同，中文引号/非 BMP 字符保留，原生非零退出码和中文 stderr 保留；不存在/其他项目容器不被 inspect；镜像标签变更被拒绝（Docker 查询为 Mock）。这些离线结果不等于真实 MySQL 导入或部署通过，仍以宿主机重试证据为准。

`deploy-20261017230630091` 在首个只读 SQL 身份核验之前因原生参数绑定失败停止，未执行本轮数据库写入或容器创建。本机 `Get-Command docker -CommandType Application` 同时返回 `docker.exe` 和无扩展名的 `docker`，导致 `.Source` 为数组，无法绑定到 `[string] Executable`。入口已明确选择一个 `docker.exe`，将完整路径保存至 `native-tool.json`；原重试命令不变。

新增回归先执行入口实际的命令解析语句，在修复前复现数组错误；修复后通过。SQL 文本、文件导入、快照导出、失败日志均经过与真实原生工具同类型的参数边界测试（Docker 操作为 Mock），并验证固定 context、非零退出码及 schema 拒绝规则。真实 UTF-8 子进程往返和 12 项隔离检查再次通过；未重新运行 Java 全量测试，真实部署仍待宿主机执行。

`deploy-20261017231116729` 在首个只读 SQL 核验处失败，数据库导入及容器创建尚未执行。进一步复现发现：PowerShell 会将三个非泛型异步 Task 的 `GetResult()` 返回的 `VoidTaskResult` 输出到管道，污染原生封装的单对象返回值；入口严格模式下因此无法读取 `ExitCode`。现已显式丢弃这三个内部返回值。原先子进程单测没有启用严格模式，调用链测试又替换了封装本体，未覆盖这一组合，这是原测试缺口。新增严格模式及返回数量断言，并在实际入口函数中运行真实子进程封装（仅将 Docker 端点替换为无副作用的 PowerShell 子进程）；修复前复现相同异常，修复后查询、文件导入、快照导出、日志采集、非零退出码及中文 stderr 全部通过。重试命令及镜像复用方式不变，真实部署验收仍待宿主机执行。

## Mapper 启动冲突及保留状态续跑

`deploy-20261017231415443` 的失败原因来自应用日志：`LikeServiceImpl` 构造器的 `BookCommentMapper comments` 同时匹配 `bookCommentMapper` 和派生的 `frontBookCommentMapper`；回复 Mapper 有相同歧义。已使用显式构造器和两个 `@Qualifier` 选择基础 Mapper，不改变查询或点赞语义。新增 Spring 容器装配回归，修复前复现同一个 `NoUniqueBeanDefinitionException`，修复后连同正文归属与点赞用例共 10 项通过。数据库用例的手工构造不会覆盖 Spring 装配，这是此前单测未发现启动错误的原因。

本轮原始证据还保留了受控写锁等待 1515ms、按测试 schema 采集的 `data_lock_waits`；测试后全局死锁计数为 0，须结合测试前值与服务器其他活动分析，不宣称永无死锁。四个索引前后查询结果一致，但尚未在此声明索引带来的性能收益。

`-ResumeFrom deploy-20261017231415443` 只接受 MySQL/索引检查已通过、未进入 HTTP 变更的启动失败检查点。它核对保存的凭据目标、数据库身份、未使用的 HTTP 评论样本、专用 Redis、容器项目和原依赖身份；只重新打包/构建 front，核对并复用 admin 镜像及 JAR，保留既有 schema、Redis、Topic 和全部卷。不再导入数据库、重跑 19 项数据库用例或重复建索引。续跑使用新证据目录和私有配置副本，关联旧数据库原始证据；旧单轮 baseline 仅作为失败轮保留，三对阅读回归重新独立采集。18 项离线续跑检查、12 项隔离检查及真实子进程 UTF-8 调用链回归均已通过；Docker 仍只由宿主机执行。旧的 `-ReuseBuildFrom` 不适合已改动的 Java 前台，脚本会拒绝复用旧 front。

## 后台身份参数修复及就地收尾

`deploy-20261017233540699` 已复用原 schema `cv_app_20261017231415443`，但脚本将新的证据目录编号传给 `DeployedAdminIT` 的 `CORRECTNESS_RUN_ID`。测试在第一条只读 SQL 正确识别不符，实际保存的数据库标记并无错误。现已将该参数绑定到保存的 `identityRun`，新建数据库时才使用新运行号；数据库身份断言保持严格，未修改数据库标记或放宽测试。

该检查点的三对阅读回归共 360 次测量请求全部通过业务校验；baseline 三轮 P95 为 28.0666/25.7954/19.4642ms，candidate 为 45.2319/34.6602/31.3865ms。均值 24.4421→37.0929ms，增加 12.6508ms（51.76%），触发原先设定的复核阈值。小样本含客户端开销，不能据此推断高并发容量；也不能声称本次版本没有明显退化。原 CSV、指标和 REVIEW 状态保留，不重复测量来覆盖该结论。

新检查点续跑只接受上述特定的后台前置身份断言失败，并核对三个运行容器、保存的镜像/JAR、原始 schema 身份，以及后台测试用户、角色、权限记录尚不存在。它沿用现有进程和配置，只执行后台 HTTP 验收及最终指标/隔离核验，不构建镜像、不创建/重建容器、不重跑前台冒烟、阅读对照或数据库测试。已完成阶段关联到原始证据目录。身份参数回归、30 项续跑离线检查（包括实际执行收尾分支）、严格模式子进程和隔离回归通过；真实后台结果仍待宿主机执行。

若其余功能及部署检查均通过，最终状态保持 **REVIEW_REQUIRED**，明确报告测得的阅读退化，不标为 COMPLETE；该状态不会再作为一般部署异常抛出。真实测试失败仍保留 FAILED 并报错。本次仅修改验收脚本与回归，不修改业务 Java 或运行配置。

## 2026-10-18 依赖重启后的受控恢复

`deploy-20261018131217699` 在首条 SQL 前失败，原始 stderr 说明实验 MySQL 未运行；`dependency-state-20261018-131636-919` 随后证实五个原实验容器均为相同 ID/镜像，只改变启动时间，四个基础依赖已 healthy。MySQL 日志显示昨晚收到关闭信号，重新启动时复用原 InnoDB 数据目录，无 OOM；验证前台、后台先于依赖启动而退出，私有 Redis 已重启且未配置持久化。

显式参数 `-RecoverFromDependencyState dependency-state-20261018-131636-919` 仅适用于后台收尾检查点：它核对八个容器的取证归属、数据库身份、原容器 ID/镜像、启动时间与现场证据、JAR/资源限制和基础依赖健康状态，再按检查过的容器 ID 仅 start 两个 exited(1) 的 correctness 应用。已运行的应用跳过，不操作原实验服务，不执行 restart/recreate/build 或卷操作；任一目标替换、OOM、依赖不健康或取证后再次重启都会停止。原依赖启动时间变化另存 `dependency-restarts.json`，应用恢复记录保存至 `application-recovery.json`；本次运行内仍禁止依赖再发生变动。

恢复不会重复阅读测量，旧的 P95 退化和 REVIEW_REQUIRED 状态保留。新会话/缓存不能视为前晚的预热状态，边界已写入验收结果。新增恢复逻辑离线回归、33 项续跑检查、身份及隔离回归通过；所有 Docker 启动、SQL 和 HTTP 操作仍由用户在宿主机执行，当前不能宣称后台实机验收完成。
