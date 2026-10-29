# V2-P1：真实／模拟隔离基础

日期：2026-10-28，Australia/Sydney。状态：P1 代码与本地验收通过，已暂停；P2 尚未开始。

根据后续确认，先实施不依赖开市的隔离工作。P0 正常交易时段采样仍保留为启用真实行情写入前的检查；公开多用户展示的数据许可仍需核对，不因本阶段通过而自动完成。

## 1. 本阶段交付边界

交付后端隔离基础，不交付完整模拟产品：

- 四张模拟专用表、独立状态转换事务、按用户保存最近一次实验。
- 复用纯匹配决策和 Redis ZSet／Lua，真实与模拟分别加载状态、维护 Key 和版本。
- 从真实处理链路中关闭旧的共享模拟／回放写路径。
- 保留旧数据，将其与正式通知和消息投递隔离。
- 模拟页展示升级状态并禁用旧操作，避免用户继续写共享状态。

真实 WebSocket、历史缓存／时间线、起点选择、逐交易小时生成和播放 UI 分别属于 P2–P4，本轮没有实施。内部服务接受的是受信任的 prepared input，不是供用户任意提交价格／时间的 HTTP API。

## 2. 隔离方式

| 对象 | 真实业务 | 模拟实验 |
| --- | --- | --- |
| 最新价、顺序与索引版本 | 原股票行 | 自己的 simulation_runs 行 |
| 规则 | 原 alert_rules | 创建时复制自己的规则配置，独立状态／版本 |
| 触发 | 原 alert_triggers | simulation_rule_events |
| 索引 | `pricepulse:v2:real:rules:{stock:ID}` | `pricepulse:v2:sim:rules:{run:UUID}` |
| 写事务 | 真实状态＋触发＋Outbox | 价格批次＋快照状态＋模拟事件＋时钟 |
| 正式通知 | 保留 Outbox／RocketMQ／消费幂等 | 不创建、不投递 |

`RuleTransitionEngine` 只返回 NONE／TRIGGERED／REARMED 和目标状态。真实服务和模拟服务各自持久化，不共用带副作用的 evaluation 服务。`ThresholdIndex` 只负责脚本、分值和可重建缓存，数据库加载器由作用域提供。

模拟状态从 ACTIVE 开始（PAUSED 不参与、DELETED 不复制），与真实 COOLDOWN／COMPLETED 状态无关。原规则随后修改、暂停、删除或转移不会改变快照。源配置查询不使用用户列表接口的默认 limit。

### 行锁与外键

- 真实行情和规则管理继续共用原股票行锁。
- 模拟推进只锁自己的实验行，不更新股票或真实规则。
- 新建／替换先锁用户行，再锁其旧实验行，串行化同用户竞争；复制采用一致的 MVCC 快照。
- 起点解析回调在该 MVCC 事务内接收股票快照；未来 P3 可以从已缓存的数据解析起点，不能在事务内调用供应商网络。
- `simulation_runs.stock_id` 不建立指向真实股票行的外键。InnoDB 外键校验会隐式取得父行共享锁，可能将长快照事务与真实行情更新耦合；改由受信任服务读取并校验股票。股票目录无物理删除接口，起点信息保存在实验快照中。
- 模拟源规则 ID 仅作追溯，也不对真实规则建外键。模拟内部通过复合外键禁止将另一实验的批次／快照关联到当前事件。
- 用户行串行化只用于创建／替换；常规推进不持有用户行锁。大量规则快照创建的锁持有时间仍需在后续规模测试中度量。

## 3. 持久化与内部事务

新增迁移：

| 文件 | 内容 |
| --- | --- |
| `V4__isolate_real_delivery_from_legacy.sql` | 旧 quote_events 默认 LEGACY；Outbox 增加 QUARANTINED；旧待发／失败／已领取消息隔离 |
| `V5__create_isolated_simulation_storage.sql` | simulation_runs、simulation_rule_snapshots、simulation_steps、simulation_rule_events |

没有修改 V1–V3。真实历史缓存表和真实事件精确水位属于 P2，未提前加入。模拟时钟／事件已另存整数纳秒，DATETIME(6) 用于可读查询；价格保持现有十进制四位内核。

`SimulationIsolationService` 提供内部 begin／advance／只读查询基础：

- user_id 唯一，开始新实验必须提供预期旧 runId；失败回滚保留旧实验及子数据。
- runId 每次替换使用新 UUID；旧 runId 不可继续读写。
- 起始请求和推进请求有持久化幂等键／摘要；同 Key 不同内容冲突。已提交推进重试先返回旧结果，不会因预期版本落后再次生成。
- 推进校验版本／批次，价格点必须递增；prepared batch 限 1–60 点。本阶段不声称实现“60 交易分钟”生成器，P3 公共接口会固定生成 60 点并校验日历。
- 每批原子保存价格、规则事件、状态、时钟和版本；JSON 价格批次固定小规模，规则事件按行存储。
- 缓存每次状态变化同步新 UUID token；批内后续点使用新版本。回滚后的缓存版本分叉、缓存丢失可从该实验状态重建。
- 旧 Redis Key 只在替换事务提交之后删除；失败可交给 TTL，不会删除另一实验或真实股票索引。
- 读取实验不修改状态、不跑匹配、不创建通知。

尚无 `/api/simulations/*` 用户接口；这一基础不能绕过 P3 的真实起点校验、交易日历和确定性生成流程直接暴露。

## 4. 旧数据与消息

- V1 报价保留为 LEGACY，旧股票／规则／触发／通知不删除。
- 正式报价历史、触发历史、通知列表、未读计数、单条／全部已读均排除 LEGACY。
- 只有持久化 REAL 触发可创建 Outbox；领取发送时再次校验来源。
- 旧域待发消息置 QUARANTINED，不循环重试；旧 Broker 消息按原 schema 验证后确认消费并记录日志，不生成正式通知。
- 真实至少一次投递、租约回收、失败重试、trigger_id 消费唯一约束保持原有设计。
- `/api/market/quotes / replay / simulate` 的公开 POST 入口关闭。旧模拟／回放在服务层也拒绝执行，真实 HTTP Controller 同样拒绝，不能靠改前端参数绕开。
- 包内真实 ingest 仍可供后续受信任接入器使用，拒绝 REPLAY／SIMULATION policy。

测试中的历史价格输入转入仅 `src/test` 可用的 `RealQuoteTestSupport`，通过受控时钟测试原真实链路。测试控制器还限制在 integration／performance profile，打包 JAR 中不包含它，没有为压测重开生产接口。

## 5. 验证

以下为本轮最终结果，摘要与源码指纹保存在 `target/v2-p1/verification.json`。不同 profile 重跑的同一组单元测试不重复计数。

| 检查 | 结果 |
| --- | --- |
| 单元／契约 | 41 项通过，含 13 项纯状态机边界用例 |
| 原有 MySQL／Redis 集成 | 24 项通过 |
| V2 隔离集成 | 10 项通过 |
| V3→V5 升级演练 | 1 项通过 |
| 真实 Broker 通知链路 | 1 项通过 |
| 原压测脚本兼容性 | 100 条规则、50 次匹配采样、10 条通知洪峰；1 项通过 |
| P0 探针离线回归 | 15 项通过，无真实行情请求 |
| 前端类型／构建 | 通过 |

上述测试没有失败或跳过。数据库／Redis 集成共 35 项。应用 JAR 检查确认不含测试专用入口或私密环境文件。

验证重点：同股真实处理＋两用户实验并发；只复制自己的规则；配置冻结与 D1 初始化；重复／并发推进；创建竞争；删除后新建失败的整事务恢复；缓存故障／回滚／丢失恢复；纳秒保留；旧 runId／其他用户拒绝访问；内部复合外键；持有真实股票行锁时仍可初始化实验；旧通知隐藏、旧消息隔离及真实通知仍可消费。

首次小规模脚本运行发现其内部辅助方法仍调用旧 replay 服务，得到明确拒绝。该辅助方法已改为测试 fixture，复验通过；失败报告仍保留。脚本通过只是兼容性／故障恢复检查，不是全规模 V2 性能报告，更不能替代开市后的输入速率与容量验证。

通过的兼容性运行 ID 为 `e1712317-98ba-40e4-aac5-954898b77304`，报告在 `target/performance/`。本轮沿用 Java 25 运行时、Java 21 编译目标及现有中间件版本；没有升级依赖。现有上游兼容性警告仍保留，不影响本轮验收结果。

## 6. 修改文件

| 区域 | 文件 |
| --- | --- |
| 迁移 | `db/migration/V4__isolate_real_delivery_from_legacy.sql`、`V5__create_isolated_simulation_storage.sql` |
| 共用内核 | `alert/RuleTransitionEngine.java`、`matching/RuleIndexEntry.java`、`ThresholdIndex.java` |
| 真实适配 | `alert/AlertEvaluationService.java`、`AlertRuleResponse.java`、`matching/RuleIndex.java` |
| 模拟模块 | `simulation/SimulationModels.java`、`SimulationMapper.java`、`SimulationIsolationService.java` |
| 快照读取／串行化 | `AlertRuleMapper.java` 及 XML、`user/UserMapper.java` |
| 入口与来源 | `SecurityConfiguration.java`、`market/MarketController.java`、`MarketService.java`、`QuoteIngestionService.java`、`QuoteEvent.java` 及报价 Mapper XML |
| 正式通知 | `NotificationService.java`、`AlertTriggerDelivery.java`、通知 Mapper XML、`AlertTriggerMapper.java` |
| 可靠发布 | `OutboxMapper.java` 及 XML、`OutboxWriter.java`、`OutboxDispatchService.java`、`OutboxStatus.java` |
| 前端 | `frontend/src/views/SimulationView.vue` |
| 测试 | `RuleTransitionEngineTest.java`、`V2IsolationInfrastructureIT.java`、`V2MigrationInfrastructureIT.java`、`RealQuoteTestSupport.java`，以及现有 Infrastructure／Pipeline／Performance 测试适配 |
| 配置与文档 | `pom.xml` 的集成测试匹配范围、README、V2 计划／契约／阶段记录和旧实验台说明 |

## 7. 生效范围与下一阶段

本轮只在临时 MySQL 库测试了升级，没有迁移正在使用的开发库，也没有重启用户的 IDEA 进程。应用 JAR 已通过构建，实际运行旧版本的服务需要用新版本重新启动才能生效。

部署／重启顺序：停止旧 Spring Boot 接收及定时发布，备份开发库，再启动新版本执行 Flyway V4／V5。不要让旧共享写入服务与新版本并行访问同一库。旧模拟通知从正式列表消失是来源隔离，不是数据删除。

P2 尚未开始：真实源接入、时间／成交资格、历史缓存、只读时间线和真实时间坐标图表仍待实施。P3 才提供真实起点、日历推进及可恢复用户模拟操作。当前模拟页禁用属于阶段交付的明确状态。

完成本轮验收后暂停，等待用户确认，不自动进入 P2。
