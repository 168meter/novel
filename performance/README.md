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
