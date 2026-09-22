# 用户认证安全体系升级 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留历史手机号账号可登录的前提下，将前台认证升级为邮箱账号、Argon2id 密码、MD5 登录懒迁移、Redis 一次性验证码、登录防护和可撤销 JWT，并补齐页面、监控及本地验收。

**Architecture:** `AuthenticationService` 编排注册、登录、重置和改密；密码算法、验证码、邮件、登录风险、客户端地址和 Token 版本分别由独立组件负责。数据库保存算法与 Token 版本，Redis 只保存短期安全状态，邮件使用有界线程池，Controller 只接收 DTO 和映射统一响应。

**Tech Stack:** Java 17、Spring Boot 3.4、MyBatis Dynamic SQL、MySQL 8、Redis/Lettuce、Spring Mail、Spring Security Crypto、Bouncy Castle、JJWT、Micrometer、Thymeleaf、PowerShell、Docker Compose。

**Spec:** `docs/superpowers/specs/2026-09-16-authentication-security-design.md`

## Global Constraints

- 只修改 `D:\offer\novel-plus\.worktrees\chapter-performance`，不要碰主工作树。
- 用户负责 `git add` 和 `git commit`；每个任务结束给出精确文件清单和建议提交消息。
- 永远排除用户已有的三个配置改动：`novel-admin/src/main/resources/application-dev.yml`、`novel-admin/src/main/resources/application-prod.yml`、`novel-common/src/main/resources/application-common-dev.yml`。
- 全程 TDD：先运行目标测试并确认因缺失行为失败，再写最小实现，再运行模块测试。
- 不在日志、异常、指标、测试快照或命令输出中打印密码、验证码、完整邮箱、原始 IP、JWT、SMTP 授权码或第三方 API Key。
- 不把真实密钥写入仓库。已经进入 Git 历史的第三方凭据由用户到提供商控制台轮换，代码只能完成外置和检测，不能替用户轮换。
- Redis、数据库或 Token 版本查询故障时，安全检查失败关闭；只有“已正确验证密码后的哈希升级失败”允许本次登录继续。
- 密码原文不 `trim()`；邮箱和登录账号先 `trim()`，邮箱再使用 `Locale.ROOT` 转小写。

---

## Task 1: 外置敏感配置并建立认证配置边界

**Files:**

- Modify: `novel-front/pom.xml`
- Modify: `novel-front/src/main/resources/application.yml`
- Modify: `novel-front/src/main/resources/application-dev.yml`
- Modify: `novel-front/src/main/resources/application-prod.yml`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/config/AuthPasswordProperties.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/config/AuthSecurityProperties.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/config/AuthConfiguration.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/config/AuthPasswordPropertiesTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/config/AuthConfigurationTest.java`
- Create: `performance/test-auth-security-config.ps1`
- Modify: `performance/start-front-monitoring.ps1`
- Modify: `performance/README.md`

- [ ] **Step 1: 写配置失败测试**

测试绑定 `novel.auth.password`，覆盖默认值 `saltLength=16`、`hashLength=32`、`memoryKiB=19456`、`iterations=2`、`parallelism=1`，并断言低于安全下限、超过资源上限、JWT/HMAC 密钥为空时校验失败。

```java
assertThatThrownBy(() -> properties.validate())
    .isInstanceOf(IllegalStateException.class);
```

- [ ] **Step 2: 运行测试，确认因配置类不存在而失败**

```powershell
./mvnw.cmd -pl novel-front -am -DskipTests install
./mvnw.cmd -pl novel-front -Dtest=AuthPasswordPropertiesTest,AuthConfigurationTest test
```

- [ ] **Step 3: 加入官方实现依赖**

在 `novel-front/pom.xml` 增加 `spring-boot-starter-mail`、`spring-security-crypto` 和 `bcprov-jdk18on`。版本由 Spring Boot BOM 管理的依赖不手写版本；Bouncy Castle 使用父 POM property 管理的固定版本，避免散落。

- [ ] **Step 4: 实现受控配置和 Bean**

`AuthPasswordProperties` 使用 `@ConfigurationProperties("novel.auth.password")`；`AuthSecurityProperties` 保存 HMAC、登录窗口、验证码 TTL、可信代理等配置。`AuthConfiguration` 创建 `SecureRandom`、当前策略的 `Argon2PasswordEncoder` 和认证专用 `MeterRegistry` 使用入口。客户端请求永远不能传 Argon2 参数。

- [ ] **Step 5: 移除仓库中的固定秘密**

将配置改为只引用环境变量：

```yaml
jwt:
  secret: ${JWT_SECRET}
cache:
  manager:
    password: ${CACHE_MANAGER_PASSWORD}
spring:
  ai:
    openai:
      api-key: ${NOVEL_AI_API_KEY:}
novel:
  auth:
    hmac-secret: ${NOVEL_AUTH_HMAC_SECRET}
```

开发环境可使用不具生产权限的本地变量，但不能在 YAML 留默认秘密；生产 Profile 缺失 JWT/HMAC 必须启动失败。启动脚本在 Maven 运行前检查必要变量是否存在并给出变量名，不打印变量值；README 提供仅对当前 PowerShell 进程设置随机本地值的方法，不创建会被提交的 `.env`。不要在测试中回显变量值。

- [ ] **Step 6: 添加配置契约与敏感信息扫描**

`performance/test-auth-security-config.ps1` 解析 YAML，确认使用环境占位符，并用文件名级扫描拦截 `api-key: <明文>`、`password: <SMTP明文>`、固定 JWT/HMAC；发现问题只输出文件路径和规则名，不输出匹配内容。

- [ ] **Step 7: 验证**

```powershell
./mvnw.cmd -pl novel-front -Dtest=AuthPasswordPropertiesTest,AuthConfigurationTest test
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
& .\performance\test-auth-security-config.ps1
git diff --check
```

- [ ] **Step 8: 用户提交**

建议提交消息：`chore: externalize authentication secrets`

提交后，用户必须在第三方服务控制台轮换曾提交过的 AI 凭据；上线前再创建新的 JWT 与认证 HMAC 随机秘密。

---

## Task 2: 数据库迁移、实体和 Mapper

**Files:**

- Create: `doc/sql/20260917_authentication_security.sql`
- Modify: `doc/sql/novel_plus.sql`
- Create: `performance/apply-authentication-security-schema.ps1`
- Create: `performance/test-authentication-schema-script.ps1`
- Modify: `novel-common/src/main/java/com/java2nb/novel/entity/User.java`
- Modify: `novel-common/src/main/java/com/java2nb/novel/mapper/UserDynamicSqlSupport.java`
- Modify: `novel-common/src/main/java/com/java2nb/novel/mapper/UserMapper.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/mapper/FrontUserMapper.java`
- Modify: `novel-front/src/main/resources/mybatis/mapping/UserMapper.xml`
- Create: `novel-front/src/test/java/com/java2nb/novel/mapper/FrontUserAuthMapperTest.java`

- [ ] **Step 1: 写数据库契约失败测试**

脚本在临时数据库创建一条历史 MD5 用户，连续执行迁移两次，并断言：数据仍在、`password` 扩为 255、历史算法为 `MD5`、默认算法为 `ARGON2ID`、`username` 可空、邮箱唯一索引存在。

- [ ] **Step 2: 写 Mapper 失败测试**

覆盖按规范化邮箱查询、按历史手机号查询、读取 `token_version`、MD5 条件升级竞争只成功一次。

```java
int upgradeLegacyPassword(long userId, String oldHash, String newHash, LocalDateTime now);
Optional<User> selectAuthByEmail(String normalizedEmail);
Optional<User> selectAuthByLegacyUsername(String username);
OptionalLong selectTokenVersion(long userId);
```

- [ ] **Step 3: 运行测试确认失败**

```powershell
& .\performance\test-authentication-schema-script.ps1
./mvnw.cmd -pl novel-front -Dtest=FrontUserAuthMapperTest test
```

- [ ] **Step 4: 实现安全、幂等迁移**

SQL 顺序必须是：扩列和放宽 `username`；新增 nullable `password_algorithm`；回填空值为 `MD5`；改成 `NOT NULL DEFAULT 'ARGON2ID'`；新增 `email`、`token_version`、`email_verified_at` 和唯一索引。脚本不得删除表或覆盖密码。

- [ ] **Step 5: 更新实体与生成式 Mapper**

`User` 新增 `email`、`passwordAlgorithm`、`tokenVersion`、`emailVerifiedAt`；`password` setter 不再 trim。更新 Dynamic SQL 列、select list、insert/update 映射。注册 DTO 以后负责校验，不再把手机号规则放在实体 `username` 上。

- [ ] **Step 6: 添加认证专用查询**

XML 只选择认证所需字段；懒迁移使用 `WHERE id/password/password_algorithm` 三条件；Token 版本查询只返回一个数字，避免每次认证加载整行。

- [ ] **Step 7: 验证**

```powershell
& .\performance\test-authentication-schema-script.ps1
./mvnw.cmd -pl novel-front -Dtest=FrontUserAuthMapperTest test
git diff --check
```

- [ ] **Step 8: 用户提交**

建议提交消息：`feat: add authentication security schema`

---

## Task 3: 可扩展密码服务与 Argon2id

**Files:**

- Create: `novel-front/src/main/java/com/java2nb/novel/auth/password/PasswordAlgorithm.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/password/PasswordHash.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/password/PasswordAlgorithmHandler.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/password/Md5PasswordAlgorithmHandler.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/password/Argon2idPasswordAlgorithmHandler.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/password/PasswordService.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/password/DefaultPasswordService.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/password/PasswordServiceTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/password/Argon2idPasswordAlgorithmHandlerTest.java`

- [ ] **Step 1: 写行为测试**

覆盖随机 salt、正确/错误密码、MD5 只验证不编码、未知/空算法拒绝、损坏哈希拒绝、低参数 Argon2 需要升级、重复 handler 算法启动失败。

```java
public interface PasswordService {
    PasswordHash encode(String rawPassword);
    boolean matches(String rawPassword, String encodedPassword, String algorithm);
    boolean needsUpgrade(String encodedPassword, String algorithm);
}
```

- [ ] **Step 2: 确认测试失败后实现最小注册表**

`DefaultPasswordService` 从 `List<PasswordAlgorithmHandler>` 构造不可变 Map。`MD5` handler 只调用项目现有 MD5 工具验证，`encode` 抛出明确的内部异常；业务层不得直接调用 MD5 工具。

- [ ] **Step 3: 实现 Argon2id handler**

使用 Spring Security `Argon2PasswordEncoder` 和 Bouncy Castle；`PasswordHash` 同时返回 encoded 与 `ARGON2ID`。比较使用库实现，不自行解析并实现哈希。`needsUpgrade` 只比较服务端策略与编码参数。

- [ ] **Step 4: 验证**

```powershell
./mvnw.cmd -pl novel-front -Dtest=PasswordServiceTest,Argon2idPasswordAlgorithmHandlerTest test
```

- [ ] **Step 5: 用户提交**

建议提交消息：`feat: add extensible password hashing`

---

## Task 4: 登录 DTO、认证编排与 MD5 懒迁移

**Files:**

- Create: `novel-front/src/main/java/com/java2nb/novel/auth/dto/LoginRequest.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/model/AuthenticationResult.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/AuthenticationService.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/DefaultAuthenticationService.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/controller/UserController.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/service/UserService.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/service/impl/UserServiceImpl.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/DefaultAuthenticationServiceTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/controller/UserControllerAuthenticationTest.java`

- [ ] **Step 1: 写登录和迁移失败测试**

覆盖邮箱登录、历史手机号登录、错误密码统一失败、未知算法拒绝、MD5 成功后条件升级、并发竞争为 `race_lost`、更新异常仍签发本次 Token、第二次登录走 Argon2id。

- [ ] **Step 2: 引入独立 DTO**

```java
public record LoginRequest(
    @NotBlank @Size(max = 254) String loginAccount,
    @NotBlank @Size(min = 8, max = 64) String password,
    String imageCaptcha
) {}
```

历史账号可能有不足 8 位旧密码，因此登录 DTO 对密码只设非空和合理上限；8–64 仅约束新注册/重置/改密。切勿 trim 密码。

- [ ] **Step 3: 实现账号路由与虚拟验证入口**

邮箱格式查 `email`，历史手机号格式查 `username`，其余作为不存在账号处理。不存在账号执行预先生成的 Argon2id dummy hash 验证，再返回同一 `BAD_CREDENTIALS`，避免快速枚举。

- [ ] **Step 4: 实现懒迁移**

密码验证成功后若算法为 MD5 或 Argon2 参数落后，生成新哈希并条件更新。捕获升级阶段异常、增加指标但不改变本次认证成功；密码验证本身异常仍失败关闭。

- [ ] **Step 5: Controller 改为 DTO**

保持 `/user/login` URL 和当前 Token 响应结构，删除 Controller 中密码算法和数据库逻辑。`UserService.login(User)` 先保留为受控兼容适配或在同一提交清理所有调用点，不能出现两套认证实现长期并存。

- [ ] **Step 6: 验证**

```powershell
./mvnw.cmd -pl novel-front -Dtest=DefaultAuthenticationServiceTest,UserControllerAuthenticationTest test
```

- [ ] **Step 7: 用户提交**

建议提交消息：`feat: migrate legacy passwords on login`

---

## Task 5: Redis 邮箱验证码原子状态机

**Files:**

- Create: `novel-front/src/main/java/com/java2nb/novel/auth/captcha/CaptchaPurpose.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/captcha/CaptchaConsumeOutcome.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/captcha/CaptchaIssueOutcome.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/captcha/AuthIdentityHasher.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/captcha/CaptchaService.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/captcha/RedisCaptchaService.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/captcha/AuthIdentityHasherTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/captcha/RedisCaptchaServiceTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/captcha/RedisCaptchaServiceRedisIT.java`

- [ ] **Step 1: 写单元和 Redis 集成失败测试**

覆盖 purpose 隔离、验证码不明文入 Redis、600 秒 TTL、60 秒冷却、邮箱每用途 5 次/小时、IP 30 次/小时、错误 5 次失效、成功立即删除、100 个并发消费只有 1 个成功。

- [ ] **Step 2: 定义窄接口**

```java
CaptchaIssue issue(CaptchaPurpose purpose, String normalizedEmail, String clientAddress);
CaptchaConsumeOutcome consume(CaptchaPurpose purpose, String normalizedEmail, String code);
void revoke(CaptchaPurpose purpose, String normalizedEmail);
```

`CaptchaIssue` 中验证码只能交给邮件投递边界，禁止 `toString()` 暴露；Controller 和响应不能取得验证码。

- [ ] **Step 3: 实现 HMAC key 和 SecureRandom**

Redis key 使用 `HMAC-SHA-256(secret, purpose|normalized identity)` 的短十六进制标识；value 保存 `HMAC(purpose|email|code)`。使用 `SecureRandom.nextInt(1_000_000)` 格式化为 6 位。

- [ ] **Step 4: 用 Lua 实现原子发放和消费**

发放脚本一次完成 cooldown/hour/IP-hour 检查和验证码写入；消费脚本一次完成比较、错误计数、第五次删除、成功删除。所有 key 由 Java 计算并通过 `KEYS` 传入，脚本不拼 PII。

- [ ] **Step 5: 验证**

```powershell
./mvnw.cmd -pl novel-front -Dtest=AuthIdentityHasherTest,RedisCaptchaServiceTest test
./mvnw.cmd -pl novel-front -Dtest=RedisCaptchaServiceRedisIT -Dnovel.redis.it.enabled=true test
```

- [ ] **Step 6: 用户提交**

建议提交消息：`feat: add atomic email captcha state`

---

## Task 6: 163 邮件有界异步投递

**Files:**

- Create: `novel-front/src/main/java/com/java2nb/novel/auth/mail/AuthMailService.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/mail/SmtpAuthMailService.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/mail/AuthMailExecutorConfig.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/mail/SmtpAuthMailServiceTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/mail/AuthMailExecutorConfigTest.java`
- Modify: `novel-front/src/main/resources/application.yml`
- Modify: `novel-front/src/main/resources/application-dev.yml`
- Modify: `novel-front/src/main/resources/application-prod.yml`

- [ ] **Step 1: 写失败、拒绝和隐私测试**

Mock `JavaMailSender`，断言主题/模板正确，日志与异常不含邮箱和验证码；队列满时立即拒绝并调用验证码撤销回调；不存在账号的 no-op 任务也走同一 executor。

- [ ] **Step 2: 配置 163 SMTP 和超时**

使用 465/SSL，username/password 只来自 `MAIL_USERNAME`、`MAIL_PASSWORD`；显式配置 connection/read/write timeout。测试 Profile 使用 mock，不发真实邮件。

- [ ] **Step 3: 创建专用有界执行器**

固定核心/最大线程、有限队列、明确拒绝策略；不复用 `novel.front.executor`。任务结果记录 success/failed/rejected 低基数指标。

- [ ] **Step 4: 验证并提交**

```powershell
./mvnw.cmd -pl novel-front -Dtest=SmtpAuthMailServiceTest,AuthMailExecutorConfigTest test
```

建议提交消息：`feat: send bounded authentication emails`

---

## Task 7: 邮箱验证码注册

**Files:**

- Create: `novel-front/src/main/java/com/java2nb/novel/auth/dto/EmailCodeRequest.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/dto/RegisterRequest.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/EmailNormalizer.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/auth/AuthenticationService.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/auth/DefaultAuthenticationService.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/controller/UserController.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/service/UserService.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/service/impl/UserServiceImpl.java`
- Modify: `novel-common/src/main/java/com/java2nb/novel/core/enums/ResponseStatus.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/EmailRegistrationTest.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/controller/UserControllerAuthenticationTest.java`

- [ ] **Step 1: 写注册端到端服务测试**

覆盖统一验证码申请响应、已存在邮箱 no-op、验证码用途错误、密码 7/65 字符拒绝、两次密码不同、成功写入 Argon2id/算法/验证时间、`username=null`、默认昵称不暴露邮箱、唯一键竞争统一失败。

- [ ] **Step 2: 实现 DTO 和邮箱规范化**

```java
public record RegisterRequest(
    @Email @Size(max = 254) String email,
    @Pattern(regexp = "\\d{6}") String code,
    @Size(min = 8, max = 64) String password,
    String confirmPassword
) {}
```

- [ ] **Step 3: 实现申请接口**

增加 `POST /user/register/email-code`。无论邮箱已存在与否都返回同一 HTTP 状态、code、message 和结构；真实发送和 no-op 都提交到邮件执行器。限流或依赖故障使用独立、不泄露邮箱存在性的结果。

- [ ] **Step 4: 实现事务注册**

增加 `POST /user/register`。先消费验证码，再在事务内复查唯一性、编码密码、显式写 `ARGON2ID`/`tokenVersion=0`/`emailVerifiedAt`。消费后 DB 失败不恢复验证码。

- [ ] **Step 5: 验证并提交**

```powershell
./mvnw.cmd -pl novel-front -Dtest=EmailRegistrationTest,UserControllerAuthenticationTest test
```

建议提交消息：`feat: register users with verified email`

---

## Task 8: 密码重置、改密与 JWT 版本撤销

**Files:**

- Create: `novel-front/src/main/java/com/java2nb/novel/auth/dto/PasswordResetRequest.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/dto/PasswordChangeRequest.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/token/TokenVersionService.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/token/DatabaseTokenVersionService.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/core/bean/UserDetails.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/core/utils/JwtTokenUtil.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/core/filter/NovelFilter.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/controller/UserController.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/auth/AuthenticationService.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/auth/DefaultAuthenticationService.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/PasswordResetTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/token/DatabaseTokenVersionServiceTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/core/utils/JwtTokenUtilSecurityTest.java`
- Modify: `novel-front/src/test/java/com/java2nb/novel/controller/UserControllerAuthenticationTest.java`

- [ ] **Step 1: 写撤销语义失败测试**

覆盖不存在邮箱统一申请响应、重置写 Argon2id 并 `token_version + 1`、旧 Token 失效、新登录 Token 有效、版本 DB 故障失败关闭、历史无版本 Token 按 0、JWT 日志不含 Token。

- [ ] **Step 2: 缩小 JWT claims**

只写 `userId`、`nickName`、`tokenVersion`、`iat/exp`，不再把整个 `UserDetails` JSON 放入 subject。解析异常只记录分类和 request correlation id，不记录原 Token 或堆栈中的请求秘密。

- [ ] **Step 3: 每次认证校验数据库版本**

`NovelFilter` 解析 Token 后调用 `TokenVersionService.isCurrent(userId, tokenVersion)`；查询失败视为未认证。`refreshToken` 必须先完成版本校验，不能延长已撤销 Token。

- [ ] **Step 4: 实现重置接口**

增加 `/user/password-reset/email-code` 和 `/user/password-reset`。验证码成功消费后事务更新新 Argon2id、算法并原子递增版本；成功不自动登录。

- [ ] **Step 5: 改造已登录修改密码**

旧密码通过 `PasswordService` 验证；成功写 Argon2id 并递增版本，响应返回新 Token。其他旧 Token 全部失效。

- [ ] **Step 6: 验证并提交**

```powershell
./mvnw.cmd -pl novel-front -Dtest=PasswordResetTest,DatabaseTokenVersionServiceTest,JwtTokenUtilSecurityTest,UserControllerAuthenticationTest test
```

建议提交消息：`feat: revoke tokens after password changes`

---

## Task 9: 登录暴力破解防护、可信客户端地址和图片验证码

**Files:**

- Create: `novel-front/src/main/java/com/java2nb/novel/auth/security/ClientAddressResolver.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/security/TrustedProxyClientAddressResolver.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/security/LoginSecurityService.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/security/RedisLoginSecurityService.java`
- Create: `novel-front/src/main/java/com/java2nb/novel/auth/security/LoginSecurityDecision.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/auth/DefaultAuthenticationService.java`
- Modify: `novel-front/src/main/java/com/java2nb/novel/controller/FileController.java`
- Modify: `novel-common/src/main/java/com/java2nb/novel/core/utils/RandomValidateCodeUtil.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/security/TrustedProxyClientAddressResolverTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/security/RedisLoginSecurityServiceTest.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/security/RedisLoginSecurityServiceRedisIT.java`
- Create: `novel-front/src/test/java/com/java2nb/novel/controller/FileControllerCaptchaTest.java`

- [ ] **Step 1: 写风险规则测试**

覆盖账号 15 分钟 5 次失败限制；成功清账号计数；IP 15 分钟 10 次失败要求图片验证码；每 IP 每分钟 60 请求后 429/`Retry-After`；IP 不产生长期封禁；Redis 故障不绕过；高风险验证码在昂贵密码验证前检查。

- [ ] **Step 2: 写可信代理测试**

直接请求忽略伪造 XFF；只有 remote address 命中配置的可信代理 CIDR 才读取由代理覆盖的转发头；无效 IP/CIDR 启动失败或安全回退到直连地址。

- [ ] **Step 3: 实现 Redis 原子窗口**

账号 key 和 IP key 都使用 HMAC 标识。Lua 完成增量和首次设置 TTL，避免 `INCR` 与 `EXPIRE` 分离；公开结果只有 allowed/accountLimited/captchaRequired/rateLimited/dependencyError。

- [ ] **Step 4: 加固图片验证码**

`RandomValidateCodeUtil` 改用 `SecureRandom`；图片验证码按 HMAC 客户端标识存 Redis，不使用原始 IP；成功后单次删除。`FileController` 不自行解析代理头。

- [ ] **Step 5: 接入登录流程**

顺序固定为短窗口限流 → 账号限制/IP 风险 → 必要的图片验证码 → 查询账号/dummy verify → 密码验证 → 记录失败或清账号计数。通用失败消息不暴露账号状态。

- [ ] **Step 6: 验证并提交**

```powershell
./mvnw.cmd -pl novel-front -Dtest=TrustedProxyClientAddressResolverTest,RedisLoginSecurityServiceTest,FileControllerCaptchaTest test
./mvnw.cmd -pl novel-front -Dtest=RedisLoginSecurityServiceRedisIT -Dnovel.redis.it.enabled=true test
```

建议提交消息：`feat: protect login from brute force attacks`

---

## Task 10: PC 与移动端认证页面

**Files:**

- Modify: `novel-front/src/main/java/com/java2nb/novel/controller/page/PageController.java`
- Modify: `novel-front/src/main/resources/templates/user/login.html`
- Modify: `novel-front/src/main/resources/templates/user/register.html`
- Create: `novel-front/src/main/resources/templates/user/forgot_password.html`
- Modify: `novel-front/src/main/resources/templates/mobile/user/login.html`
- Modify: `novel-front/src/main/resources/templates/mobile/user/register.html`
- Create: `novel-front/src/main/resources/templates/mobile/user/forgot_password.html`
- Modify: `novel-front/src/main/resources/templates/user/set_password.html`
- Modify: `novel-front/src/main/resources/static/javascript/user.js`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/AuthenticationTemplateTest.java`

- [ ] **Step 1: 写模板契约失败测试**

断言 PC/移动端都有邮箱、验证码、发送按钮、密码确认、忘记密码链接；登录页支持邮箱/历史手机号和按响应显示图片验证码；模板中不存在手机号注册字段或敏感配置。

- [ ] **Step 2: 实现页面与交互**

注册发送按钮只做 60 秒 UX 倒计时，服务端限流才是安全边界；重置成功跳登录；登录收到 `captchaRequired=true` 时显示图片验证码。前端不区分“邮箱不存在”和“密码错误”。

- [ ] **Step 3: 防止重复提交和密码泄露**

请求期间禁用提交按钮；完成后清空密码和验证码输入；URL、localStorage、console、DOM data attribute 不保存这些值。

- [ ] **Step 4: 验证并提交**

```powershell
./mvnw.cmd -pl novel-front -Dtest=AuthenticationTemplateTest test
```

建议提交消息：`feat: add secure email authentication pages`

---

## Task 11: 认证指标、Grafana 和告警

**Files:**

- Create: `novel-front/src/main/java/com/java2nb/novel/auth/metrics/AuthenticationMetrics.java`
- Modify: `monitoring/grafana/dashboards/novel-plus-overview.json`
- Modify: `monitoring/prometheus/rules/novel-plus-alerts.yml`
- Modify: `performance/check-observability.ps1`
- Modify: `performance/test-observability-config.ps1`
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/metrics/AuthenticationMetricsTest.java`

- [ ] **Step 1: 写低基数指标测试**

只允许 spec 中的预定义 outcome；断言 tag 中没有 email/ip/userId/exception/message。记录 captcha、mail、login、password upgrade、JWT version 和 Argon2 timer。

- [ ] **Step 2: 接入服务并扩展面板**

业务服务只调用类型安全方法，例如：

```java
metrics.login(LoginOutcome.BAD_CREDENTIALS);
metrics.passwordUpgrade(PasswordUpgradeOutcome.RACE_LOST);
```

- [ ] **Step 3: 增加告警**

覆盖邮件持续失败、认证依赖错误、登录失败异常增长、密码升级持续失败、邮件队列拒绝。规则表达式避免不存在序列被误判为故障。

- [ ] **Step 4: 验证并提交**

```powershell
./mvnw.cmd -pl novel-front -Dtest=AuthenticationMetricsTest test
& .\performance\test-observability-config.ps1
docker run --rm --entrypoint /bin/promtool -v "${PWD}/monitoring/prometheus:/etc/prometheus:ro" prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml
```

建议提交消息：`feat: observe authentication security`

---

## Task 12: Argon2 基准、只读验收和故障演练

**Files:**

- Create: `novel-front/src/test/java/com/java2nb/novel/auth/password/Argon2Benchmark.java`
- Create: `performance/benchmark-argon2.ps1`
- Create: `performance/check-authentication-security.ps1`
- Create: `performance/test-authentication-security-script.ps1`
- Modify: `compose.local.yml`
- Modify: `performance/start-front-monitoring.ps1`
- Modify: `performance/check-observability.ps1`
- Modify: `performance/README.md`
- Modify: `docs/learning/novel-plus-evolution-guide.md`
- Modify: `docs/superpowers/specs/2026-09-16-authentication-security-design.md`

- [ ] **Step 1: 写脚本行为测试**

使用临时 fake Maven、fake HTTP 响应和命令适配器模拟服务未启动、错误 SMTP/Redis/MySQL、非法参数和成功输出。先运行脚本并确认因实现缺失而失败。脚本必须创建带随机前缀的隔离测试账号，`finally` 仅清理本次证据；失败时保留必要的不可逆 ID，不打印密码、验证码、Token 或 Argon2 哈希。

- [ ] **Step 2: 实现 Argon2 基准脚本**

`Argon2Benchmark` 只在显式系统属性开启时执行。PowerShell 参数使用固定允许列表，并限制 `memoryKiB * targetConcurrency <= 512 MiB`。一次 Maven 运行分别输出 encode/verify 的单线程和目标并发共四行结果；包装脚本验证 operation、samples、p50/p95、吞吐、失败数和结果完整性后才输出，不回显 Maven 原始输出或哈希。在目标 Linux 服务器根据结果调整环境变量，不改代码默认下限。

- [ ] **Step 3: 增加本地 Mailpit**

在 `compose.local.yml` 增加固定版本 Mailpit 服务，SMTP `1025` 与 HTTP API `8025` 都只映射到 `127.0.0.1`，并配置健康检查。README 给出仅用于验收的启动参数：应用 SMTP 指向 `127.0.0.1:1025`，关闭 auth/SSL，发件地址使用 `acceptance@novel.local`。生产配置和真实 163 邮箱流程不改。

- [ ] **Step 4: 实现本地完整验收**

每次生成随机 `@example.test` 邮箱，通过 Mailpit API 按收件人和主题定位本次邮件并提取验证码。自动验证：新注册为 Argon2id；构造隔离 MD5 用户首次登录升级且第二次登录成功；验证码 TTL/单次消费/60 秒冷却；账号失败限制；IP captcha 升级；重置后旧 Token 失效；指标存在。不得读取日志或 Redis 摘要取得验证码。默认不连接真实邮箱。

- [ ] **Step 5: 实现故障演练**

通过 `-FailureDrill Redis|MySql|Kafka|Smtp -AllowContainerStop` 显式启用。分别短暂停止依赖，验证 Redis/MySQL 失败关闭、Kafka 无关且不影响认证判断、Mailpit 停止后接口统一响应且验证码被撤销。脚本只停止已验证名称的本地容器，在 `finally` 中仅恢复由本次脚本停止的容器，并等待健康状态恢复。

- [ ] **Step 6: 运行完整回归**

```powershell
./mvnw.cmd test
& .\performance\test-auth-security-config.ps1
& .\performance\test-authentication-schema-script.ps1
& .\performance\test-authentication-security-script.ps1
& .\performance\test-observability-config.ps1
git diff --check
git status --short
```

- [ ] **Step 7: 启动后运行在线验收**

```powershell
& .\performance\apply-authentication-security-schema.ps1
docker compose -f .\compose.local.yml up -d mailpit
& .\performance\check-authentication-security.ps1 -BaseUrl 'http://127.0.0.1:8083' -MailpitUrl 'http://127.0.0.1:8025'
& .\performance\check-observability.ps1 -GrafanaUser 'admin' -GrafanaPassword '<本地密码>'
```

- [ ] **Step 8: 人工安全检查**

- 轮换历史中已出现的第三方 API 凭据；
- 为部署环境生成独立的 JWT 与 HMAC 随机秘密；
- 确认 Git staged 文件不含 `.env`、授权码、真实邮箱或验收密码；
- 浏览器检查 PC/移动注册、登录、忘记密码和改密；
- Grafana 检查认证面板，Prometheus 检查告警规则加载。

- [ ] **Step 9: 用户提交**

建议提交消息：`test: verify authentication security`

---

## Task 13: 最终代码审查与分支验收

**Files:**

- Review only: all files changed by Tasks 1–12
- Modify if required: only files with verified review findings

- [ ] **Step 1: 安全审查**

检查认证绕过、枚举差异、事务边界、验证码竞争、代理头信任、MD5 新写入、Token 撤销窗口、PII/秘密日志、无界线程或队列。

- [ ] **Step 2: 数据兼容审查**

用迁移前数据库副本验证历史手机号登录、历史 MD5 标记、新注册邮箱唯一性、脚本重复执行以及回滚说明。禁止拿生产唯一副本直接试迁移。

- [ ] **Step 3: 最终验证**

重新运行 Task 12 的完整回归和在线验收；以本次新输出为准，不引用旧运行结果。确认 `git status --short` 中只剩本计划范围和用户明确保留的三个配置文件。

- [ ] **Step 4: 用户提交审查修复**

若有修复，按问题域拆分提交；若没有修复，不创建空提交。实现全部通过后再使用 `superpowers:finishing-a-development-branch` 决定保持功能分支、合并或作为独立展示项目。
