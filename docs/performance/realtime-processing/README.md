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

## 报告与复现命令

- 后续独立单变量实验：[成交 INSERT 优先对照（未保留，已恢复原去重方案）](dedup-insert-first-2026-10-31/report.md)，不覆盖下列原基线。
- [优化前基线](baseline.md)、[完整前后对照与局限](report.md)、[最小优化决策](decision.md)。
- [固定参数](plans.json)、[默认关闭的运行时观测](runtime-metrics.md)、[自然市场待验清单](market-acceptance.md)。
- `before/`、`after/` 是 84 档短窗口汇总；`before-sustain/`、`after-sustain/` 是每档 60s 的重复观测。逐笔原始文件留在忽略的 `target/`，汇总保存文件摘要；复现须重新运行，不能从汇总反推出逐笔样本。

以下命令从仓库根目录运行，需要 Docker 和可用的本机 Maven 依赖。每次输出目录须为新目录；脚本只销毁自己创建的容器。各轮顺序执行，不同时压测。

```bash
# 相同参数分别运行改造前 evaluator 与当前实现；其余观测／注入工具相同。
PP_PIPELINE_EVALUATION_REF=7171525 bash scripts/run-real-pipeline-experiment.sh \
  --suite=baseline --seconds=8 --warmup-seconds=2 --repeats=2 \
  --output=target/pipeline-baseline-repro
bash scripts/run-real-pipeline-experiment.sh \
  --suite=baseline --seconds=8 --warmup-seconds=2 --repeats=2 \
  --output=target/pipeline-after-repro

# 临界速率：每档预热 2s、正式 60s、重复两次，保留 1024 队列。
bash scripts/run-real-pipeline-experiment.sh --suite=sustain \
  --seconds=60 --warmup-seconds=2 --repeats=2 --stocks=1 --rates=100,150,200 \
  --scenarios=ZERO_HIT,FEW_HIT --instrumentation=boundary \
  --output=target/pipeline-sustain-repro

# 逐笔候选 ID、初末规则状态、触发及 Outbox 严格对照，旧版／新版分别运行。
bash scripts/run-real-pipeline-experiment.sh --suite=sustain \
  --seconds=4 --warmup-seconds=2 --repeats=2 --stocks=1 --rates=100,200 \
  --scenarios=ZERO_RULES,ZERO_HIT,FEW_HIT,BURST_100,BURST_500,BURST_1000 \
  --capture-candidates=true --instrumentation=full \
  --output=target/pipeline-control-repro

bash scripts/run-real-pipeline-experiment.sh --suite=correctness \
  --output=target/pipeline-correctness-repro
bash scripts/run-real-pipeline-checks.sh
python3 -m unittest discover -s scripts/tests -p 'test_real_pipeline_report.py'
python3 scripts/performance/report-real-pipeline.py \
  --input target/pipeline-after-repro --output target/pipeline-report-repro
```

`PP_PIPELINE_EVALUATION_REF` 只在隔离编译镜像中替换 `AlertEvaluationService.java`，不 checkout／覆盖当前工作区。旧 evaluator 调用保留的单行 Outbox 方法；新版调用有界批量方法。两者的规则判定、输入、计时器、JDBC 代理及其余服务相同，源码摘要记录于每轮环境信息。

`full` 测每个 Mapper／JDBC 阶段；`boundary` 关闭精细 SQL 代理，仅保留队列、事务外层及完整处理边界，用于减少观测扰动。这两类结果分别比较，不能混作同一性能样本。实际 WebSocket 协议验证由隔离检查中的回环测试执行，负载工具入口仍是队列；不是供应商网络端到端压测。

可通过 `--stocks=1,5,15`、`--rates=50,100,200`、`--seconds=60`、`--rules=1000`、`--seed=2026103101` 改变规模；`--hit-rules=2` 控制 FEW_HIT 中的近价规则数量，`--path=mixed|flat|oscillate` 控制价格路径。mixed 固定 80% 小幅随机点、10% 低点、10% 高点；实际非空候选率另报 `nonemptyCandidateEvents / processedEvents`，不把配置比例当实测命中率。集中场景明确为一次 100／500／1000 条 ONCE 穿越。入口限制股票数、速率、时长、规则数与输出路径，不接受外部数据库／Redis 地址，防止误指向正式资源。
