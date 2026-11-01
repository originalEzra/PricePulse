# 阶段一：REAL ONCE 目标价确认（2026-11-01）

本次仅离线开发；正式 V10、正在运行的后端、上游 IEX 连接和正式数据均未加载或修改。禁止将本报告当作自然市场验收。

## 行为

| 场景 | ONCE | REPEAT |
| --- | --- | --- |
| 正常成交 | ABOVE 当前价 ≥ 阈值；BELOW 当前价 ≤ 阈值 | 保留原穿越条件 |
| 首次订阅、重启、断线或溢出后的合格基线 | 当前达到即确认，记录 RECOVERY 与真实基线原因 | 不跨缺口推断穿越；COOLDOWN 可以按现价重新激活 |
| 价格不变 | ACTIVE ONCE 仍可入选；完成后不再入选 | 原行为不变 |
| 创建、修改或恢复 ACTIVE ONCE | 在股票行锁内检查可信现价；满足则在同一事务写 COMPLETED、trigger 和 Outbox | 原行为不变 |
| 已接受成交重复、乱序或过期 | 原去重、水位、舍弃逻辑先执行，不补造提醒 | 原行为不变 |

`TargetReachContext` 区分 LIVE、RECOVERY、RULE_CREATED、RULE_ACTIVATED，保留确认时间、实际成交时间和基线原因。已验证的正常开盘跨越继续保留 OPENING_GAP 上一交易日参考上下文。创建确认复用已存在的真实成交外键，不生成行情点，图表按本次确认时间筛选和展示该触发。恢复确认，以及成交在规则创建前产生但随后才被接受的确认，也使用本次实际确认时间；quote 本身的原始纳秒时间不改，不能倒画为缺口期间或规则创建前的突破。

模拟器及已保存实验继续使用原纯 `evaluate()` 穿越语义；本轮只调整 REAL 的 `evaluateReal()`，没有把任何模拟状态引入 REAL。

## 可信现价与并发

创建确认要求：ALPACA/iex、REAL、PROCESSED、与已持久化 accepted_sequence / event_time_ns 一致、股票最新价一致；事件、接收和提交时间在 `liveMaxAge` 内（现有默认 120 秒），不接受未来成交。还要求当前连接健康、该股票 ACK 属于当前 intent、最新成交 continuity 属于当前 lane，且 lane 没有尚未处理的任务。

任一条件不足则保留 ACTIVE，等待下一笔合格成交。PENDING 但仍保有订阅需求和名额的 ONCE 可以创建；不能把它显示成已经确认订阅。FAILED 且无名额的请求仍拒绝。REPEAT 的订阅门槛保持原样。

创建和成交都先锁同一 stocks 行；规则生效时间由创建时 Clock 记录，未来尚未生效的 ONCE 不确认。索引 Token、规则版本条件更新、trigger_key 和 Outbox 保持原有事务约束。新建规则与报价并发时，先后任一种顺序最终只触发一次。读到旧价格而新报价排队时选择等待，避免把队列中的旧状态当成当前确认。

## Redis 兼容

原四个 score band 继续负责 REPEAT ACTIVE / COOLDOWN；另加两段专用于 ACTIVE ONCE。查询始终包含当前满足区间，前价为 null 或相等也不会遗漏。最大 score 5,999,999,999,999,999，低于 2^53，整数可精确表示。

Redis marker 采用 `reachable-once-v1:<MySQL token>`；旧 marker 自动 MISS 并由 MySQL 重建，无须删除正式 Redis。数据库 UUID Token、SQL 权威校验、回滚后 Token 不一致重建保持不变。本阶段不增加迁移。

## 主要改动位置

| 职责 | 源码 |
| --- | --- |
| REAL ONCE 判定与原 REPEAT / SIM 判定分离 | [RuleTransitionEngine](../once-only-model/acceptance.md#历史实验与源码)、[AlertEvaluationService](../../src/main/java/com/ezra/pricepulse/alert/AlertEvaluationService.java) |
| 创建事务与可信报价门槛 | [AlertRuleService](../../src/main/java/com/ezra/pricepulse/alert/AlertRuleService.java)、[OnceRuleActivation](../../src/main/java/com/ezra/pricepulse/alert/OnceRuleActivation.java)、[RealMarketRuntime](../../src/main/java/com/ezra/pricepulse/market/RealMarketRuntime.java) |
| 派生索引与版本 | [RuleIndex](../../src/main/java/com/ezra/pricepulse/matching/RuleIndex.java)、[RuleIndexEntry](../../src/main/java/com/ezra/pricepulse/matching/RuleIndexEntry.java)、[ThresholdIndex](../../src/main/java/com/ezra/pricepulse/matching/ThresholdIndex.java) |
| 事件时间与展示 | [TargetReachContext](../../src/main/java/com/ezra/pricepulse/alert/TargetReachContext.java)、[MarketDataMapper](../../src/main/java/com/ezra/pricepulse/market/MarketDataMapper.java)、[DashboardView](../../frontend/src/views/DashboardView.vue) |
| 集成证据 | [ReachableOnceInfrastructureIT](../../src/test/java/com/ezra/pricepulse/market/ReachableOnceInfrastructureIT.java) |

## 验证

运行入口：`bash scripts/run-real-pipeline-checks.sh`。脚本冻结源码到忽略目录 target，排除 application-local.yml 和凭据，使用 Testcontainers MySQL 8.4.7、Redis 7.4.6、独立 RocketMQ 5.3.2；不覆盖正式 target/classes。

- 单元测试：150 项。
- 阶段一首次完整集成回归 88 项；联合验证再增加 2 项延迟成交确认时间及 1 项未来处理时间拒绝测试，ReachableOnceInfrastructureIT 从 12 增至 15。最终联合计数见阶段二报告。
- 新增测试覆盖上下对称、等价边界、创建确认、陈旧/未确认价格等待、恒价、恢复未达标、旧水位、重复/冲突、缺失及旧版 Redis marker、创建和报价并发、Outbox SQL 故障的全事务回滚，以及真实当前 lane 的 ACK/continuity 校验。
- 旧历史夹具显式设置成交前的生效时间；旧 ONCE 首笔抑制断言改为新可达语义，原 REPEAT 基线与开盘资格断言继续保留。
- 前端现有 50 项测试通过，另补 1 项确认上下文展示测试通过；TypeScript / Vite build 通过。

本地完整日志和 XML 仅在 target，不上传大量产物。本次未执行性能实验；此前穿越性能报告不能自动等同于新候选语义的吞吐。

## 待验证

后续需受控加载和自然市场验收：真实连接新鲜度、实际 IEX 恢复首笔及自然开盘场景。未恢复时已到价、恢复时又离开的瞬间无法由当前价推断；不补偿历史逐笔，不声称消除所有漏报。阶段二告警独立补充故障可见性。
