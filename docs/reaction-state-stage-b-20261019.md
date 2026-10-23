# Novel-Plus 阶段 B：显式点赞/点踩改造

阶段 B 代码和选定验证已完成，停止在联合部署验收之前。分支保持 `interaction-moderation-20261019`，HEAD 保持 `c0925ed1d041d66550e42396cbac8cd1cf84c1bd`。没有提交、推送、部署、执行数据库迁移或操作冻结实验。

## 业务行为

- 新增 POST `/book/setCommentLike`、`/book/setCommentUnLike`、`/book/setReplyLike`、`/book/setReplyUnLike`。使用 `commentId` 或 `replyId` 和严格的 `active=true/false` 表单参数。身份仅从服务器登录上下文取得。
- 新增 GET `/book/commentReaction`、`/book/replyReaction`，读取本人选中状态和当前计数。新接口成功响应为 `data={likesCount,unLikesCount,liked,disliked}`，沿用现有全局协议，两个 Long 计数在 JSON 中是十进制字符串，两个状态是布尔值；响应禁止缓存。
- 新旧写入口和新状态查询入口均先拒绝未登录、非法或不存在的目标。主评论必须审核通过；回复必须自身和父评论均通过。作者本人也不能给未公开内容投票。
- 继续使用既有四种 Set 前缀，无新数据库表。Lua 在一次执行中完成 SADD/SREM、双方计数和本人成员关系快照。重复设置同一状态不增加/减少多次；赞踩独立，允许同时选中。
- 两个 Set 都在写入前校验类型，避免另一键类型错误时先写后报错。读取或写入失败不伪造成功快照。
- 四个旧 toggle 入口继续返回原有单一计数载荷，并带 `@Deprecated`、HTTP `Deprecation` 和替代入口 Link。旧 toggle 仍然非幂等，不应自动重试。
- 内置、green、orange 模板改走新接口；同一 kind/id 只允许一个请求在途，忙时忽略重复点击，赞踩操作也共享该限制。用请求序号丢弃迟到回调；失败后 GET 恢复，不重放写操作。选中态和双方计数以确认的服务器结果更新，首次加载/AJAX 换页后重新读取。前端保留计数字符串，避免大整数精度丢失。orange 原有占位赞控件替换为实际赞踩；orange 没有独立回复模板，回复沿用内置页。

## 验证结果

实际宿主机运行入口：`performance/correctness/Run-ReactionChecks.ps1 -IncludeRedis -CredentialFile <既有 correctness runtime verify.env>`。

原始证据目录：`performance/correctness/artifacts/reaction-20261019214052630`，时间 2026-10-19 21:40:52 至 21:41:02（+08:00），状态 `REDIS_TESTS_PASSED`。逐个 XML 与快照另行核验，未使用历史报告充当本次结果。

| 验证 | 通过 | 失败/错误/跳过 |
|---|---:|---:|
| 选定 Java 回归总计 | 41 | 0 / 0 / 0 |
| 其中真实 Redis（127.0.0.1:26381） | 13 | 0 / 0 / 0 |
| Node VM 和模板静态检查 | 27 | 0 |
| Redis 前后原始快照 | 26 | 全部起始键不存在 |

Java 分项：ReactionControllerTest 5、ContentReactionWiringTest 指定用例 1、LikeServiceImplTest 3、ReactionStateServiceTest 11、ReactionVisibilityTest 8、LikeConcurrencyRedisIT 13。

| 业务要求 | 实际证据与结果 |
|---|---|
| 连续两次 true / false | 真实 Redis 分别保留一个成员 / 无成员，返回快照匹配 |
| 20 用户并发 true，再重复执行 | Set 精确为 20，第二轮返回 20 且成员不增 |
| 同用户并发同意图 | 48 次 true 最终一个成员；48 次 false 最终无成员 |
| 赞踩独立、回复行为一致 | 同时为 true 可达到 1/1；单边取消保留另一边，查询与 Set 一致 |
| 待审、拒绝、隐藏父评论 | 新旧入口均拒绝；真实 Redis 测试未创建任何投票键；审核读取使用 Mapper 桩 |
| 未登录、非法/不存在目标、孤立回复 | Java 校验拒绝且未访问 Redis；HTTP 验证服务器身份和严格 active 参数 |
| Lua 原子性/异常 | 并发成员和计数正确；另一键错误类型时不产生部分写入 |
| 兼容旧 toggle | 原来的连续、同用户并发奇偶、不同用户计数测试保留；MockMvc 配合真实 Redis 验证旧标量载荷 |
| 双击、迟到响应、失败恢复 | Node 模拟回调验证请求数量、请求序号、失败后只 GET；模板脚本解析及三份共享脚本一致性检查通过 |
| 实际 JSON 协议 | MockMvc 加载全局响应 advice，覆盖字符串计数；Node 覆盖字符串、大整数和空响应恢复 |

测试过程曾发现并修复 Long 字符串与前端校验不匹配的问题；最终运行没有失败项。保留最初的失败回归日志，证明新行为测试在实现/修复前失败。未运行完整全仓测试；阶段 A 的 66 项 MySQL 验证为上一阶段证据，本阶段不重复归入测试总数。

## 阶段 A 保护与变更区分

修改 B 前已保存 4,449 个文件的 SHA-256、完整文件 ZIP、当时 Git diff 和 status。内部检查点在本聊天 `work/stage-b/stage-a-files.zip` 等文件；公开交付只复制哈希和校验结论，原归档保留原处。检查点 ZIP 每个条目的内容哈希均与 manifest 一致。

阶段 B 修改了检查点中的 12 个文件，4,437 个保持字节相同，无删除。12 个重叠文件仅为点赞 Controller/Service、8 个模板和既有真实 Redis 测试。新增 B 文件另列清单；admin、novel-interaction、SQL、评论 Service/Mapper、本人评论模板和原阶段 A 验证入口均与检查点逐字节一致。重叠差异补丁与新增文件随证据包保存，可独立审阅 A/B。

## 兼容、迁移与剩余边界

1. B 无 DDL、数据重算或旧 Set 搬迁需求；没有执行 A 计数迁移。现有投票成员继续生效。旧调用方载荷兼容，但隐藏目标现在会被拒绝；新旧前端脚本和模板应作为一组发布。
2. 审核状态的 MySQL 查询与 Redis Lua 之间存在窗口：校验后目标可能被隐藏，导致一次已准入投票完成。后续请求仍重新校验；本改造不提供跨存储事务，不保证立即撤销窗口内投票，不清理历史投票数据。
3. 返回计数和状态只代表该次 Lua 执行时刻；后续用户操作、其他标签页或旧 toggle 可以再改变结果。不同意图的并发设置没有全局业务顺序保证。网页忙时忽略重复点击，不排队或保存下一次意图。
4. 两键 Lua 复用既有键名，针对当前 standalone Redis；未增加 Redis Cluster 同槽迁移方案。Lua 单次执行的原子性不等于 Redis 故障后的持久性保证。
5. 页面首屏公开计数仍沿用已有展示读取逻辑；Redis 不可用时原逻辑可回退 0，新增状态接口不会把故障解释成成功状态。恢复 GET 也可能失败，此时下次点击只重读，不盲写。
6. 真实 Redis 测试使用随机且事先不存在的目标键，保留测试新增 Set、故意构造的错误类型键和快照；没有清理既有数据。验证入口固定拒绝冻结实验端口 26380。测试不连接 MySQL，审核可见性用 Mapper 桩；HTTP 用 MockMvc 登录属性，不代表部署后的完整鉴权链路。
7. Node 是状态协调器和静态模板验证，没有声称完成真实浏览器点击验收。联合部署时仍需用实际登录会话验证三套模板、资源发布、选中显示、网络故障及真实审核状态变化。当前工作区保持未提交，等待该验收。

## 实际文件清单

相对于阶段 A 检查点；CSV 和 JSON 另含前后 SHA-256。下列清单使用仓库相对路径，仓库根目录为 `C:\Users\26635\Documents\Codex\2026-10-16\novel-plus-redis-a-b-java\correctness-src`。

| 变更 | 文件 |
|---|---|
| 新增 | `docs/reaction-state-stage-b-20261019.md` |
| 新增 | `docs/superpowers/plans/2026-10-19-reaction-state.md` |
| 修改 | `novel-front/src/main/java/com/java2nb/novel/controller/BookController.java` |
| 修改 | `novel-front/src/main/java/com/java2nb/novel/service/LikeService.java` |
| 修改 | `novel-front/src/main/java/com/java2nb/novel/service/impl/LikeServiceImpl.java` |
| 新增 | `novel-front/src/main/resources/lua/reaction-read.lua` |
| 新增 | `novel-front/src/main/resources/lua/reaction-set.lua` |
| 新增 | `novel-front/src/main/resources/static/javascript/reaction-state.js` |
| 修改 | `novel-front/src/main/resources/templates/book/book_comment.html` |
| 修改 | `novel-front/src/main/resources/templates/book/book_comment_reply.html` |
| 修改 | `novel-front/src/main/resources/templates/book/book_detail.html` |
| 新增 | `novel-front/src/test/java/com/java2nb/novel/controller/ReactionControllerTest.java` |
| 修改 | `novel-front/src/test/java/com/java2nb/novel/service/impl/LikeConcurrencyRedisIT.java` |
| 新增 | `novel-front/src/test/java/com/java2nb/novel/service/impl/ReactionStateServiceTest.java` |
| 新增 | `novel-front/src/test/java/com/java2nb/novel/service/impl/ReactionVisibilityTest.java` |
| 新增 | `performance/correctness/Run-ReactionChecks.ps1` |
| 新增 | `performance/correctness/Test-ReactionUi.cjs` |
| 修改 | `templates/green/html/book/book_comment.html` |
| 修改 | `templates/green/html/book/book_comment_reply.html` |
| 修改 | `templates/green/html/book/book_detail.html` |
| 新增 | `templates/green/static/javascript/reaction-state.js` |
| 修改 | `templates/orange/html/book/book_comment.html` |
| 修改 | `templates/orange/html/book/book_detail.html` |
| 新增 | `templates/orange/static/javascript/reaction-state.js` |
