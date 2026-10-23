# Novel-Plus 阶段 A：评论审核

当前结论：阶段 A 实现及选定回归完成，宿主机隔离 MySQL 8 / InnoDB 验证通过；阶段 B 未开始。没有部署、清理数据、提交或推送。

源码目录：`C:\Users\26635\Documents\Codex\2026-10-16\novel-plus-redis-a-b-java\correctness-src`；独立分支 `interaction-moderation-20261019`；HEAD 仍为 `c0925ed1d041d66550e42396cbac8cd1cf84c1bd`。开始前的未提交修改完整保留；新增工作与前序变更通过阶段起点哈希清单区分。完整实际修改列表见同目录 stage-a-files.csv。

## 已实现行为

- 评论及回复强制新建待审，客户端不能指定作者、审核状态、版本或计数。待审创建不增加公开计数；仅公开父评论允许新建回复。
- 公开评论分页与总数只含状态1；公开回复要求父子均为1；主评论回复直达页对隐藏/不存在目标返回404。
- 我的书评/回复只从登录身份限定本人，展示0/1/2和父评论不可见提示。作者可修改原记录；已通过正文修改重新待审并扣减公开数；拒绝后原记录重提，不更改唯一约束。
- 前后台复用 novel-interaction 中的审核事务与SQL，锁序 book -> comment -> reply。状态变化按旧状态差额更新；父评论隐藏不改子回复状态或内部已通过回复数；删除主评论事务内删除回复，重复删除不重复计数。
- 审核和正文编辑是不同入口；均要求 moderation_version。每次正文或状态变化递增，过期请求拒绝；同状态审核不重复计数。普通书籍编辑不能覆盖派生评论数，新书为0。
- 后台保留原评论权限，补充主评论/回复待审筛选、通过/拒绝、正文编辑及删除。回复入口位于 `/novel/bookComment/replies`，审核权限沿用 `novel:bookComment:edit`。
- 正文按纯文本展示；三个现用模板来源（内置、green、orange）及长度提示同步为1至512个Unicode码点。个人列表用请求序号拒绝迟到响应。

## 实际测试证据

最终宿主机证据：`C:\Users\26635\Documents\Codex\2026-10-16\novel-plus-redis-a-b-java\correctness-src\performance\correctness\artifacts\moderation-20261019211411360`，状态 `MYSQL_TESTS_PASSED`。

最终 Java 共66项，66通过、失败0、错误0、跳过0。此前本地 H2 运行61通过、5项MySQL专用检查条件跳过；不把跳过项算作通过。另13项Node VM前端检查与3项PowerShell证据新鲜度检查通过；均不是浏览器/部署验收。

| Suite | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
|com.java2nb.novel.service.BookCommentCounterProtectionTest|2|0|0|0|
|com.java2nb.novel.service.CommentDeletionTest|10|0|0|0|
|com.java2nb.novel.controller.BookControllerCommentHttpTest|1|0|0|0|
|com.java2nb.novel.controller.page.CommentPageVisibilityTest|1|0|0|0|
|com.java2nb.novel.service.impl.CommentCorrectnessTest|15|0|0|0|
|com.java2nb.novel.comment.CommentModerationTest|34|0|0|0|
|com.java2nb.novel.comment.ModerationMigrationTest|3|0|0|0|

覆盖九种主评论转换、九种回复转换、重复审核、父评论隐藏/恢复、公开/本人分页总数、作者越权与原记录重提、并发审核/回复/删除/编辑竞争、过期版本、故障回滚、HTTP匿名拒绝/直达404与后台权限注解。Node VM复现并修复个人列表乱序；JUnit复现并修复书籍普通编辑覆盖计数。全部原始失败证据保留，不以失败后的旧报告冒称当前通过。

真实 MySQL 中两项受控锁等待分别为607ms（审核等待book锁）、1509ms（前台创建等待book锁）。三项迁移fixture通过：计数重算及即时回滚保留旧值；正文/版本变化拒绝回滚；非法历史状态在新增版本列前被拒绝。迁移只在新建fixture schema运行，未对保存的cv_app/novel_ab执行迁移。对账确认7份本轮XML合计66项，与summary一致；全部62份schema快照均为本轮新建的受限cv_it前缀，未混入现有应用schema。

两轮早期宿主机失败（moderation-20261019211103747、moderation-20261019211226397）为端口23307连接拒绝，各37项setup错误，未进入业务断言。另一个成功运行moderation-20261019211311135保留；最终结论仅引用用户返回的moderation-20261019211411360，不合并多轮计数。

## 迁移交付与风险

迁移目录：`C:\Users\26635\Documents\Codex\2026-10-16\novel-plus-redis-a-b-java\correctness-src\doc\sql\moderation-20261019`。

1. 01-preflight.sql：只读检查状态、归属、唯一约束、引擎与计数差异。
2. 02-add-version.sql：人工指定schema且确认停写后，增加两个版本列并收紧状态非空；DDL隐式提交，部分失败需人工检查。
3. 03-recount.sql：保留原始计数和行指纹，再事务内重算已通过数量；备份固定名称拒绝覆盖旧证据。
4. 04-rollback-counts.sql：仅在维护窗口即时回滚计数；行内容/状态/版本/归属/ID或迁移后计数变化则拒绝。保留版本列和全部备份。

这些脚本仅在新建迁移fixture验证，没有对现有数据执行。历史0/2内容会变为私有，不能批量自动改为通过。前后台应共同切换计数语义；旧页面携带auditStatus走通用编辑将被拒绝。原正文中的HTML将按文字显示。写暂停变量只是操作者确认，不能阻止应用写入；在线回滚需要另行对账，不能运行即时回滚脚本。

## 宿主机执行与保留

用户已手动执行受控入口并返回上述最终证据路径。Run-StageA-MySql.ps1仅作为复现入口交付，本次无需重跑。它读取已保存的验证账号，只创建新的受限cv_it测试schema，保留全部数据与证据，不调用Docker、不部署、不更改源schema。入口强制运行测试、只接收本次新鲜Surefire报告，并要求本次MySQL原始证据；不能靠旧报告判为通过。依赖不可用时保留失败证据并停止，不恢复或重建环境。

## 尚未验证或未实施边界

- 本次真实MySQL状态、计数、并发和锁等待已验证；新模块的前后台实际启动、ShardingSphere部署接入、Shiro实机权限与真实浏览器交互尚未验收。
- 部署前必须完成版本DDL和历史计数对齐，不能直接发布新应用到旧schema。
- 阶段B尚未开始，点赞/点踩仍保留旧实现；显式接口、隐藏目标拒绝、真实Redis测试留待阶段B。
- Redis投票持久化、历史孤儿修复、书籍/用户整体删除链路的关系完整性、审核历史流水不在本次自动扩展范围。评论和回复的状态/正文版本不是完整审计日志。
