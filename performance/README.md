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
