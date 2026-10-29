# V2-P2：开市只读采样与隔离处理验收

日期：2026-10-29，Australia/Sydney。**P2 仍为 PARTIAL；正式写入关闭，P3 未开始。**

本记录保留尚未升级开发库时的开市补验结果。此后用户确认执行了本机备份、V6 升级和只读接入，最新运行状态见 [本机升级记录](v2-p2-local-rollout.md)；下文的 V3／未重启为当次历史状态。

本轮补正常交易时段的接收证据、一次使用实际采样的临时库处理实验，以及关闭握手复验。没有重做历史 1M 下载、前端图表或整套既有验收，没有迁移或重启开发后端。

## 当前本机状态

- 后端 18081 健康，前端 5173 运行；18080 属于另一个 Docker 服务，未操作。
- 开发库已执行的数字迁移为 V1–V3，V4–V6 未应用，四张 P2 行情表尚不存在。
- 开始及结束检查时开发库报价均为 961、触发 5、Outbox 5、通知 5；V3 和 P2 表数量为 0 的结果未变。原后端／前端仍由相同进程监听，后端健康为 UP。
- `price-pulse.alpaca.business-writes-enabled=false` 保持原值；独立采样无 Spring／业务库连接，处理实验只连接临时 MySQL／Redis，MQ 关闭。
- 未提交、推送、reset、clean，也未修改私人环境及 IDEA Run 配置。

## 正常时段实际接收

使用生产 Java `AlpacaStream` 和 `AlpacaProtocol`，固定 IEX／五股。只取最近十分钟的历史及交易日历验证正常时段：1 页、45 根已闭合分钟线，没有重复下载一个月。

两个观察窗口各 120 秒，中间主动关闭并自动重新认证／订阅：

- 悉尼：2026-10-29 01:13:43–01:15:43、01:15:46–01:17:46。
- 纽约：2026-10-28 10:13:43–10:15:43、10:15:46–10:17:46。
- 统计窗口合计 240.014 秒；重连间隔不计入接收速率。该次使用关闭修正前的客户端，修正后另有下述短复验。

| 股票 | 收到的成交消息 | 符合当前生产条件过滤的成交 |
| --- | ---: | ---: |
| AAPL | 146 | 71 |
| MSFT | 172 | 76 |
| NVDA | 244 | 109 |
| AMZN | 133 | 49 |
| GOOGL | 200 | 109 |
| 合计 | 895 | 414 |

411 笔条件为 `@`，3 笔为 `@,F`；其余 481 笔条件含 `I`，按当前明确的保守策略排除，未静默改变成交资格。五股在两个连接中均有实际合格成交。

| 指标 | 观测值 |
| --- | ---: |
| 原始／合格成交平均速率 | 3.729 / 1.725 笔/秒 |
| 聚合五股的 UTC 一秒峰值 | 原始 18、合格 9 笔 |
| eventTime → 接收回调 P50 / P95 / P99 / 最大值 | 126.40 / 134.08 / 155.27 / 183.62 ms |
| 负延迟样本 | 0 |
| 带亚微秒精度的合格时间戳 | 414 / 414 |
| 连接／成功订阅 | 2 / 2 |
| 缺口回调／实际 Pong | 1 / 17 |

该延迟是两个时钟时间戳之差，含网络和本机调度，依赖本机与供应商时钟；未独立校准时钟，也不代表数据库处理或通知延迟。回调耗时仅覆盖探针协议解析和统计。短采样不代表开盘洪峰、长期负载或全市场速率。

原始摘要和供临时实验使用的合格成交保存在忽略目录：

- `target/v2-p2/open-session-readonly.json`
- `target/v2-p2/open-session-trades.json`

## 实际采样的临时库处理实验

`MarketSampleCapacityIT` 显式开启才运行；使用可丢弃的 MySQL 8.4.7、Redis 7.4.6，Hikari 连接池 5。没有供应商、开发库或 MQ 连接，不启用应用的正式接入开关。

五股各创建 100 条合成 REPEAT 上破规则，共 500 条；阈值分布在本次采样价格范围。将上述 414 笔真实采样按原接收顺序、相对间隔缩为 1/10，交给实际 `MarketQuoteLane` 和带事务代理的 `QuoteIngestionService`。每股队列容量 1024；真实 `stocks FOR UPDATE`、Redis 索引、匹配、触发和 Outbox 事务均参与。

价格及事件时间没有重写。测试时钟采用原 receivedAt 加实验中实测的排队等待；这是历史采样处理实验，不能称作当前实盘写入。切换捕获连接前排空上一段，再为五股建立新连续段。

| 指标 | 结果 |
| --- | ---: |
| 提交／完成／PROCESSED | 414 / 414 / 414 |
| 溢出／处理故障 | 0 / 0 |
| 新基线／基线触发 | 10 / 0 |
| 临时触发／Outbox | 1,260 / 1,260 |
| 临时通知／模拟实验 | 0 / 0 |
| 含提交的事务 P50 / P95 / P99 / 最大值 | 13.26 / 51.80 / 82.97 / 164.74 ms |
| 队列等待 P95 / P99 / 最大值 | 46.06 / 57.76 / 83.01 ms |
| 入队到事务返回 P95 / P99 | 63.02 / 84.44 ms |
| 本次完成速率／实验墙钟时长 | 17.45 笔/秒 / 23.73 秒 |

触发与 Outbox 计数对账一致，基线没有触发。**上述 1,260 条全部属于临时测试库，未投递 MQ，不能作为真实提醒到通知的证据。**

报告：`target/v2-p2/sample-capacity.json`；输入 SHA-256：`24be39353fc69bf487118c318f90ce80d1dc988b23e893ae7327ea06c690b8e9`。

该结果说明本机在这组输入和规则规模下能够处理加速采样。没有测定最大／持续容量，没有并发真实规则编辑、历史同步或 MQ 消费；事务耗时包含股票锁查询，并非独立的锁等待指标。V1 性能数字没有借用于本次结论。

## 关闭修正及遗留

检查发现原实现有三个关闭生命周期问题：

1. `sendClose` 的 Future 完成只表示发出 Close，原三秒定时只约束发送，没有约束对端确认。
2. 主动停止后忽略文本回调但不继续请求接收，可能使对端 Close 留在尚未消费的数据之后。
3. 收到对端 Close 时直接 `abort`，可能抢在 JDK 自动回送 Close 前终止连接。

依据 [JDK WebSocket 的 sendClose / request 约定](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/WebSocket.html)和 [Listener.onClose 约定](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/WebSocket.Listener.html)，改为停止业务回调后继续消费输入、等待实际关闭回调，并对整次关闭握手设置三秒超时；收到对端关闭时让 JDK 完成回送。非预期关闭继续标记缺口并重连，停止时的异常关闭保留安全错误码。

补充四项回归：等待对端确认且不转发停止后的成交、对端正常关闭仍算连续性缺口但不抢先 abort、停止时 1006 不冒充正常关闭、已发出 Close 但无确认时超时 abort。

**修正没有消除供应商在线 1006。**使用修正后的生产客户端再采样两个 30 秒窗口：239 笔消息、102 笔合格成交，重连成功、5 次 Pong；主动重连及最终停止各收到一次 1006。`gracefulCloseAcknowledged=false`，最终安全错误 `STOP_CLOSE_1006`。

复验报告：`target/v2-p2/close-check-readonly.json`。本机原生 Node 在 P0 也有 1006 记录，见 [P0 已知现象](v2-phase-0.md#连接关闭的已知现象)；现有证据不足以确定供应商、网络或运行时中的具体原因。没有通过改写关闭码、取消警告或放宽成正常关闭来“通过”验收。

## 验证与复现

| 检查 | 本轮结果 |
| --- | --- |
| 相关单元回归 | 21 项通过：AlpacaStream 9、MarketData 10、MarketQuoteLane 2，含 4 项新增关闭用例 |
| 开市 Java 独立只读观察 | 1 项通过，保留关闭警告 |
| 修正后的在线复验 | 1 项通过，关闭确认仍未通过 |
| 临时 MySQL／Redis 处理实验 | 1 项通过 |
| 应用打包、脚本语法及 diff 空白检查 | 通过；JAR 含修正后的接入器，不含测试入口、采样测试类或私人环境文件 |

没有重复跑原先 58 单元／42 集成／Broker／前端全套。在线测试只把“实际接收与重连”作为断言，关闭握手的独立布尔值仍为 false；测试进程成功不意味着 P2 全通过。

本轮摘要及源码指纹保存在 `target/v2-p2/open-session-verification.json`；原 `verification.json` 和 `provider-readonly.json` 保留为上一轮历史记录。

```sh
# 无供应商、数据库依赖的相关回归
./mvnw -B -ntp -Dtest=AlpacaStreamTest,MarketDataTest,MarketQuoteLaneTest test

# 当前处于正常交易时段时采样；两个窗口默认各 120 秒
node --env-file=.local/alpaca.env scripts/v2-p2-readonly-smoke.mjs --open-session

# 保留原样本，单独记录两个 30 秒窗口的关闭复验
PRICEPULSE_SAMPLE_SECONDS=30 PRICEPULSE_SAMPLE_REPORT_PREFIX=close-check \
  node --env-file=.local/alpaca.env scripts/v2-p2-readonly-smoke.mjs --open-session

# 仅在上述样本存在时，对可丢弃的测试库执行，未接入 MQ
PRICEPULSE_RUN_SAMPLE_CAPACITY=true ./mvnw -B -ntp \
  -Dit.test=MarketSampleCapacityIT test-compile failsafe:integration-test failsafe:verify
```

Alpaca 的流连接可能只允许一个实例，先确认没有用户现有接入占用；不要并发运行两个在线探针。[官方连接限制](https://docs.alpaca.markets/us/docs/streaming-market-data)。凭据仅从本机忽略文件继承，不放在命令参数或输出中。

## 阶段结论与下一步边界

开市接收采样、纳秒保留、重订阅和一次隔离的加速处理实验已有证据。仍未完成：供应商正常关闭握手、实际行情进入业务处理直至 MQ／正式通知的端到端验收、开发库升级后的运行核验，以及数据展示／自动提醒用途许可核对。最大／持续容量及并发编辑／历史同步的综合压力不在本次结论内。

本轮在 P2 暂停。若安排真实提醒端到端验收，需先向用户说明目标库、备份／升级方式、测试用户与规则、写入时长和恢复措施，取得新的明确确认；本报告不授权开关变更或进入 P3。

## 本轮修改文件

- 生产关闭流程：`market/AlpacaStream.java`。
- 相关回归及实验：`market/AlpacaStreamTest.java`、新增 `AlpacaOpenSessionReadOnlyIT.java`、`MarketSampleCapacityIT.java`，均在 `src/test`。
- 只读启动脚本：`scripts/v2-p2-readonly-smoke.mjs` 增加开市模式，直接执行选定验证，不附带重复运行全部旧单元测试。
- 状态与交接：本文、`docs/v2-phase-2.md`、`docs/v2-handoff.md`、`docs/v2-product-and-technical-plan.md`、`README.md`。

没有修改迁移、前端、正式开关或私人配置；既有未提交改动保留。
