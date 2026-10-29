# V2-P5：迁移、完整回归与容量证据

日期：2026-10-29，Australia/Sydney。用户明确要求开始 P5 后执行。

**状态：P5 软件验收已完成并暂停。P2 仍为 PARTIAL，V2 整体仍有外部待验项；本机正式行情写入继续关闭。**

## 本轮范围与结果

| 验证 | 最终结果 |
| --- | --- |
| 后端单元／契约 | 72 项通过，无跳过 |
| 前端及探针离线测试 | 53 项通过（38 项前端、15 项只读行情探针），无跳过 |
| MySQL／Redis／Broker 集成 | 62 个不同用例通过，含 1 项容量记录；首轮 61 通过、1 项新夹具错误，修正后定向通过 |
| 独立性能／故障实验 | 1 项通过，含多项实际负载与恢复检查，独立于上面的 62 项 |
| TypeScript／前端构建 | 通过，包含压力验收发现的表格滚动修正 |
| 后端打包 | 通过；JAR 不包含测试 REAL 入口、集成夹具类或私人目录 |
| 图表压力验收 | 6000 个模拟点＋780 个真实前缀夹具点完整绘图；1000 条同点事件全部保留、展开、定位；1500px／390px 无页面横向溢出 |
| 开发库与运行状态 | 前后汇总完全一致，原后端／前端与四项中间件保留，临时环境已关闭 |

本轮没有修改正式业务 Java 实现、数据库迁移、运行配置或真实写入开关。新增／加强验收夹具与报告能力，修正了模拟规则大表的展示高度。

## 迁移与可靠性

- 新增干净库验收：空 MySQL 应用 V1–V6，六个版本成功，五只美股目录存在，正式事实与四张模拟表为空；再次启动迁移执行数为 0。
- V1 应用的 V3 旧库升级到 V6：保留旧价格、既有 AAPL 目录、规则、触发与通知；PENDING／SENDING／FAILED 旧 Outbox 转为 QUARANTINED，SENT 保留；重复迁移不再写入。
- 使用本轮独立 RocketMQ，而非停止现有 Broker。受控 REAL 测试入口经过生产匹配事务、触发、Outbox、实际 Broker 和消费者生成一条通知；两个独立模拟分支不新增正式触发／Outbox，重复 MQ 投递仍只有一条业务通知。
- 旧 Broker 消息实际重新投递并等待消费者确认后，LEGACY 仍不生成正式通知，也不进入正式列表／计数／全部已读；运行时旧积压的三种待发送状态均被隔离。
- 实际停止并重启本轮临时 Broker：FAILED 任务保留，恢复后 SENT／一条业务通知，观测恢复约 9.61 秒。测试会校验容器项目标签，只允许 `pricepulse-v2-p5-` 前缀的显式目标。
- 实际临时 Redis 停启、同事件安全重试；模拟批次故障回滚、缓存重建、并发推进、替换失败、不同用户隔离、固定水位分页，以及两个 OS 进程间重启恢复均通过。
- Outbox 发送异常、租约到期和“Broker 已确认但未 markPublished”窗口通过，额外投递不会增加业务通知。

首轮新增 Broker／双模拟用例将 UUID 当作内部请求哈希传入，正确地被内部校验拒绝。修正为有效长度的测试元数据后，只重验该失败用例并通过，未修改生产校验来放宽要求。首轮失败日志／XML 和定向复验均保留；最终计数按不同用例合并，不累计重复执行。

集成用例分布：InfrastructureIT 24、V2CleanMigrationInfrastructureIT 1、V2MigrationInfrastructureIT 1、V2IsolationInfrastructureIT 10、MarketTimelineInfrastructureIT 8、SimulationLifecycleInfrastructureIT 13、SimulationRestartIT 1、NotificationPipelineIT 4，共 62。PerformanceIT 另计 1 项。

## 本轮容量实测

环境：aarch64，Java 25.0.1（项目目标 Java 21），JVM 可见 10 个处理器、最大堆约 4 GiB，Docker 可用内存约 7.75 GiB；MySQL 8.4.7、Redis 7.4.6、RocketMQ 5.3.2。原本机服务和临时浏览器亦在运行。这些是闭环本机负载观测，未测最大容量、长时间稳定性、集群或断电级持久化。

| 场景 | 样本／数据规模 | P95 | P99 | 其他实测 |
| --- | --- | --- | --- | --- |
| Redis 稀疏候选查询 | 1000 规则，100 对样本 | 1.581 ms | 1.817 ms | 全量读取＋扫描 P95 10.559 ms，集合差异 0 |
| Redis 稀疏候选查询 | 10000 规则，100 对样本 | 1.611 ms | 1.951 ms | 全量读取＋扫描 P95 47.807 ms，集合差异 0 |
| HTTP 受控 REAL 入口，无穿越 | 1 股、1000 规则、100 请求 | 22.876 ms | 25.405 ms | 68.44 请求／秒，错误 0 |
| HTTP 受控 REAL 入口，无穿越 | 4 股各 1000 规则、400 请求 | 29.238 ms | 35.903 ms | 199.85 请求／秒，错误 0 |
| 模拟价格批次，0 规则 | 100 个交易小时／6000 点，100 批 | 53.400 ms | 60.597 ms | 单批 60 点；约 1935 保存点／秒 |
| 模拟同点触发 250 条 ONCE | 25 个样本，每批 60 点 | 614.305 ms | 696.509 ms | 替换事务 P95 333.763 ms、P99 519.244 ms |
| 模拟同点触发 1000 条 ONCE | 25 个样本，每批 60 点 | 1683.614 ms | 2059.432 ms | 替换事务 P95 799.814 ms、P99 953.120 ms |

补充口径：

- 候选查询不包含锁等待或触发写入；每档另保存三次冷重建样本，1000 规则约 28.34–107.17 ms，10000 规则约 70.16–89.64 ms，不能作为冷启动 SLA。
- 模拟 100 小时完整读回 10 页、6000 点；替换这个 100 批实验的一次观测为 54.727 ms。密集事件实验的每次替换都断言旧步骤／事件／快照已删除，正式事实无变化。25 个样本的 P99 等于该小样本最大值。
- 股票行锁单独使用 12 次人工占锁：故意持有约 25 ms，观察等待 P95／P99 30.891 ms；它衡量受控竞争，不是正常无竞争请求延迟。
- 250 条正式测试通知集中触发，触发事务约 656.82 ms；故意暂停消费者后恢复，约 1.078 秒消化积压，观测约 232 条／秒。触发到通知包含人为暂停和发布过程，P95 2763.059 ms、P99 2778.636 ms；额外重投 10 条，重复业务通知及缺失均为 0。
- 注入发送异常恢复约 194 ms；遗漏发布确认的租约窗口恢复约 1.082 秒；实际临时 Redis 停启到安全重试通知约 2.020 秒。注入异常、实际进程／容器停启分别记录，未混称为相同故障。

以上数值来自本轮，不沿用旧 V1 性能记录作为 V2 结论。原始延迟样本、参数和环境在 `target/v2-p5/performance/`、`simulation-capacity.json`、`broker-outage.json`。

## 图表压力验收与修正

使用独立内存 HTTP 夹具＋临时 Vite，没有开发库、供应商或消息连接，全部清楚标为合成压力验收。夹具的未知生成器仅可读，不能推进，不代表本机现有实验无法播放。

- 长图实际有 6780 个线坐标：780 个前缀＋6000 个模拟点，17 处休市断轴；Home／End 准星正确，末点 #6000 时间／价格与概要一致。概要仍为 113px，无图内大 tooltip。
- 1000 条同点事件以一个聚合标记保留；展开 1000 条详情并高亮 1000 个事件行，选择第 1000 行可准确定位 #1／原始价格 100.4000。选择一条 COMPLETED 阈值线后，1000 条事件仍保留。
- 发现 1000 条模拟规则让文档高达 58727px，导致事件区域难以到达。仅为模拟规则表增加 320px 内部滚动与固定表头，展开的来源详情限高滚动；文档降到 1474px，1000 条规则未删除、未截断。
- 390px 文档宽度仍为 390px，规则表／事件表分别在 320px／280px 区域内滚动。浏览器控制台无错误。

本轮验证了长图与重叠事件功能，没有采集浏览器 FPS、GC 或渲染专用性能轨迹，不将自动化往返时间当作绘图耗时。

## 本机保护及外部遗留

验收前后只读 SQL 汇总完全一致：V6、REAL 报价 0、LEGACY 报价 961、正式水位 0、触发／Outbox／通知各 5、规则版本合计 9；现有实验 1 个、步骤 6、事件 2，实验状态摘要哈希一致。没有替换原用户实验。

原 18081 后端、5173 Vite 与原 MySQL／Redis／NameServer／Broker 仍运行，原四项中间件健康。临时 Testcontainers、UI 夹具和本轮独立 Broker／网络已清理。没有 reset、clean、提交、推送、远端覆盖或开发库升级；私人配置未修改、未输出。

只读实际 API 与 5173 代理验证返回历史 390 点、连接 SUBSCRIBED、businessWritesEnabled=false，但 lastError 保留 `HEARTBEAT_TIMEOUT`，报告为 PASSED_WITH_WARNINGS。没有为消除此警告而重启现有行情连接或新增并发供应商探针。

另对五股的 1H／1D／1W／1M 共 20 个窗口和 1M 前端代理只读核验：所有已缓存分钟点均返回，未应用采样。AAPL 分别有 61／390／1951／8580 点；1M 预期 8581 个分钟点，仍有 1 个不可用，API 明确报告缺口，未补点或用断轴掩盖。证据 `runtime/windows/live-after.json`。这不构成全市场数据完整性结论。

**P2 遗留继续保留：**供应商正常关闭握手（既有 1006）、心跳警告的进一步核验、实际供应商行情到正式提醒的端到端和用途许可。受控 REAL 入口经过实际 Broker 的测试不能替代供应商实盘端到端。长期／最大容量、集群容错及断电耐久性也未由本轮样本证明。

## 复现与证据

在仓库根目录执行。所有故障目标必须为本轮独立环境，原 `compose.yaml` 不参与停启故障。

```sh
./mvnw -B -ntp test
node --experimental-strip-types --test frontend/tests/*.test.ts scripts/tests/*.test.mjs
npm run build --prefix frontend
docker compose -p pricepulse-v2-p5-acceptance -f deploy/p5/compose.yaml up -d --wait

ROCKETMQ_NAMESERVER=127.0.0.1:39876 \
PRICEPULSE_P5_BROKER_CONTAINER=pricepulse-v2-p5-acceptance-broker-1 \
PRICEPULSE_P5_BROKER_PORT=38111 \
./mvnw -B -ntp -Dpricepulse.p5.capacity=true -Dpricepulse.p5.report-dir=target/v2-p5-rerun \
  -Dit.test=InfrastructureIT,V2CleanMigrationInfrastructureIT,V2MigrationInfrastructureIT,V2IsolationInfrastructureIT,MarketTimelineInfrastructureIT,SimulationLifecycleInfrastructureIT,SimulationRestartIT,NotificationPipelineIT \
  test-compile failsafe:integration-test failsafe:verify

ROCKETMQ_NAMESERVER=127.0.0.1:39876 ./mvnw -B -ntp -Dit.test=PerformanceIT \
  -Dperf.scales=1000,10000 -Dperf.samples=100 -Dperf.burst=250 \
  -Dperf.report-dir=target/v2-p5-rerun/performance test-compile failsafe:integration-test failsafe:verify

# 成功或失败后均仅清理这个测试项目。
docker compose -p pricepulse-v2-p5-acceptance -f deploy/p5/compose.yaml down -v
./mvnw -B -ntp -DskipTests package
```

容量记录用例和实际 Broker 停启分别显式启用，普通测试不自动执行。AI／供应商在线验证是独立付费／外部依赖项目，本轮未调用，不计为通过或被动跳过。

UI 复现：`node scripts/v2-ui-stress-fixture.mjs --report-dir=target/v2-ui-stress-new`，使用输出的本机 URL。合成长图账户 `long@test.local`、聚合账户 `dense@test.local`，密码使用任意合成文本；不使用真实账户或密码。此内存夹具无业务写 API。结束时创建报告目录的 `stop.signal`；20 分钟自动退出。存在旧停止信号时拒绝覆盖，改用新报告目录。此工具未打包到应用。

本机只读 API：`node scripts/v2-p2-local-readonly-check.mjs --p3 --report-dir=target/v2-p5-readonly-new`；临时不落库身份只在内存中签名，凭据不输出，报告保存在新目录。

本轮证据均在忽略目录 `target/v2-p5/`：`unit-tests.log`、`frontend-and-probe-tests.log`、`integration-tests.log`、`integration-first-reports/`、`broker-recheck.log`／`broker-recheck-reports/`、`performance-tests.log`、`performance/`、`simulation-capacity.json`、`broker-outage.json`、`development-before.json`／`development-after.json`、`runtime/`、`runtime-and-artifact.json`、`ui-checks.json`、`verification.json` 和 `source-manifest.json`。截图 `ui-long.jpg`／`ui-dense.jpg`／`ui-mobile.jpg` 均为只读合成压力夹具，不是实盘行情。

P5 到此暂停；不因软件验收通过而自动开启正式行情写入或变更阶段。
