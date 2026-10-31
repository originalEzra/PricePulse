# REAL 行情整链路测量

先测基线，再决定优化；本目录不改变“穿越”语义，也不以历史候选查询成绩代替完整事务性能。

## 隔离与复现

`bash scripts/run-real-pipeline-experiment.sh --suite=smoke --output=target/pipeline-smoke-new`

脚本将源码复制到忽略目录单独编译，排除私人配置，不覆盖正式进程正在使用的 `target/classes`。Java 驱动只存在于测试 classpath，不提供行情注入 HTTP 端点；它创建随机口令、仅回环映射的独立 MySQL／Redis，直接装配正式 MyBatis Mapper、事务代理、`MarketQuoteLane`、`QuoteIngestionService`、`RuleIndex` 和 OutboxWriter。没有 Spring 应用启动、Alpaca 连接或正式 Broker 引用。只清理自己新建的测试库，退出自动销毁容器。

队列保持 1024、单股单线程；Hikari 上限 10，MySQL 1 CPU／768 MiB，Redis 0.5 CPU／256 MiB，测试 JVM 2 个可见处理器／768 MiB。MySQL 保持持久提交。宿主上正式服务继续运行，实验不能冒充独占硬件压测。

## 测试层次

1. `smoke`：验证真实组件、统计口径及 1／5／15 股入口，不用于持续吞吐结论。
2. `baseline`：零规则、千条无命中、少量重复触发、100／500／1000 条集中一次性触发；每股 50／100／200／500／1000 笔/s，另测多股竞争。每档预热并重复；集中触发每轮只有一次，命中条件分位数必须显示样本数，不能用整轮 P95 掩盖尖峰。
3. `sustain`：在基线发现的临界区域延长观测，吞吐结论需要结合积压趋势、实际输入速率和排空时间。
4. `correctness` 及现有隔离集成测试：重复／乱序／过期、溢出、故障、回滚、规则并发、状态和通知隔离，另列证据。
5. 回环 WebSocket 只验证协议／恢复；自然 Alpaca 开市是独立待验，不因离线通过标记 PASS。

## 时间与统计边界

- 合成事件为固定种子价格路径，使用交易日内的独立测试时钟；接收、计划入队、实际入队、工作线程启动和完成均另外使用单调时钟。队列积压仍会按真实经过时长触发原有过期保护。
- `transaction_total_ms` 的外层拦截位于 Spring 事务代理之外，覆盖连接获取、执行和真正提交；另记录 `transaction_body_ms` 和 JDBC `commit` 耗时，不能混用。
- `stock_lock_wait_ms` 是实际 `SELECT ... FOR UPDATE` 调用总耗时，含锁等待、SQL 往返和对象转换，是纯锁等待的上界。数据库锁等待另外采集，不将总耗时伪称为纯数据库等待。
- Mapper 阶段含结果转换；`quote_persist_ms` 累加身份去重、事件插入和状态完成调用。`rule_transition_ms` 为乐观状态 UPDATE，纯决策另记 `rule_decision_ms`。`trigger_outbox_write_ms` 包含快照序列化、触发和 Outbox 调用。
- `redis_candidate_ms` 包含生产候选调用，冷缺失时会包含重建／数据库加载；维护耗时单列。阶段存在嵌套，不可简单相加。
- JDBC 代理统计实际 `execute*` 次数，并保存数据库 Performance Schema 计数供核对；逐笔数据只存内存，排空后写入忽略目录，不逐笔同步日志。
- P50／P95／P99 为 nearest-rank，样本数与 Max 同列。已丢弃输入不伪造零延迟；成功处理分位数必须同时展示丢弃和失败数。

报告与优化决策在完成基线后补充；当前不能宣称单股持续能力、改造收益或自然市场验收通过。
