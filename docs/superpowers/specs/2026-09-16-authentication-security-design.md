# 用户认证安全体系升级设计

## 1. 背景与目标

Novel-Plus 当前前台账号使用手机号作为 `username`，注册依赖图片验证码，密码使用无盐 MD5，登录失败没有账号维度防护，忘记密码也没有邮箱验证闭环。该实现不适合直接暴露到公网。

本阶段在不重做全站授权架构的前提下，加固现有 Spring Boot MVC + JWT 登录体系：

- 新用户使用已验证邮箱作为唯一登录账号；
- 历史手机号账号继续允许登录，但不能再注册新的手机号账号；
- 新密码只使用 Argon2id，历史 MD5 用户在成功登录后无感升级；
- 使用 Redis 管理分用途、单次、短期邮箱验证码；
- 增加账号维度登录限制和 IP 风险升级，抵抗暴力破解与邮件滥用；
- SMTP 密钥只从环境注入；
- JWT、认证 HMAC、SMTP 以及仓库中现存的第三方服务密钥全部改为环境注入，已暴露凭证必须轮换；
- 重置密码后使旧 JWT 失效；
- 增加可观测指标、告警、自动化测试和上线验收。

本阶段采用现有架构内的安全加固，不整体迁移到 Spring Security Filter Chain，也不引入 Keycloak/Auth0 等外部身份平台。这样可以控制改动范围，并保留项目自身的认证安全实现作为学习和展示内容。

## 2. 范围与边界

### 2.1 本阶段包含

- 邮箱验证码注册；
- 邮箱验证码找回并重置密码；
- 163 SMTP 邮件发送；
- MD5 到 Argon2id 的登录懒迁移；
- 可扩展密码算法注册表；
- 密码参数配置及基准测试；
- 邮箱、IP 双维度验证码发送控制；
- 账号失败限制及 IP 风险升级；
- PC 和移动端登录、注册、忘记密码页面；
- JWT `token_version` 校验及密码重置后的旧 Token 失效；
- 安全指标、告警、自动化测试与运行验收。

### 2.2 本阶段不包含

- 不开放修改登录邮箱接口，仅预留 `CHANGE_EMAIL` 验证码用途；
- 不实现 MFA、第三方登录、OAuth2/OIDC 或 Passkey；
- 不移除历史手机号账号，也不强制历史用户集中改密；
- 不重构后台管理员认证；
- 不把 SMTP 密钥、真实测试邮箱或授权码提交到 Git；
- 不保留 JWT、AI 服务、缓存管理等固定生产密钥；本地示例只能引用环境变量；
- 不通过无限线程、无限队列或 `Thread.sleep` 抵抗攻击；
- 不将验证码、邮箱、原始 IP、密码或 JWT 写入日志和指标。

## 3. 设计原则与依据

密码使用不可逆、自适应、带随机盐的 Argon2id。OWASP 当前给出的最低推荐组合之一是 `m=19456 KiB、t=2、p=1`；Spring Security 提供基于 Bouncy Castle 的 `Argon2PasswordEncoder`。实际生产参数必须结合目标云服务器进行压测，哈希耗时应控制在可接受范围内，同时考虑并发认证造成的 CPU 和内存消耗。

参考：

- OWASP Password Storage Cheat Sheet：<https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html>
- Spring Security Argon2PasswordEncoder：<https://docs.spring.io/spring-security/site/docs/current/api/org/springframework/security/crypto/argon2/Argon2PasswordEncoder.html>
- OWASP Forgot Password Cheat Sheet：<https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html>
- OWASP Authentication Cheat Sheet：<https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html>

安全逻辑遵循以下原则：

- 邮箱存在性不通过状态码、响应消息或明显的执行路径差异泄露；
- 验证码必须短期、单次、分用途、可限制尝试次数；
- 登录限制以账号为主要维度，IP 只作为辅助风险信号；
- 对关键安全依赖失败关闭，不静默绕过校验；
- 密码、验证码、Token 和 SMTP 授权码均不得进入日志；
- Controller 只负责输入输出，安全规则由独立服务封装。

## 4. 数据模型与迁移

### 4.1 `user` 表目标结构

保留数据库字段 `password`，不重命名为 `password_hash`，但其语义固定为密码哈希。

| 字段 | 目标定义 | 用途 |
|---|---|---|
| `username` | `VARCHAR(50) NULL` | 仅兼容历史手机号登录 |
| `email` | `VARCHAR(254) NULL` + 唯一索引 | 新账号登录邮箱 |
| `password` | `VARCHAR(255) NOT NULL` | MD5 或 Argon2id 编码结果 |
| `password_algorithm` | `VARCHAR(20) NOT NULL DEFAULT 'ARGON2ID'` | 当前密码算法 |
| `token_version` | `BIGINT NOT NULL DEFAULT 0` | JWT 全量失效版本 |
| `email_verified_at` | `DATETIME NULL` | 邮箱验证完成时间 |

MySQL 唯一索引允许多个 `NULL`，因此历史手机号用户可暂时没有邮箱。应用会将邮箱去除首尾空白并统一转为小写；密码不会调用 `trim()`，避免改变用户实际输入。

### 4.2 安全迁移顺序

不能直接用 `DEFAULT 'ARGON2ID'` 给历史记录打标，否则历史 MD5 会被误认为 Argon2id。迁移必须按以下顺序执行：

1. 将 `password` 扩为 `VARCHAR(255)`；
2. 将 `username` 改为可空；
3. 新增 `email`、`token_version`、`email_verified_at`；
4. 先以可空形式新增 `password_algorithm`；
5. 将所有历史空算法记录回填为 `MD5`；
6. 将 `password_algorithm` 改为 `NOT NULL DEFAULT 'ARGON2ID'`；
7. 创建 `email` 唯一索引。

注册代码必须显式写入 `ARGON2ID`，不能依赖数据库默认值。迁移脚本需要可验证现有数据被保留，不删除或覆盖历史密码。

## 5. 密码服务设计

### 5.1 组件边界

`PasswordService` 是业务层使用的唯一密码入口，提供：

```text
encode(rawPassword) -> PasswordHash
matches(rawPassword, encodedPassword, algorithm) -> boolean
needsUpgrade(encodedPassword, algorithm) -> boolean
```

`PasswordHash` 同时包含哈希文本和算法名，防止调用方忘记写 `password_algorithm`。

具体算法由 `PasswordAlgorithmHandler` 注册表提供：

```text
algorithm()
encode(rawPassword)
matches(rawPassword, encodedPassword)
needsUpgrade(encodedPassword)
```

当前注册：

- `MD5`：只允许验证历史密码，`encode` 必须拒绝调用；
- `ARGON2ID`：允许创建、验证及参数升级判断。

以后增加 PBKDF2、scrypt 等算法时，只增加新的 Handler，不修改登录、注册和重置密码流程。未知、为空或格式损坏的算法必须安全拒绝，不能猜测或自动回退。

### 5.2 Argon2id 参数

参数由服务端 `AuthPasswordProperties` 管理：

- 默认：salt 16 字节、hash 32 字节、`m=19456 KiB`、`t=2`、`p=1`；
- 参数可以通过受控环境配置调整；
- 启动时校验安全下限与资源上限；
- 客户端请求不能提供或覆盖参数；
- 提供独立基准脚本输出单次及并发场景耗时和内存观察结果；
- 部署到目标服务器后再决定最终参数；
- 已有 Argon2id 哈希成本低于当前策略时，在成功登录后再次升级。

不使用 `Argon2id(MD5(password))` 等嵌套方案。迁移时必须直接使用本次登录请求中的原始密码生成 Argon2id；该字符串只存在于当前方法调用内，不保存、不缓存、不记录。

### 5.3 MD5 懒迁移

登录流程先按 `password_algorithm` 选择 Handler。MD5 验证成功后，使用原始输入生成 Argon2id，并通过条件更新执行迁移：

```sql
UPDATE user
SET password = :newHash,
    password_algorithm = 'ARGON2ID',
    update_time = :now
WHERE id = :userId
  AND password = :oldHash
  AND password_algorithm = 'MD5'
```

条件更新避免同一账号并发登录互相覆盖。迁移更新失败只增加指标并记录不含敏感数据的受控日志，不影响本次已经验证成功的登录。懒迁移不递增 `token_version`，因此不会使刚签发的 Token 失效。

## 6. 邮箱验证码与邮件发送

### 6.1 用途隔离

验证码用途枚举包含：

- `REGISTER`
- `RESET_PASSWORD`
- `CHANGE_EMAIL`（仅预留，不开放接口）

Redis key 必须包含用途，禁止不同安全操作共用验证码。邮箱和 IP 不直接出现在 Redis key 中，而使用服务端 HMAC 后的稳定标识。

示意：

```text
auth:captcha:register:{emailHmac}
auth:captcha:reset-password:{emailHmac}
auth:captcha:attempts:{purpose}:{emailHmac}
auth:captcha:cooldown:{purpose}:{emailHmac}
auth:captcha:hour:{purpose}:{emailHmac}
auth:captcha:ip-hour:{ipHmac}
```

验证码值也不以明文存储，而存储 `HMAC(purpose | normalizedEmail | code)`。HMAC 密钥通过独立环境变量注入，不能复用 JWT 密钥或 SMTP 授权码。

### 6.2 生命周期与原子消费

- 使用 `SecureRandom` 生成 6 位数字；
- 有效期 10 分钟；
- 同邮箱 60 秒内不能重复发送；
- 同邮箱每个用途每小时最多发送 5 次；
- 同一 IP 每小时最多接受 30 次邮件验证码申请，达到额度后只限制邮件申请，不封禁登录或阅读；
- 同一验证码最多允许 5 次错误输入；
- 成功验证立即删除验证码及尝试计数；
- 第 5 次错误后立即使验证码失效；
- Redis Lua 原子完成比较、错误计数、成功删除，确保并发请求最多成功一次。

注册邮箱已存在、重置邮箱不存在时，接口仍返回统一接受结果。请求路径使用相同的格式校验、限流与异步提交边界，降低邮箱枚举的状态和时间差异。

### 6.3 163 SMTP

使用 Spring Mail 的 `JavaMailSender`，配置只引用环境变量：

```yaml
spring:
  mail:
    host: smtp.163.com
    port: 465
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}
```

SSL、连接超时、读取超时和写入超时必须显式配置。仓库不提供真实账号、授权码或测试收件人。

邮件投递使用认证专用的有界线程池，不复用章节线程池或推荐调度器。队列满时拒绝新任务并记录指标，不能无限堆积。发送失败时删除本次验证码和冷却键，使用户可以稍后重试；日志只记录用途、失败类型和不可逆标识，不记录邮箱或验证码。

## 7. 注册、登录和重置密码流程

### 7.1 注册

1. 用户申请注册验证码；
2. 服务端完成格式、邮箱/IP 额度和邮箱占用检查，但返回统一结果；对于不存在的重置账号和已存在的注册账号，也提交同类型的无邮件异步任务，使 HTTP 路径保持一致；
3. 对可注册邮箱生成验证码、写 Redis、异步发信；
4. 用户提交邮箱、验证码、密码和确认密码；
5. 服务端校验 8–64 字符密码并原子消费验证码；
6. 事务内再次检查邮箱唯一性，生成 Argon2id 哈希并插入用户；
7. 写入 `password_algorithm=ARGON2ID`、`token_version=0` 和 `email_verified_at`；
8. 生成不包含邮箱的 JWT，保持当前注册成功后自动登录体验。

新账号不写手机号 `username`，默认昵称使用不包含邮箱的读者编号。注册遇到唯一键竞争时不能泄露邮箱状态，用户可重新申请验证码。

### 7.2 登录

登录请求使用独立 DTO：`loginAccount`、`password`、可选图片验证码。`loginAccount` 为邮箱格式时查 `email`；为历史手机号格式时查 `username`。新手机号注册不再允许。

顺序：

1. 规范化登录账号并解析可信客户端地址；
2. 检查账号临时限制和 IP 风险状态；
3. 高 IP 风险时要求图片验证码；
4. 查询账号；不存在时执行固定 Argon2id 虚拟验证；
5. 根据 `password_algorithm` 验证密码；
6. 失败时记录账号和 IP 风险；
7. 成功时清除账号失败记录，IP 风险按 TTL 自然衰减；
8. 必要时尝试 MD5 或低参数 Argon2id 懒迁移；
9. 签发带 `token_version` 的 JWT。

账号 15 分钟内连续失败 5 次后限制到窗口结束。一个 IP 在 15 分钟内累计 10 次登录失败后，后续登录必须通过图片验证码；IP 风险记录按 15 分钟 TTL 自然衰减，不直接锁定共享网络。登录接口另设每 IP 每分钟 60 次的短窗口请求上限，超过时返回 HTTP 429 和 `Retry-After`；该限制只反映请求来源，不依赖账号是否存在。禁止通过 `Thread.sleep` 实现延迟，因为这会被攻击者用来占满 Tomcat 线程。

账号不存在、密码错误、算法异常、账号受限均使用统一状态和通用失败消息。IP 风险要求图片验证码可以通过独立布尔字段返回，因为它只反映请求来源风险，不反映邮箱是否存在。

### 7.3 忘记密码

1. 用户申请重置验证码；
2. 无论邮箱是否存在都返回统一结果；
3. 只有真实账号才发送邮件，但 HTTP 路径保持相同异步边界；
4. 用户一次提交邮箱、验证码、新密码和确认密码；
5. 原子消费验证码；
6. 事务内写入新的 Argon2id 哈希和 `ARGON2ID`，递增 `token_version`；
7. 清理账号登录失败记录；
8. 不自动登录，跳回登录页；
9. 可异步发送不含密码的“密码已重置”通知邮件。

验证码已经安全消费但数据库写入失败时，不恢复旧验证码；用户需要重新申请。这避免一次性凭证在不确定状态下被重复使用。

### 7.4 已登录修改密码

现有修改密码接口继续要求旧密码。成功后使用 Argon2id 写入新密码并递增 `token_version`，使其他旧 JWT 失效；当前请求返回一个新版本 JWT，避免用户立即被自己的修改操作登出。

## 8. JWT 版本校验

JWT 只保存最小身份信息：用户 ID、昵称、`token_version`、签发和过期时间，不保存邮箱、手机号或密码算法。历史 Token 缺少版本时按 0 处理，以兼容迁移前签发且数据库版本仍为 0 的用户。

`TokenVersionService` 第一版直接查询数据库中的当前版本。每次获取登录用户时比较 JWT 版本与当前版本：

- 一致：允许继续；
- 不一致：按未登录处理；
- 数据库不可用：认证失败关闭。

第一版不缓存 `token_version`，避免缓存失效失败形成旧 JWT 仍可使用的撤销窗口。当前受保护接口流量有限，优先保证语义正确；以后只有在获得真实查询压力证据并设计出无撤销窗口的失效协议后才允许增加缓存。JWT 解析失败日志不能输出原始 Token；当前 `JwtTokenUtil` 中记录 Token 内容的行为必须移除。

## 9. 客户端地址与图片验证码

抽取通用 `ClientAddressResolver`，只在请求确实来自配置的可信反向代理时读取转发头；否则使用直接连接地址。公网 Nginx 必须覆盖而不是追加不可信客户端提供的转发头。

现有图片验证码从按原始 IP 存储改为按不可逆客户端标识存储，并用于高风险登录挑战。图片验证码是纵深防御，不能替代账号失败限制和请求限流。

## 10. API 与页面

### 10.1 API

```text
POST /user/login
POST /user/register/email-code
POST /user/register
POST /user/password-reset/email-code
POST /user/password-reset
POST /user/updatePassword
POST /user/refreshToken
```

所有认证接口使用专用请求 DTO，不直接接收数据库 `User` 实体。密码字段用后不保留，响应对象不含 `password`、`password_algorithm`、邮箱验证码或完整邮箱。

### 10.2 PC 与移动端

- 登录页：邮箱/历史手机号、密码，高风险时显示图片验证码，提供忘记密码入口；
- 注册页：邮箱、邮件验证码、密码、确认密码、60 秒发送倒计时；
- 忘记密码页：邮箱、邮件验证码、新密码、确认密码；
- 前端校验只改善体验，所有规则必须在服务端重复执行；
- 重置成功后返回登录页；注册成功保持自动登录；
- 错误消息不能区分邮箱不存在、密码错误或账号受限。

## 11. 组件职责

| 组件 | 单一职责 |
|---|---|
| `PasswordService` | 密码算法路由、编码、验证、升级判断 |
| `PasswordAlgorithmHandler` | 单一密码算法实现 |
| `CaptchaService` | 验证码生成、Redis 状态、Lua 原子消费 |
| `AuthMailService` | 邮件模板与有界异步投递 |
| `LoginSecurityService` | 账号失败计数、IP 风险、图片验证码要求 |
| `AuthenticationService` | 编排注册、登录、重置和懒迁移 |
| `TokenVersionService` | JWT 版本数据库校验 |
| `ClientAddressResolver` | 可信代理和客户端地址解析 |

Controller 不直接访问 Redis、JavaMailSender、密码编码器或登录失败 key。

## 12. 失败策略

| 故障 | 行为 |
|---|---|
| Redis 不可用 | 验证码申请、注册、重置和登录安全检查失败关闭 |
| SMTP 不可用 | 接口保持统一响应；任务记录失败并清理验证码/冷却状态 |
| 数据库不可用 | 不认证、不注册、不重置；返回通用依赖失败 |
| Argon2 懒迁移更新失败 | 本次已验证登录成功，记录指标，后续登录重试迁移 |
| JWT 版本数据库查询失败 | 拒绝认证，不继续使用无法确认版本的 Token |
| 邮件线程池饱和 | 拒绝新投递、清理本次验证码状态、增加指标 |
| 未知密码算法 | 安全拒绝，绝不猜测或降级 |

### 12.1 敏感配置管理

`JWT_SECRET`、`NOVEL_AUTH_HMAC_SECRET`、`MAIL_USERNAME`、`MAIL_PASSWORD` 以及现有第三方 AI 服务密钥必须从环境变量或后续部署密钥文件注入。生产 Profile 缺少 JWT 或认证 HMAC 密钥时启动失败，不能回退到仓库默认值。当前仓库已经出现过的真实或疑似真实密钥即使从最新代码删除，仍可能存在于 Git 历史中，因此必须在对应服务端轮换；仅修改配置文件不能视为完成轮换。配置契约测试和敏感信息扫描负责阻止新明文密钥进入后续提交。

## 13. 可观测性

增加低基数 Micrometer 指标：

- 验证码请求结果：accepted、email_limited、ip_limited、dependency_error；
- 验证码校验结果：success、invalid、attempts_exhausted、expired；
- 邮件结果：success、failed、rejected；
- 登录结果：success、bad_credentials、account_limited、captcha_required、dependency_error；
- 密码升级结果：success、race_lost、failed；
- JWT 版本结果：valid、revoked、dependency_error；
- Argon2 编码和验证耗时。

标签只能使用预定义枚举，不能包含邮箱、IP、用户 ID 或异常消息。Grafana 增加认证安全面板；Prometheus 对邮件持续失败、依赖错误、登录失败异常增长、密码升级持续失败设置告警。阈值先用于本地演示，上线后根据真实流量重新校准。

## 14. 测试与验收

### 14.1 密码测试

- 相同原始密码两次编码得到不同 Argon2id 哈希；
- 正确密码匹配、错误密码拒绝；
- MD5 Handler 只能验证，不能生成新密码；
- 未知算法和损坏哈希安全拒绝；
- 参数低于当前策略时 `needsUpgrade` 返回真；
- 配置低于安全下限或高于资源上限时应用启动失败；
- 密码为 8–64 字符，密码不会被 trim；
- 独立基准脚本输出目标机器的单次和并发结果，不使用脆弱的固定耗时单元断言。

### 14.2 迁移与业务测试

- 新注册账号写入邮箱、Argon2id、算法和验证时间；
- MD5 用户首次登录后条件更新为 Argon2id；
- 并发懒迁移不会互相覆盖；
- 懒迁移失败不影响本次登录；
- 第二次登录直接使用 Argon2id；
- 历史手机号账号仍能登录；
- 新手机号注册被拒绝；
- 注册唯一键竞争不产生重复账号。

### 14.3 Redis 与验证码测试

- 注册、重置验证码用途不能互换；
- 10 分钟 TTL、60 秒冷却和小时额度正确；
- 验证成功后 key 删除；
- 并发验证只有一个请求成功；
- 连续错误 5 次后验证码失效；
- Redis 集成测试验证 Lua 的真实原子行为；
- Redis 故障不会绕过验证码或登录保护。

### 14.4 防枚举与登录防护测试

- 已注册与未注册邮箱的重置申请具有相同 HTTP 状态、业务消息和响应结构；
- 不存在账号仍执行虚拟 Argon2id 验证；
- 账号 15 分钟连续失败 5 次触发限制；
- 成功登录清除账号失败记录；
- IP 风险只升级图片验证码和短窗口限流，不创建长期封禁；
- 未通过高风险图片验证码不能继续执行昂贵密码验证；
- 失败日志不含账号原文、密码、验证码或 JWT。

### 14.5 JWT 与页面测试

- 重置密码后旧 JWT 失效，新登录 Token 可用；
- 已登录修改密码返回新版本 Token，旧 Token 失效；
- Token 版本数据库查询失败时认证失败关闭；
- PC 和移动端均包含邮箱验证码、确认密码和忘记密码流程；
- 登录风险响应只控制验证码显示，不泄露账号状态。

### 14.6 运行验收

- 数据库迁移脚本保留现有用户和数据；
- 新账号数据库算法为 `ARGON2ID`；
- 构造 MD5 用户验证首次登录升级及第二次 Argon2id 登录；
- Redis 中验证码状态有正确 TTL，成功验证后消失；
- 60 秒重复发送受限；
- SMTP 密钥和授权码未进入 Git；
- Prometheus 指标和 Grafana面板可见；
- 完整 Maven、前端脚本、配置契约和 `git diff --check` 通过；
- 公网部署前执行 Redis、SMTP、数据库故障演练和登录限流压测。

### 14.7 本地邮件验收基础设施

本地自动验收使用 Mailpit 接收测试邮件，不向公网邮箱投递。Mailpit 的 SMTP 和 HTTP API
只绑定 `127.0.0.1`，不进入生产 Compose 或生产 Profile。启动 `novel-front` 进行验收时，
显式把 SMTP host/port 指向 Mailpit，并使用非敏感的本地发件地址。

验收脚本为每次运行生成随机邮箱，通过 Mailpit HTTP API 读取本次邮件并提取六位验证码；
不得从应用日志、Redis 验证码摘要或数据库反推出验证码。脚本只删除本次测试消息和本次创建的
账号证据，不清空整个邮箱、Redis 或业务表。SMTP 故障演练可在明确启用破坏性演练参数后临时
停止 Mailpit，并必须在 `finally` 中恢复由脚本停止的容器。真实邮箱发送仅作为人工可选检查，
不属于默认自动验收。

## 15. 实施顺序

1. 数据库迁移脚本、实体和 Mapper；
2. 可扩展密码服务与 Argon2 参数配置；
3. MD5/Argon2id 登录及懒迁移；
4. Redis 验证码原子状态机；
5. 163 SMTP 有界异步发送；
6. 邮箱注册接口；
7. 忘记密码与 JWT 版本失效；
8. 登录失败保护、可信 IP 与图片验证码；
9. PC/移动页面；
10. 指标、告警、基准脚本和完整运行验收。

每一步先编写失败测试，再实现最小代码使其通过。不得在前一层安全边界尚未验证时直接接入公网。
