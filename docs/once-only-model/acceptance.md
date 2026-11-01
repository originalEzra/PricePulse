# 一次性目标价模型：离线验收

本轮仅离线开发；正式 V10 后端、前端、数据库、Redis 和 MQ 不加载本分支。开发副本为 `price-pulse-once-only`，本地分支 `codex/pricepulse-once-only-20261101`，基础提交 `c99377c`。未推送或合并。

## 行为与事务

| 操作 | 本分支行为 |
| --- | --- |
| 正常／恢复后的可信成交 | ABOVE 当前价 ≥ 目标，BELOW 当前价 ≤ 目标；不依赖前价或价格变化 |
| 新建／编辑 ACTIVE 规则 | 共用可信现价确认；不足够新鲜、订阅未确认、存在处理缺口时等待新成交 |
| 达到目标 | 股票行锁下，以版本及 `status='ACTIVE'` 条件完成；规则、触发与 Outbox 同事务 |
| 重复成交／重复投递 | 保留成交身份校验、时间水位、唯一触发、通知消费幂等 |
| 已完成规则 | 不能编辑、暂停、恢复或重新激活；再次提醒需新建 |
| 取消 | 沿用 DELETED 逻辑删除；保留历史触发与通知，取消个人股票订阅仍原子删除本人相关规则 |
| 新模拟实验 | 共用纯目标价判定；独立快照从 ACTIVE 开始，首次实际生成点达标即可完成，永不进入正式 Outbox |
| 旧模拟实验 | 原价格、快照、状态和事件保持，只读展示；不能按新语义继续推进 |

恢复确认采用实际确认时间，保留原成交时间与 RECOVERY 标记；可信正常开盘跳空仍可保留事实上下文，不伪造阈值成交或缺口中的突破时间。创建／编辑时使用实际确认时间，并保留被引用成交的原时间。创建／编辑立即完成的响应重新读取最终行，避免返回完成更新前的 `updatedAt`。

## 源码与接口影响

- [TargetPriceMatcher](../../src/main/java/com/ezra/pricepulse/alert/TargetPriceMatcher.java) 取代旧复杂状态机；[AlertEvaluationService](../../src/main/java/com/ezra/pricepulse/alert/AlertEvaluationService.java)、[AlertRuleService](../../src/main/java/com/ezra/pricepulse/alert/AlertRuleService.java) 保留真实事务编排与并发保护。
- [RuleIndex](../../src/main/java/com/ezra/pricepulse/matching/RuleIndex.java)／[ThresholdIndex](../../src/main/java/com/ezra/pricepulse/matching/ThresholdIndex.java) 只查询两个 ACTIVE 目标 band，包含边界及同价；Token 增加 `once-target-v1:` 格式前缀，旧 Redis marker 自动 MISS／权威重建，无需清空正式 Redis。
- [SimulationIsolationService](../../src/main/java/com/ezra/pricepulse/simulation/SimulationIsolationService.java) 和 [SimulationService](../../src/main/java/com/ezra/pricepulse/simulation/SimulationService.java) 保留实验锁、快照、分页、批次幂等与独立存储。新生成器为 `splitmix64-once-v2`／`opening-gap-once-v2`；旧状态按历史字符串读取，`legacyRearmed` 仅统计既存历史事件，没有重新激活执行路径。
- 规则 API 删除 `triggerMode`／`rearmPrice`；传入旧字段返回 400，旧 pause／resume 路径为 404，编辑 COMPLETED 返回 409。更新仍需版本，删除仍需版本；历史 JSON 不改写。模拟批次及匹配结果不再返回 `rearmed`。
- [RuleModal](../../frontend/src/components/RuleModal.vue)、[DashboardView](../../frontend/src/views/DashboardView.vue)、AI 草稿及模拟规则展示删除重复／冷却选项；默认最多显示三条 ACTIVE 阈值线，已完成可手动显示。旧实验提示只读，保留原快照 JSON。
- 未修改 `QuoteIngestionService`、`MarketQuoteLane`、`MarketFeedHealth`、30 秒故障协调器及 Outbox／MQ 投递机制；未采用 INSERT 优先去重，保留单股串行、1024 队列、股票锁及 READ_COMMITTED。

## 迁移与数据清单

详见 [迁移方案](migration-plan.md)及 [V12](../../src/main/resources/db/migration/V12__once_only_rule_model.sql)。V11 已用于故障告警迁移，不能复用或改变 checksum；未来批准后的顺序为 V10 → V11 → V12。

本次正式库只读盘点：37 条均为 ONCE（16 ACTIVE／21 COMPLETED），没有 REPEAT／COOLDOWN／PAUSED；历史触发、Outbox、通知各 21，旧模拟 1 个、2 条已完成 ONCE 快照。该盘点不替代正式停服截止点复核。

V12 不转换或删除旧规则：任意行（含 DELETED）不兼容就阻止发布，保留原行及字段，等待明确处置决定。兼容时仅删除恒定为 ONCE 的冗余模式／重新激活字段，规则 ID、版本、状态、价格、时间、历史 JSON、通知及模拟表保持。已有模拟记录继续只读，创建新实验仍沿用明确替换旧实验的既有产品策略。

多条 DDL 不能整体事务回滚。正式发布仍须独立审批、截止点一致性备份、隔离恢复演练、维护窗口与一致性回退方案；不能只换回旧 JAR。运行时 schema guard 也会拒绝禁用 Flyway 后直接连接旧模型。

## 回归与复现

结果汇总见 [verification.json](verification.json)：144 项后端单元、143 项集成、52 项前端测试通过，均无跳过；TypeScript／构建通过，另 15 项只读探针、8 项 Python 报告工具测试通过。143 项集成由同一最终业务源码的主矩阵 142 项及独立联合 Broker 1 项组成，未把重复复跑累计为新用例。原始日志／XML 保留在忽略的 `target/once-only-audit-20261101`，不提交随机容器凭据或大量输出。

首次集成矩阵为 5 失败／5 错误：旧夹具未更新 Token、有效时间不匹配、空时间边界、测试触发器权限，以及重复返回的空 matching；还发现并修复了立即完成响应的旧 `updatedAt`。修正后完整矩阵零失败。联合用例另修正了缺少 import、合成时钟与 DB 到期时钟及测试列名，最终通过；所有失败日志保留，未改生产 Outbox 来迁就测试。

```bash
bash scripts/run-real-pipeline-checks.sh
cd frontend
node --experimental-strip-types --test tests/*.test.ts
npm run type-check
npm run build
```

脚本冻结公开源码，排除私人 local 配置，显式关闭真实 Alpaca 接入与供应商 AI；仅创建 Testcontainers MySQL 8.4.7、Redis 7.4.6 和临时 RocketMQ 5.3.2。原生 WebSocket loopback 测试只监听本机测试端口。没有第二条 Alpaca IEX 连接。

重点证据：

| 验收内容 | 测试入口 |
| --- | --- |
| 边界、创建／编辑即时达标、同价达标、过期等待、恢复、最终态与并发、事务回滚 | ReachableOnceInfrastructureIT、TargetPriceMatcherTest、InfrastructureIT |
| 索引缺失、旧 marker、Token 回滚、权威回查 | ReachableOnceInfrastructureIT、InfrastructureIT、V2IsolationInfrastructureIT |
| 确认时间、纳秒水位、历史分页与取消后历史保留 | MarketTimelineInfrastructureIT、MonitoringInfrastructureIT |
| 跳空上下方向、模拟幂等、旧实验只读、REAL／SIM 隔离 | SimulationOpeningGapInfrastructureIT、SimulationLifecycleInfrastructureIT、V2IsolationInfrastructureIT |
| 5／29／30 秒、跨重试、ACK、一次恢复、1024 舍弃、恢复可达 | MarketFeedHealthTest、MonitorIncidentInfrastructureIT |
| 真实临时 Broker、重复价格／故障消息、唯一通知、回滚 | OpeningGapInfrastructureIT |
| 多用户共享订阅、取消后的未投递提醒、冻结模拟跳空和最终态 | V21DeliveryRegressionIT（自带独立 Broker，不读取外部 NameServer） |
| V10 历史保持、兼容升级与旧规则门禁 | OnceOnlyMigrationInfrastructureIT、MonitorIncidentMigrationInfrastructureIT |

## 历史实验与源码

清理了只服务于旧 REPEAT／COOLDOWN 语义的状态机测试与容量／性能 harness；这些测试不作为新模型通过项计数。旧性能报告、原始安全汇总和测量值保留，不能用旧语义的 P95／吞吐替代新模型测量。

完整旧源码仍在本机不可变提交 `c99377c`，例如：

```bash
git show c99377c:src/main/java/com/ezra/pricepulse/alert/RuleTransitionEngine.java
git show c99377c:src/test/java/com/ezra/pricepulse/market/RealPipelineExperiment.java
```

`PerformanceIT`、`RuleSelectionExperiment`、`EvidenceReport`、`MarketSampleCapacityIT`、`RealPipelineExperiment`、`PipelineProbe`、`PipelineCorrectness`、`InsertFirstCorrectness`、`DedupExceptionPerformance` 均属该旧实验归档。对应性能脚本在当前源码缺少旧 harness 时先退出，避免用新业务语义错误复现或误启动 Compose。重现须从该提交在独立副本执行，不能把旧字段重新引入当前模型。INSERT 优先仍是已否决方案。

## 未验事项

- 正式 V11／V12 加载、最终截止点备份与正式数据恢复演练均未执行。
- 自然市场的恢复首笔、实际断线及消息延迟、连续跨日开盘跳空仍待受控加载后只读验收；IEX 1006 仍保留，离线结果不替代供应商行为。
- 进程退出、MySQL／MQ／网络完全不可用时，不能保证第 30 秒通知送达。
- 新模型未重新测性能，不继承旧复杂规则实验的吞吐结论。

完成离线交付后停止，不自动推送、合并或部署。
