# 小说章节性能测试

这里使用本机已经安装的 Apache JMeter 5.6.3，不会重新下载 JMeter。

## 两种使用方式

学习和调试时运行：

```powershell
& 'D:\jmeter\apache-jmeter-5.6.3\bin\jmeter.bat'
```

在 GUI 中打开 `performance/jmeter/chapter-baseline.jmx`。GUI 只使用 1 个线程检查地址、断言和响应；正式压测不要打开 `View Results Tree`，因为监听器会占用大量内存并污染测试结果。

正式测试使用 `performance/run-chapter-stages.ps1`。它以非 GUI 模式依次运行 1、10、30、50、100、200 个线程，并为每档生成 JTL 和 HTML 报告。

## 指标含义

- Threads：同时循环发请求的虚拟用户数，不等于 QPS。
- Throughput：JMeter 实际完成的每秒请求数。
- P95/P99：95%/99% 的请求不超过该耗时；比平均值更能暴露慢请求。
- Error %：HTTP 错误、超时和断言失败所占比例。
- 容量拐点：增加线程后吞吐量不再明显增长，但 P99 或错误率快速上升的位置。

## 选择真实章节

先启动 MySQL，然后查询一个免费章节：

```powershell
docker compose -f '.\compose.local.yml' up -d
docker exec novel-mysql mysql -uroot -p123456 novel_plus -e "SELECT book_id,id AS book_index_id,index_name FROM book_index WHERE is_vip=0 ORDER BY id LIMIT 10;"
```

必须从同一行取 `book_id` 和 `book_index_id`。例如查询结果为书籍 123、章节 456，运行：

```powershell
& '.\performance\run-chapter-stages.ps1' -BookId 123 -BookIndexId 456 -Label before
```

优化后必须使用相同 ID：

```powershell
& '.\performance\run-chapter-stages.ps1' -BookId 123 -BookIndexId 456 -Label after
```

默认每档持续 60 秒。第一次验证脚本可以缩短为 10 秒：

```powershell
& '.\performance\run-chapter-stages.ps1' -BookId 123 -BookIndexId 456 -Label before -DurationSeconds 10
```

脚本拒绝覆盖已有的 `before` 或 `after` 目录，防止误删性能证据。生成的原始结果位于 `performance/results/raw/`，不会提交到 Git。

## 公平比较规则

前后两轮必须保持同一台电脑、同一份数据库、同一个章节、同一 JVM 参数、同一并发阶梯和同一测试时长。访问量接口 `POST /book/addVisitCount` 不包含在章节正文读取基线里，将在后续写场景中单独测试。

原项目的统一异常处理会把部分服务端异常渲染成 HTTP 200 的自定义 404 页面，所以不能只看状态码。当前 JMeter 计划还断言响应必须包含真实手机章节页标记 `id="contentIdHidden"`。正式报告中的 `Successful throughput` 会排除断言失败的快速错误页。

## 缓存正确性与故障测试

以下命令中的 ID 必须和前后压测使用同一行数据。只删除目标章节键，不要使用 `FLUSHALL`：

```powershell
$bookId = 123
$bookIndexId = 456
$chapterKey = "novel:chapter:v1:$bookId`:$bookIndexId"
$chapterUrl = "http://127.0.0.1:8083/book/$bookId/$bookIndexId.html"
$mobileHeaders = @{
    'User-Agent' = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Mobile/15E148'
}

docker exec novel-redis redis-cli -a 123456 DEL $chapterKey
$cold = Invoke-WebRequest $chapterUrl -Headers $mobileHeaders -UseBasicParsing
$hot = Invoke-WebRequest $chapterUrl -Headers $mobileHeaders -UseBasicParsing
$cold.Content.Contains('contentIdHidden')
$hot.Content.Contains('contentIdHidden')
docker exec novel-redis redis-cli -a 123456 TTL $chapterKey
```

两次页面标记都应为 `True`，TTL 应在 1–2100 秒之间。正常新写入的 TTL 是 1800–2100 秒；接近过期时看到更小的正数是正常现象。

Redis 故障只做一个请求，不要在依赖不可用时继续高并发压测：

```powershell
try {
    docker stop novel-redis
    Measure-Command {
        Invoke-WebRequest $chapterUrl -Headers $mobileHeaders -UseBasicParsing -TimeoutSec 8
    }
}
finally {
    docker start novel-redis
    docker exec novel-redis redis-cli -a 123456 ping
}
```

请求必须在有限时间内返回或失败，`finally` 必须把 Redis 恢复到 `PONG`。本次实测发现章节缓存能回源 MySQL，但旧的模板目录读取仍依赖 Redis，因此会快速返回自定义错误页；完整数据和结论见 `performance/results/chapter-performance-summary.md`。

## 小说点击量写入基线

点击量和章节正文必须分开测试。`performance/jmeter/book-visit-count.jmx` 只请求：

```text
POST /book/addVisitCount
bookId=<真实小说ID>
```

它同时断言 HTTP 200 和响应中的 `"ok":true`。Kafka改造前后使用同一个脚本：

```powershell
& '.\performance\run-book-visit-stages.ps1' `
  -BookId 2055879962859147264 `
  -Label 'before-kafka' `
  -DurationSeconds 60

& '.\performance\run-book-visit-stages.ps1' `
  -BookId 2055879962859147264 `
  -Label 'after-kafka' `
  -DurationSeconds 60
```

脚本禁止覆盖已有标签目录。正式比较需要记录 HTTP 成功数、接口P99、Kafka确认成功数、最终数据库增量、MySQL实际UPDATE数量和消费者Lag，不能只比较接口表面QPS。

## Kafka 点击量链路验收

先确认 MySQL、Redis、Kafka 都健康，并启动 novel-front：

    docker compose -f '.\compose.local.yml' up -d
    docker compose -f '.\compose.local.yml' ps

用下面的脚本做一次不会重置原数据的功能检查。它分批并发发送请求，默认每批最多
100 个；随后等待 Kafka Lag 清零，并核对数据库增量与生产者确认成功数完全一致：

    & '.\performance\check-book-visit-kafka.ps1' -BookId 2055879962859147264 -RequestCount 1000 -MaxConcurrency 100

输出中的指标含义：

- HttpAccepted：接口已经接收的请求数，不代表 Kafka 一定写入成功。
- ProducerSuccesses/ProducerFailures：Kafka 生产者的最终确认结果。
- FinalDelta：MySQL 中点击量的实际增量，必须等于 ProducerSuccesses。
- ConsumerLag：消费者尚未完成的消息数，验收结束必须是 0。
- DrainMilliseconds：请求发完后，消息落库并清空 Lag 所花的时间。

常用观察命令：

    docker exec novel-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic novel-book-visit-v1
    docker exec novel-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group novel-book-visit-writer-v1
    docker exec novel-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic novel-book-visit-dlt --from-beginning

    Invoke-RestMethod 'http://127.0.0.1:8084/actuator/metrics/novel.book.visit.kafka.send'
    Invoke-RestMethod 'http://127.0.0.1:8084/actuator/metrics/novel.book.visit.kafka.batch_size'
    Invoke-RestMethod 'http://127.0.0.1:8084/actuator/metrics/novel.book.visit.kafka.db_updates'

本地故障测试只停止单个容器，并始终用 finally 恢复。MySQL 停机时，已确认的
Kafka 消息应形成 Lag，MySQL 恢复后再补写；Kafka 停机时点击接口不会同步回退写
MySQL，生产失败由 result=failed 指标暴露。非法事件进入 novel-book-visit-dlt，
后续合法事件仍可消费。具体实测数字见
performance/results/book-visit-kafka-summary.md。

## 本地可观测性

本阶段保持 Java 应用运行在 Windows 主机，Prometheus 和 Grafana 运行在 Docker。
`monitoring` Profile 会让 Docker 通过 `host.docker.internal:8084` 抓取 Actuator；
不要在公网环境启用这个仅供本地学习的 Profile。

启动前，在当前 PowerShell 进程中生成临时本地秘密。以下赋值不会写入 `.env`、
不会持久化到用户或系统环境，也不会显示生成值；关闭该 PowerShell 窗口后即失效：

```powershell
function New-LocalSecret([int]$ByteLength = 32) {
    $bytes = [byte[]]::new($ByteLength)
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
        [Convert]::ToBase64String($bytes)
    }
    finally {
        $random.Dispose()
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}
$env:JWT_SECRET = New-LocalSecret
$env:CACHE_MANAGER_PASSWORD = New-LocalSecret
$env:NOVEL_AUTH_HMAC_SECRET = New-LocalSecret
```

这些随机值只适合本地运行。生产环境必须由密钥管理系统注入稳定且高熵的独立值，
不得复用 JWT、缓存管理和认证 HMAC 密钥。

打包配置 `novel-front/src/main/build/config/application.yml` 还通过环境变量读取
`REDIS_PASSWORD`、`NOVEL_HTTP_PROXY_USERNAME` 和 `NOVEL_HTTP_PROXY_PASSWORD`。
代理未启用时后两项可以留空；启用时必须从部署环境注入。此前进入 Git 历史的
固定 Redis/代理凭据应在对应服务端轮换，删除当前文件中的值不会清除历史记录。

先启动基础设施和监控容器：

```powershell
$env:GRAFANA_ADMIN_PASSWORD = 'change-me'
docker compose -f '.\compose.local.yml' up -d
docker compose -f '.\compose.local.yml' ps
```

在单独的 PowerShell 窗口启动应用。脚本会自动定位当前 worktree，并通过 Git 找到
共享主仓库中的外部 `config` 目录；它激活 `dev,monitoring`，同时保留
`application.yml` include 的 `website,alipay,oss` Profiles：

```powershell
& '.\performance\start-front-monitoring.ps1'
```

应用和容器就绪后运行只读验收：

```powershell
& '.\performance\test-observability-config.ps1'
& '.\performance\check-observability.ps1' -GrafanaPassword 'change-me'
```

常用页面：

- Prometheus Targets：<http://127.0.0.1:9090/targets>
- Prometheus Alerts：<http://127.0.0.1:9090/alerts>
- Grafana：<http://127.0.0.1:3000>

Grafana 会自动加载 `Novel-Plus Overview`，不需要手动创建数据源。Dashboard 主要
回答四类问题：请求是否变慢或报错；章节缓存是否真正减少数据库回源；Kafka 是否
发送失败或产生积压；JVM 和数据库连接池是否接近容量边界。

Dashboard 的缓存命中率是完整章节查询的业务命中率，不是 Redis 服务所有命令的
全局命中率。告警阈值只用于本地演示和故障发现，上线前必须根据真实流量基线调整。

### 首页阅读推荐：监控与只读验收

推荐刷新每五分钟执行一次，Redis 快照 TTL 为十五分钟。Dashboard 的四个推荐面板分别
展示 `novel_home_recommendation_source_total` 的来源速率、
`novel_home_recommendation_refresh_total` 的刷新结果、
`novel_home_recommendation_generation_seconds` 的平均生成耗时，以及
`novel_home_recommendation_local_age_seconds` 的最大值。最后一个 gauge 仅表示各
`novel-front` 进程最后一次成功生成的本地快照年龄，不能当作多实例 Redis 快照的全局
新鲜度。不要为了看见数值而用 `or vector(0)` 伪造缺失的运行时 series。

`HomeRecommendationRefreshDbErrors` 在五分钟窗口出现 `db_error` 即告警；
`HomeRecommendationSnapshotStale` 在本地年龄超过 900 秒并持续五分钟后告警。
运行时检查会要求这些指标已有真实 series，并验证两条规则和四个面板已经由
Prometheus/Grafana provisioning 加载。

在本地依赖和 monitoring 前端已启动时，以下验收只发 HTTP GET，只对 Redis 执行单键
GET，并只向 MySQL 发 CTE/SELECT；它不会刷新快照、删除/写入 Redis、改写
`book_setting` 或伪造阅读 credit：

脚本中的两条 SQL 是固定查询；正则 guard 只用于阻止维护时误改，不是数据库权限边界。
若在共享或公网数据库运行，应另行使用仅授予 `SELECT` 的验收账号。

```powershell
& '.\performance\test-home-recommendation-script.ps1'
& '.\performance\check-home-recommendation.ps1' -MySqlPassword '123456' -RedisPassword '123456'
& '.\performance\check-observability.ps1' -GrafanaPassword 'change-me'
```

验收会比较首页分组、Redis 快照与基于该快照 `generatedAt` 窗口的 SQL 排序，并确认
click/new/update 排行接口各自可用。若前后读取发生变化，或当前数据已无法重建
`generatedAt` 时刻的快照排序，脚本会以 `INCONCLUSIVE` 结束；停止并在没有并发刷新、
阅读统计或配置变动时重试，不能把它报告为算法通过或失败。

应用不可用告警演练时，只停止 `novel-front`，不要停止 Prometheus。等待超过一分钟
后在 Alerts 页面确认 `NovelFrontDown` 进入 firing；重新用启动脚本运行应用后，
告警应恢复。该演练不需要停止 MySQL、Redis 或 Kafka。

## 阅读参与度运行手册

### 启动与密钥

在当前 worktree 根目录执行；本地 Redis 为 6380/123456、Kafka 为 9092。
先只读识别 8083/8084 的监听进程与 Actuator health，确认是当前 worktree 的
novel-front 后，才在原启动窗口 Ctrl+C 并重启。不要停止来源不明的进程。

```powershell
Get-NetTCPConnection -LocalPort 8083,8084 -State Listen | Select-Object LocalPort,OwningProcess
Invoke-RestMethod 'http://127.0.0.1:8084/actuator/health'
docker compose -f '.\compose.local.yml' up -d
docker compose -f '.\compose.local.yml' ps
& '.\performance\start-front-monitoring.ps1' -RedisPort '6380' -RedisPassword '123456'
```

启动脚本依次使用 `-ReadingIpHmacSecret` 参数、已有环境变量
`NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET`、32-byte CSPRNG 随机值。
随机值只在本次 Maven/Java 子进程继承的环境中使用；脚本退出时恢复调用进程的原值。
脚本只报告已生成密钥，不打印密钥，也不把密钥放到 JVM 命令行。
已有 secret manager 注入环境时直接运行脚本即可；也可用
`-ReadingIpHmacSecret $secretFromSecretManager`（不要把实际值写入命令历史）。

公网部署必须注入稳定、高熵的 secret，所有应用实例使用一致的值；随机本地值重启后
会改变 IP HMAC，不能用于公网。应用端口只能由可信 Nginx 访问，防火墙禁止浏览器
直接访问；Nginx 必须覆盖 `X-Real-IP`，不能透传客户端自报的值，应用只信任明确配置
的代理地址。Actuator/本地 monitoring Profile 也不得直接暴露到公网。

### 自动验收

在无其他阅读流量的本地实例上先跑普通 smoke。脚本使用独立 WebRequestSession
保留匿名 Cookie，默认只读查询该书第一个非 VIP 章节，再从真实 HTML 提取页面 token。
序列 1、重复 1、2 应产生 accepted +2、duplicate +1、credited seconds +60、
Kafka success +2；发送后最多轮询十秒。HTTP 200 本身不代表计时成功，必须核对这些增量。

```powershell
& '.\performance\test-observability-config.ps1'
& '.\performance\check-reading-engagement.ps1' -BookId 2055879962859147264
& '.\performance\check-observability.ps1' -GrafanaUser 'admin' -GrafanaPassword '123456'
& '.\performance\check-reading-engagement.ps1' -BookId 2055879962859147264 -VerifyIpLimit
```

已知章节时可加 `-ChapterId 2055884263706857472` 省去数据库查询。
IP 演练必须在普通 smoke 之后运行：121 个独立 session，逐个加载章节并发送 sequence 1，
请求固定覆盖 `X-Real-IP: 198.51.100.77`，期望 120 accepted、1 ip_rate_limited。
全部请求必须在 60 秒窗口内完成；重跑前至少等待 61 秒并停止其他测试流量。
这会真实发送 120 条测试事件；不做 Redis 清理，页面 key 两小时、限流 key 两分钟、
日额度 key 两天自然到期。脚本不打印 Cookie、页面 token、Redis key、session hash 或 IP HMAC。

### 故障演练及恢复

仅本地学习环境运行，先确认两个依赖健康；每个演练一次只停止一个依赖，并在
`finally` 中启动它、最长 120 秒确认 healthy。MySQL 始终保持运行，不改数据库，
不使用 KEYS、SCAN、通配符删除或 FLUSH。Docker 权限不可用时预检直接失败。

```powershell
& '.\performance\check-reading-engagement.ps1' -BookId 2055879962859147264 -FailureDrill Redis
& '.\performance\check-reading-engagement.ps1' -BookId 2055879962859147264 -FailureDrill Kafka
docker compose -f '.\compose.local.yml' ps
```

Redis 演练先正常读一次章节，停 Redis 后再次读取，验证 HTTP 200、正文元素仍存在、
没有 readingPageVisitId/data-page-visit-id。Redis 故障时页面继续经数据库读取，
注册失败只禁用心跳；已持有 token 的心跳会计入 redis_error，不获得 credit。
Kafka 演练在 Redis 健康时获得新 token，Kafka 停机后发两个心跳，响应仍应 HTTP 200，
最终 failed +2。脚本为异步失败回调最多等待 150 秒；本地 delivery.timeout.ms 为 3000。
已获 credit 不回滚、不同步回退写 MySQL，也没有补发保证；failed 表示需要关注的事件损失。
如果 finally 无法恢复，立即 `docker start novel-redis` 或 `docker start novel-kafka`，
再用 `docker compose -f '.\compose.local.yml' ps` 确认 healthy，期间不继续下一项演练。

### 指标、隐私与手动焦点测试

`novel_reading_heartbeat_total` 的有限 result 值为 accepted、duplicate、invalid_page、
session_rate_limited、ip_rate_limited、daily_cap_reached、redis_error。
`novel_reading_credited_seconds_total` 只计服务器接受的秒数；
`novel_reading_kafka_send_total` 的 result 仅 success/failed，代表最终发送确认。
`novel_reading_redis_gate_seconds` 观察原子 gate 的耗时。
Grafana 增加 Reading Heartbeat Outcomes、Reading Credited Seconds / s、Reading Kafka Send / s。
已有监控容器运行时，更新规则后执行 `docker compose -f '.\compose.local.yml' restart prometheus`；
Grafana 会轮询已挂载的 dashboard 文件。两者必须挂载当前 worktree 的 monitoring 目录；
旧目录启动的监控容器需要先切换到本 worktree 部署，再验证新面板与规则确实加载。
ReadingEngagementRedisErrors 对最近五分钟错误信号持续为正一分钟告警；
ReadingEngagementKafkaPublishFailures 无额外等待，在评估到五分钟内发送失败时告警。
这些 PromQL 只按有限 result 聚合，不含书籍、章节、Cookie、hash、IP 等高基数标签。

桌面浏览器与移动端分别打开可读章节，Network 过滤 heartbeat：保持页面可见且有焦点
完整 30 秒才出现 sequence 1；20 秒时切走应舍弃不足 30 秒的片段，返回后重计完整
30 秒。桌面测试切换窗口、标签页；移动端测试切后台、锁屏再返回。离开页面不补发，
网络失败不立即重试，下一次完整活跃周期才发送递增 sequence。普通 smoke 不执行浏览器
JavaScript，因此不能替代这项人工验收。

7 天 Cookie 是匿名浏览器近似身份，并不识别真实的人：同人多浏览器/清 Cookie 可重复，
多人共用浏览器会合并。Cookie 生命周期与服务端日聚合相互独立：Cookie 可跨日继续用，
statDate 按 Asia/Shanghai 服务端日期逐日计算；每日额度按匿名 session + 书 + 章节限制。
Redis 中只保留必要的短期散列/限流状态，Kafka 事件不含 Cookie、session hash、IP HMAC 或原始 IP；
日志和 Prometheus 不引入这些身份字段。下一阶段再将 Kafka 消息批量聚合到 MySQL 日表，
明确幂等消费、日界线、重试和丢失语义；本阶段没有日表消费者，不把 accepted 当作已落库统计。
# Reading daily aggregation schema

## Daily aggregation acceptance

Keep the monitoring frontend running and execute in a second window:

```powershell
& .\performance\test-reading-daily-script.ps1
& .\performance\check-reading-daily-aggregation.ps1 -BookId 2055879962859147264
& .\performance\check-reading-daily-aggregation.ps1 -BookId 2055879962859147264 -FailureDrill Dlt
& .\performance\check-reading-daily-aggregation.ps1 -BookId 2055879962859147264 -FailureDrill MySql
```

Run only against the local demo, without concurrent reading traffic or topic/group
configuration overrides. The MySql drill temporarily stops **only** `novel-mysql`
and restores it in `finally`; other application operations may fail during it.
The first command simulates external boundaries; its PASS is not live verification.

Live checks publish UUID events with valid eight-byte Kafka Long keys, verify
30 seconds / one heartbeat, unchanged identical replay, book/date separation,
and a drained reading group. Dlt mode checks exactly one new DLT record and its
event ID, plus a later normal credit. MySql mode requires actual retry metric
growth before restoration and exactly one resulting credit.

The retry counter records failed redeliveries (attempt > 1), not retry starts.
An immediately successful retry may never create this series. MySql mode waits
for a failed retry before restoring the database, defaulting to a bounded 180
seconds (`-RetryWaitSeconds`, range 60..240). Database connection acquisition,
exception translation and rollback can each delay the callback. A timeout is
still a failed acceptance; it is not turned into a warning or a fabricated zero.

Input BookId is SELECT-only for a local chapter. Payloads use randomly generated,
unoccupied synthetic book IDs and dates 2000-01-01/02, not live business rows.
Preexisting rows cause refusal rather than an unsafe restore/overwrite.
Only this run's UUIDs and isolated book/date rows are deleted after successful
drain. Failed runs retain evidence and replay protection; do not delete those
rows while messages may still replay. Kafka test/DLT records are retained.

In a local PowerShell window, run:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
& .\performance\test-reading-schema-script.ps1
& .\performance\apply-reading-aggregation-schema.ps1
```

The application script requires a healthy `novel-mysql` and targets only its
`novel_plus` database. It executes `doc/sql/20260911_reading_daily_aggregation.sql`
using UTF-8. `CREATE TABLE IF NOT EXISTS` preserves existing tables and rows;
this is initial schema creation, not an upgrade of existing table definitions.
The behavior test replaces Docker calls and never connects to a database.
MySQL failures return an error, not a successful application message.

## 认证安全验收与 Argon2 基准

### 本地邮件与应用启动

自动验收使用 Mailpit 截获测试邮件，不会向真实邮箱发送。SMTP 与 UI/API 端口只绑定
`127.0.0.1`。先在当前 worktree 启动依赖，并在启动应用的同一个 PowerShell 进程注入
本地高熵密钥；如尚未设置，先执行上方“本地可观测性”中的 `New-LocalSecret` 代码块，
不要把实际密钥写进脚本、提交记录或截图：

```powershell
docker compose -f '.\compose.local.yml' up -d mysql redis kafka mailpit prometheus grafana
& '.\performance\start-front-monitoring.ps1' -RedisPort '6380' -RedisPassword '123456' -UseMailpit
```

`-UseMailpit` 只覆盖本次本地 Java 进程的 SMTP host/port、关闭 SMTP auth/SSL，并使用
`acceptance@novel.local` 作为非敏感发件地址；不修改生产 Profile。Mailpit UI 在
`http://127.0.0.1:8025`，不得映射到公网。真实 163 邮箱只做人工可选检查。

### 脚本契约、基准与完整验收

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
& '.\performance\test-authentication-security-script.ps1'
& '.\performance\benchmark-argon2.ps1' -MemoryKiB 19456 -Iterations 2 `
    -Parallelism 1 -Samples 50 -TargetConcurrency 4
& '.\performance\check-authentication-security.ps1'
```

依赖已完整缓存但 Maven 仓库不可访问时，基准命令可加 `-Offline`。参数只能从脚本允许
列表选择，并受 512 MiB 并发内存预算约束。结果包含 encode/verify 在单线程和目标并发下
的 p50、p95 与吞吐，不输出原始密码或哈希。应在最终 Linux 云服务器上重新测量，再通过
环境变量调整 Argon2 参数；不能仅依据开发机结果降低安全下限。

完整验收创建随机 `@example.test` 邮箱和随机历史手机号：检查新注册 Argon2id、历史 MD5
首次登录迁移与第二次登录、验证码 10 分钟 TTL/单次消费/60 秒冷却、账户失败限制、IP
图片验证码升级、重置密码后旧 JWT 失效及认证指标。验证码从 Mailpit API 获取；脚本不读
应用日志、不反推 Redis 摘要，也不打印密码、验证码、Token 或哈希。`finally` 只删除本次
创建的用户和邮件，不执行 Redis `KEYS`、`SCAN`、通配符删除或 `FLUSH*`。

### 本地故障演练

容器停机必须双重显式授权，且一次只演练一个依赖：

```powershell
& '.\performance\check-authentication-security.ps1' -FailureDrill Redis -AllowContainerStop
& '.\performance\check-authentication-security.ps1' -FailureDrill MySql -AllowContainerStop
& '.\performance\check-authentication-security.ps1' -FailureDrill Kafka -AllowContainerStop
& '.\performance\check-authentication-security.ps1' -FailureDrill Smtp -AllowContainerStop
docker compose -f '.\compose.local.yml' ps
```

Redis/MySQL 应失败关闭，Kafka 停机不应影响认证，Mailpit 停机时公开响应仍保持统一且已发
验证码状态应被异步撤销。脚本只接受回环 URL，只停止固定名称的本地容器，并在 `finally`
恢复自己停止的容器；若出现 `CRITICAL`，立即按提示手工启动对应容器后再继续。

## 2C4G 生产部署验收

生产拓扑由 `compose.prod.yml` 定义：公网只到 Nginx；novel-front、MySQL、Redis、Kafka
不发布宿主机端口；Prometheus 与 Grafana 仅绑定 `127.0.0.1`。容器总内存上限约 3.2 GiB，
4 GiB Swap 只作为 OOM 保险。完整的 Ubuntu 初始化、HTTPS、备份、迁移和回滚步骤见
`deploy/README.md`。

提交或部署前运行静态配置契约和隔离脚本行为测试：

```powershell
& '.\performance\test-production-deployment-config.ps1'
docker run --rm -v "${PWD}:/workspace:ro" --entrypoint sh redis:7-alpine `
  /workspace/performance/test-production-backup-operations.sh
docker run --rm -v "${PWD}:/workspace:ro" --entrypoint sh redis:7-alpine `
  /workspace/performance/test-production-runtime-checker.sh
```

Linux 服务器上默认执行只读检查；首次上线或明确需要创建新备份时才使用开关：

```bash
./performance/check-production-deployment.sh
./performance/check-production-deployment.sh --allow-backup
docker compose --env-file .env.prod -f compose.prod.yml ps
docker stats --no-stream
```

检查器验证七个容器健康、公网 socket、首页/Actuator 边界、Prometheus target、两个 Kafka
consumer lag、容器内存限制、物理内存与 Swap、磁盘余量和最新 MySQL 备份。非零 lag 会明确
告警而不是伪装成零；默认模式不会创建备份或修改运行状态。

本地 Windows 基线和公网 Linux 结果不能直接横向比较。CPU 型号、虚拟化超售、磁盘、网络、
JVM 暖机和香港跨境链路都会改变吞吐与 P99，因此所有 2C4G 数字都是 environment-specific，
不是该架构在其他服务器上的通用承诺。公网压测必须限时、限流量，并持续观察 Nginx、Spring、
MySQL、Redis、Kafka、Prometheus 和 Grafana 的资源指标。
