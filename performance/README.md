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
