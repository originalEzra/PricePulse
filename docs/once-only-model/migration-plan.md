# 一次性目标价模型：实施与兼容方案

本方案先于代码修改建立。仅在独立本机副本 `/Users/ezra/project-z/price-pulse-once-only` 的 `codex/pricepulse-once-only-20261101` 开发。原仓库和运行中的 V10 后端 / Vite 不切换，不迁移正式库，不连接第二条 IEX，不推送或合并。

## 正式数据只读盘点（2026-11-01）

查询使用 `SET SESSION TRANSACTION READ ONLY` 与 `START TRANSACTION READ ONLY`，不输出规则名、用户、阈值或凭据。

| 对象 | 结果 |
| --- | --- |
| Flyway | V10，成功 |
| 当前规则 | 37：16 ACTIVE、21 COMPLETED，全部 ONCE |
| REPEAT / COOLDOWN / PAUSED | 当前均无 |
| 历史触发 / Outbox / 通知 | 各 21；历史快照均 ONCE |
| 已保存模拟 | 1 个 splitmix64-price-v1 实验，2 个已完成 ONCE 快照 |

这是本次时点的清单，不能替代未来正式停服后的最终检查。

## 最小模型

- 正式规则仅 ACTIVE、COMPLETED、DELETED；取消沿用逻辑删除，保留外键与全部触发/通知。
- 删除触发方式与重新激活价字段、暂停/恢复接口；只允许编辑 ACTIVE，COMPLETED 不能编辑或重新激活。
- REAL / SIM 共用纯目标价判定与两段 ACTIVE ZSet；SQL 以 ACTIVE 和版本为条件完成。规则、触发与 Outbox 仍在同一股票锁事务内。
- 创建/编辑复用可信现价门槛；不足时 ACTIVE 等待新成交，不使用历史缓存冒充现价。
- 原告警 V11 不改 checksum；本模型新增 V12。未来发布顺序为 V10→V11→V12，本轮只在隔离库执行。

## 不静默转换旧规则

V12 先验证 **所有** alert_rules 行均为 ONCE、rearm_price 为空、状态为 ACTIVE/COMPLETED/DELETED（包含已逻辑删除的行）。不满足时迁移失败，不删除、转换或归一化旧规则，也不尝试在新运行时继续重复/冷却逻辑。

校验作为 MySQL CHECK 的原子 ALTER 执行，阻止检查与 DDL 之间并发混入旧规则。通过后才移除恒为 ONCE 的冗余列及旧约束，保留规则 id、状态、版本、所有时间与所有历史表。既有触发 JSON 中的旧字段不重写。单条 DDL 的原子性与 CHECK 验证依据 [MySQL 原子 DDL](https://dev.mysql.com/doc/refman/8.4/en/atomic-ddl.html)及 [CHECK 约束](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html)；隔离测试另外核验了失败时旧字段／规则保持。

未来正式发布前必须重新只读检查并停旧服务、完成截止点备份。若出现 REPEAT/COOLDOWN/PAUSED 或非空重新激活价，**停止发布**，先向用户提供逐条影响清单；由用户选择保留旧版本继续运行，或批准留存完整归档后终止并新建一次性规则。该处置不在本轮自动实施。不得仅通过过滤 SQL 让旧生效规则悄悄失去监控。

## 旧模拟与历史

旧 generatorVersion 的价格、规则快照、步骤、事件和汇总不改写，不套用新语义继续推进；只读展示并明确提示需开始新实验。新实验使用新的版本标识，冻结新的规则配置，实验内 ACTIVE→COMPLETED 仅一次。历史状态/事件按字符串读取，允许展示旧 COOLDOWN/REARMED 事实，相关执行分支彻底移除。模拟历史表原 CHECK 范围保留以容纳这些事实，不代表新实验可写入旧状态。

真实历史前缀/参考价只作上下文；新模拟价格点采用当前价达到即触发，不依赖前点穿越，不向正式 Outbox/MQ 发送。新实验的独立快照从 ACTIVE 开始属于新实验，不重新激活任何正式或旧实验的 COMPLETED 行。

## 验证与回滚边界

新增迁移测试分别验证兼容 V10→V11→V12 指纹保持，以及旧 REPEAT/COOLDOWN/PAUSED 被阻止且原事实不变。历史 JSON、模拟记录、触发和通知必须可读。新版本不应在缺少 V12 的库上接入行情。

整体回归覆盖目标边界、并发成交/编辑、重复消息、索引缺失与 Token 回滚、创建/编辑时达标、恢复基线、30 秒与队列告警、正式测试 Broker、事务回滚和 REAL/SIM 隔离。

MySQL 多条 DDL 不是整体回滚事务。正式加载须维护窗口及备份恢复演练；V12 删除冗余列后不能只换回旧 JAR。需一致性恢复旧数据库和旧代码，或单独评审兼容回退方案，保全窗口内新增事实。不在本轮执行任何正式备份、DDL、重启或回退。
