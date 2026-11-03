# 当前 V11／V12：设计、发布与验收

更新日期：2026-11-03，Australia/Sydney。此页补充已提交的发布计划和离线报告；旧文档中“尚未发布／仍为 V10”等文字是各自日期的历史状态。

## 当前发布

- [PR #4](https://github.com/originalEzra/PricePulse/pull/4) 已合并。2026-11-01 已受控加载合并提交 `16248c34978b3afaba5feca1540edf071095ac72`，正式库依次执行 V11、V12，运行 JAR 和前端资产哈希已核对。
- V11 引入 REAL 目标价可达确认、跨重试监控故障、持续 30 秒中断告警、队列丢弃告警及恢复通知。
- V12 精简为一次性目标价模型，REAL 与新模拟共用纯判定；不再提供 REPEAT、COOLDOWN 或重新激活。
- 截止点一致性备份、完整隔离恢复、克隆迁移及 V10 恢复回滚演练通过；迁移保留原规则、触发、通知、行情与模拟事实。私人备份和运行配置未上传。

`V12` 在这里指本轮发布采用的数据库／规则模型版本；Maven 构件版本仍以 `pom.xml` 为准。

### 前端修复

2026-11-03，经用户单独批准，前端已更新至提交 `20bb5215a9e64e2963b6ccdb0b30017b1f226372` 的构建；后端保持原 `16248c3` 进程，数据库仍 V12。本次文档与前端补丁一并同步，后端没有因该补丁重新部署。

修复的是模拟实验替换时，Vue 响应式参数被直接 `structuredClone` 而抛出 DataCloneError，导致确认弹窗关闭但请求未发出的缺陷。请求改为冻结 JSON 传输快照，并将准备失败纳入可见错误处理；保留幂等请求键和原参数重试。56 项前端测试、TypeScript 与构建通过，包含真实 Vue 响应式对象、旧实验替换、结果刷新、丢失响应重试及准备失败保留旧结果。

只切换前端静态构建和预览服务，原 main 产物保留可回退；未重启后端、迁移数据库或自动替用户创建／替换实验。旧标签页需刷新才能加载新脚本。

## 规则与接口行为

| 场景 | 当前行为 |
| --- | --- |
| ABOVE／BELOW | 当前可信价格分别 ≥／≤ 阈值即可达到，无需证明前后价格穿越 |
| ACTIVE → COMPLETED | 股票锁下校验版本与 ACTIVE 条件；规则完成、触发记录和 Outbox 同事务 |
| 创建／编辑 ACTIVE | 复核最新成交的新鲜度、来源、订阅与连续性；可信且达标时在同一事务确认，否则等待下一笔合格成交 |
| 恢复首笔 | 达标可提醒，保留 RECONNECT／RECOVERY 上下文和实际确认时间；不虚构缺口内的突破时间 |
| 已完成规则 | 不可编辑或重新激活；再次提醒需新建规则 |
| 取消规则／取消监控 | 逻辑删除；取消个人股票监控需确认并在同一事务取消需求、逻辑删除本人相关规则，保留触发与通知 |
| 旧接口字段 | `triggerMode`／`rearmPrice` 拒绝，旧暂停／恢复入口不再提供 |
| 模拟 | 新生成器共用目标价判定，冻结规则快照并独立运行；旧生成器实验保留只读，禁止按新语义继续推进 |

历史规则快照可以保留旧字段，这不代表当前仍支持旧模式。V12 迁移检查所有旧规则，包括 DELETED；存在不兼容行就拒绝，不自动转换、停用或删除。V12 删列后不能简单换旧 JAR 回滚，详见[迁移影响](../../once-only-model/migration-plan.md)和[发布与回滚计划](plan.md)。

## 当前架构与源码

真实成交复用同一条 Alpaca IEX WebSocket，经过单股串行有界队列进入 MySQL 事务。股票级行锁协调规则变更与行情处理，保留 SELECT 优先的成交去重、事件唯一约束、纳秒水位和连续性。持锁后用 Redis ZSet＋Lua 查询 ACTIVE 目标候选，Token 不匹配或索引缺失时从 MySQL 重建，再权威回查和条件完成规则。

价格触发与 Outbox 原子提交，发布器采用租约、退避和至少一次投递，RocketMQ 消费端以数据库唯一约束避免重复通知。监控异常使用独立故障／投递记录和 Outbox 类型，不能冒充价格触发。恢复须确认连接、相关订阅及处理链路状态；没有成交本身不是断线依据。

多用户共享去重后的上游订阅，个人需求持久化；预算预留、连接代次、意图版本与实际 ACK 共同决定界面状态。当前运行预算为 15 个去重股票，未验证第十六股或更高账户权限。

REAL 与 SIM 共用目标价纯函数，但模拟拥有独立实验、快照、批次、索引与事件，不调用正式 Outbox／MQ。新实验替换当前实验沿用原策略，单纯切股不删除实验。

| 能力 | 核心源码 |
| --- | --- |
| 目标价判定 | [TargetPriceMatcher](../../../src/main/java/com/ezra/pricepulse/alert/TargetPriceMatcher.java) |
| 规则并发与事务 | [AlertRuleService](../../../src/main/java/com/ezra/pricepulse/alert/AlertRuleService.java)、[AlertEvaluationService](../../../src/main/java/com/ezra/pricepulse/alert/AlertEvaluationService.java) |
| Redis 候选与 Token | [RuleIndex](../../../src/main/java/com/ezra/pricepulse/matching/RuleIndex.java)、[ThresholdIndex](../../../src/main/java/com/ezra/pricepulse/matching/ThresholdIndex.java) |
| 单股队列与接入 | [MarketQuoteLane](../../../src/main/java/com/ezra/pricepulse/market/MarketQuoteLane.java)、[QuoteIngestionService](../../../src/main/java/com/ezra/pricepulse/market/QuoteIngestionService.java)、[RealMarketRuntime](../../../src/main/java/com/ezra/pricepulse/market/RealMarketRuntime.java) |
| 共享订阅 | [MonitoringService](../../../src/main/java/com/ezra/pricepulse/monitoring/MonitoringService.java)、[MonitoringCoordinator](../../../src/main/java/com/ezra/pricepulse/monitoring/MonitoringCoordinator.java) |
| 故障计时与恢复 | [MarketFeedHealth](../../../src/main/java/com/ezra/pricepulse/market/MarketFeedHealth.java)、[MonitorIncidentService](../../../src/main/java/com/ezra/pricepulse/market/MonitorIncidentService.java) |
| Outbox／MQ | [OutboxDispatchService](../../../src/main/java/com/ezra/pricepulse/outbox/OutboxDispatchService.java)、[RocketMqOutboxPublisher](../../../src/main/java/com/ezra/pricepulse/outbox/RocketMqOutboxPublisher.java)、[NotificationMapper](../../../src/main/java/com/ezra/pricepulse/notification/NotificationMapper.java) |
| 模拟隔离 | [SimulationIsolationService](../../../src/main/java/com/ezra/pricepulse/simulation/SimulationIsolationService.java)、[SimulationService](../../../src/main/java/com/ezra/pricepulse/simulation/SimulationService.java) |

## 验证结果与边界

### 发布回归

V11／V12 发布回归为 144 单元、143 隔离集成、52 前端、15 只读探针及 8 Python 工具测试，单元／集成零失败、零跳过；TypeScript 和构建通过。包含临时 MySQL／Redis／RocketMQ、并发／回滚、索引缺失／Token 变化、故障计时／恢复、消息幂等及 REAL/SIM 隔离。

- [合并 main CI](https://github.com/originalEzra/PricePulse/actions/runs/41268709113)
- [离线回归明细](../../once-only-model/acceptance.md)（其“未加载”状态保留为当时记录）

这些是已执行的发布回归结果，更新 README 不意味着重新运行整套业务测试。

### 自然开市观察

2026-11-03 悉尼的只读观察，对应纽约 2026-11-02 交易日；以下成交统计截止悉尼 01:44:51／纽约 09:44:51：

| 项目 | 已验证结果 |
| --- | --- |
| 实际订阅和自然成交 | 十五股均已确认并收到近期成交，共 19,618 笔已处理 REAL |
| 恢复首笔可达 | 7 条自然提醒，均为 TARGET_REACHED／RECOVERY，每条唯一正式通知，规则 COMPLETED |
| 派生索引 | 十五股自然建立 V12 marker，Token 匹配；未强刷缓存 |
| 独立观察窗口 | 120.326 秒接收观察数增加 1,599；无新增重连、无队列溢出 |
| 通知链路 | 唯一正式消费者，MQ 积压与 inflight 为 0 |
| 模拟保留 | 四张实验表所有原行列哈希与发布截止点一致 |

已发生的 7 条提醒不能标成 OPENING_GAP 或普通 LIVE 提醒。自然开盘跳空、普通 LIVE 上下文、开市中自然持续故障／队列丢弃及 IEX 1006 仍未由该窗口验证。原始行情关联、运行输出和完整本机报告保存在忽略目录，不上传生产数据。

### 30 秒告警和性能口径

正式记录中有 12 个短故障未告警，3 个持续故障最终为每账户生成一次中断和一次恢复，没有重复投递事实或通知。它们发生在交易时段之前；持续故障的记录告警时间与首次故障相差约 16–17 分钟，同时存在明显时钟漂移及数据库／JVM 历史时间矛盾。

因此本轮只确认最终闭环和去重，**30 秒及时性不标 PASS**。30 秒是故障持续门槛，之后还需 Outbox／MQ／消费；进程暂停、主机休眠或网络完全不可用时不能保证第 30 秒送达。后续自然样本应分别核对首次故障、告警持久化和通知落库，并先校核时钟。

没有取得正式逐阶段提交 P95／P99、单股队列深度／最老等待或新版最大持续容量。`processed_at` 不是事务提交完成时间；窗口成交数也不是容量上限。旧穿越模型的[MySQL／Redis 对照](../../performance/v2.1-rule-selection-2026-10-31/report.md)和[队列性能报告](../../performance/realtime-processing/report.md)保留为历史实验，不外推为当前目标价模型的性能承诺。

现有[容量与时间边界审计](../../capacity-and-time-boundaries.md)中的规则配额、限流、归档和新容量测试是建议，不代表已经实现。
