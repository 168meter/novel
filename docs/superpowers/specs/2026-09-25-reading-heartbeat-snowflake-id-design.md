# 阅读心跳雪花 ID 精度修复设计

## 问题与目标

生产数据使用 19 位雪花 ID。阅读心跳脚本把 `data-book-id` 和 `data-chapter-id`
转换为 JavaScript `Number`，再调用 `Number.isSafeInteger`。真实 ID 超过
`Number.MAX_SAFE_INTEGER`，因此启动载荷被静默拒绝，浏览器不发送心跳，
`book_reading_daily` 始终为空。

本次目标是让浏览器全程以十进制字符串保存和发送书籍、章节 ID，由 Spring/Jackson
绑定为 Java `Long`。不得经过 `Number`、浮点运算或任何可能损失精度的转换。

## 范围

仅修改阅读心跳 JavaScript 和直接相关测试。保持以下内容不变：

- 心跳首次发送前等待一个完整的 30 秒活跃区间；
- 页面可见且窗口有焦点时才累计完整区间；
- `pageVisitId` 格式、序号、Redis 闸门、Kafka 事件及 MySQL 聚合语义；
- 点击量统计、首页推荐算法、用户标签布局和数据库结构。

## 数据流

章节模板继续输出十进制 `data-book-id`、`data-chapter-id` 和 32 位十六进制
`data-page-visit-id`。浏览器只用正整数正则验证两个 ID，不把它们转换为数值。
心跳请求 JSON 将两个 ID 作为字符串发送。后端 `ReadingHeartbeatRequest` 继续声明
`Long bookId` 和 `Long chapterId`，由 Jackson 完成受 Java `Long` 范围约束的绑定，
后续服务、Redis、Kafka 和数据库代码无需改动。

示例请求：

```json
{
  "bookId": "2055879962859147264",
  "chapterId": "2055880123456789012",
  "pageVisitId": "0123456789abcdef0123456789abcdef",
  "sequence": 1
}
```

## 校验与错误处理

浏览器接受不带符号、无小数点且大于零的十进制字符串；`0`、负数、空值、字母和小数
仍使自动启动保持静默。超出 Java `Long` 上限的十进制字符串可以通过浏览器格式校验，
但后端绑定必须返回 4xx，不能进入业务链路。现有 `pageVisitId` 和 `sequence` 校验保持不变。

## 测试与验收

按 TDD 执行：

1. 先增加 19 位雪花 ID 自动启动测试，并断言发送 JSON 中两个 ID 与 DOM 原字符串完全一致；
2. 观察测试因当前 `Number.isSafeInteger` 路径而失败；
3. 删除数值转换，仅保留十进制正整数字符串校验；
4. 运行全部阅读心跳 JavaScript 测试；
5. 增加或确认控制器绑定测试，证明数字字符串能绑定为 Java `Long`；
6. 运行阅读心跳相关 Java 测试与必要回归；
7. 部署后打开章节并保持前台超过 30 秒，确认心跳指标增长、Kafka 消费成功且
   `book_reading_daily` 出现对应书籍当日记录。

部署验收不得依靠点击量，因为点击统计与阅读心跳是两条独立链路。
