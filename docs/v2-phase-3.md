# V2-P3：可恢复的独立模拟闭环

日期：2026-10-29，Australia/Sydney。用户明确要求继续下一阶段后实施。P2 仍为 PARTIAL：1006 正常关闭确认、正式提醒端到端及用途许可不因进入 P3 而自动通过。

**状态：P3 已实现并通过自动化、隔离浏览器及本机只读部署核验。正式行情写入保持关闭；本阶段结束，P4/P5 未开始，等待用户确认。**

后续用户已明确确认并完成 P4，当前自动播放、暂停恢复、完整请求竞态和长实验分页见 [P4 记录](v2-phase-4.md)。以下保留 P3 当时实现与验收范围，不代表当前仍没有自动播放。

后续用户在 P3 后明确要求统一优化图表时间轴，见 [调整记录](v2-time-axis.md)。本记录的原截图和测试是当时的墙钟轴验收；当前 1W／1M 及模拟图已改用共享交易时间轴，压缩已知休市并标记断轴，交易时段内仍保持真实比例。该调整不改变实验冻结输入或 P3 生命周期。

## 本阶段交付

- 用 P2 已缓存的 IEX/raw 历史分钟点或已接受 REAL 成交开始实验，禁止客户端自填起点价格。
- 冻结前一个正常交易日开盘到起点的真实历史前缀、日历日程、模式／幅度／seed／生成器版本，以及本人该股全部非删除规则配置。
- 每用户唯一最近实验；明确开始才替换，失败回滚保留旧结果。浏览别的股票不删除已保存实验。
- 手动推进 60 个正常交易分钟，保存价格、全局点号、规则快照状态、触发／重新激活事件、版本和批次。没有自动播放或服务端推进定时任务。
- 提供 current / start / advance / timeline / events；页面刷新恢复保存结果，默认手动暂停。

复用 P1 的四张模拟表、事务和 Redis 独立 Key，没有新增数据库迁移或重做真实处理内核。模拟服务不依赖 Outbox／通知服务，没有真实 MQ 投递。

## 起点与冻结

`HISTORICAL_POINT` 接收 P2 时间线的 pointRef 和 revision。服务端按股票、ALPACA／iex、raw／1Min、已闭合正常交易时段或 REAL／PROCESSED 成交验证，并从 DB 解析价格。另一股票、LEGACY、未接受成交、未闭合／未来点会拒绝；修订不匹配返回 409，保留原实验。

`LATEST` 只读取 ALPACA／iex 正式水位对应的已接受成交，不回退为某根历史分钟价。新鲜度相对当前／最近正常交易时段检查，休市不会简单按整个周末的墙钟年龄判过期。当前本机正式逐笔写入关闭，没有正式实时水位，LATEST 明确不可用；历史分钟起点仍可用。

开始由 `SimulationIsolationService.begin` 的准备回调，在同一 REPEATABLE_READ 快照内读取起点、缓存前缀、日历和本人规则；锁用户与旧实验，不锁股票实时行，不在事务内访问供应商。缺少前一交易日、前缀下载覆盖或有效起点时返回错误；不删除旧实验后再偷偷换起点。

D1 保持原决策：未暂停规则（包括原 COOLDOWN／COMPLETED）从 ACTIVE 初始化，PAUSED 不参与，DELETED 不复制。以后修改／转移／删除真实规则、历史缓存修订或日历更新，都不改变已冻结输入。

## 交易分钟与确定性

- 使用冻结的 UTC 开闭市日程，逐点累计一分钟交易时长；跳过休市、周末、节假日及提前收市，不猜测缺失日历。
- 原点带秒或纳秒时保留精度；跨收盘不足一分钟的余量带入下一次开市。正好累计到收盘的点可标在收盘端点，下一个点从下一交易时段继续计时。
- 日历不足时整个小时失败，时钟／批次／规则／事件均不移动。不能返回不足 60 点的“已完成小时”。
- 生成器 `splitmix64-price-v1` 由 seed 和全局 pointSeq 产生每点随机量，以上一已保存四位小数价格继续；每次舍入本身也是版本化算法的一部分。
- NORMAL 为对称随机变化；RISE／FALL 使用固定方向的半幅到全幅随机变化；OSCILLATE 每五个全局点切换方向。最大幅度为每分钟 0.3%，最低为一个价格 tick；超过价格内核上限时整批拒绝。
- 未知生成器版本停止新推进，已有输入与结果仍可查看，提示明确开始新实验；没有静默切换算法。

## API 与并发

沿用 JWT 身份及 ApiResponse。开始请求必须显式提供 expectedCurrentRunId（无旧实验为 null）；开始与推进必须带 UUID Idempotency-Key。请求摘要规范化幅度，服务端生成的 seed 仅在实际首次创建时产生。seed 响应和页面输入使用十进制字符串，支持完整有符号 64 位范围，不经 JavaScript Number 舍入；API 也接受范围内的整数 JSON。

已提交的同 Key 重试先返回原 run／原批次，早于预期版本检查；同 Key 不同请求 409。旧批次重试返回原 versionAfter，不能伪装成当前新版本。实验替换后旧 runId 404，其他用户也不可读写。

价格按最多十个小时批次分页，每页最多 600 个模拟点；真实前缀单独返回。事件独立每页 100 条，额外读一条判下一页。HMAC 游标绑定本人、runId、查询类型、范围与固定水位，不能跨人／跨实验／跨价格事件页复用。价格分页期间后续推进不会混入旧水位的结果。

所有历史 GET 只读，统计来自已存事件。事件关联冻结的规则配置，不临时拿真实规则当前值推导旧结果。

## 最小页面

用现有三导航和原生 SVG：历史点选择、四种模式、幅度与可选 seed、明确替换、下一交易小时、恢复保存结果、价格／事件翻页和规则快照概览。

一张图显示蓝色真实冻结前缀、棕色虚线模拟后缀和起点分界；起点只出现一次，跨交易日保留空白。正式事件没有混入模拟，标记明确写“模拟触发”。

网络失败保留原请求键和请求体；重试不会更换随机数或小时。用户也可先查询已保存状态再明确结束重试。基本请求版本保护防止迟到读取覆盖新实验／新版本。完整播放循环、暂停继续与更广的交互竞态属于 P4，未提前实施。

## 当前自动化证据

| 检查 | 结果 |
| --- | --- |
| 相关单元／契约 | 26 项通过：日历 4、生成器 3、模拟游标 1、原纯状态机 13、API 契约 4、standalone 启动 1 |
| 新 P3 MySQL／Redis 生命周期 | 12 项通过，包括不支持的生成器只读保留、缺少前缀覆盖时不替换旧实验，以及完整 64 位 seed 请求 |
| 打包后真实进程重启 | 1 项通过：前一进程退出后再启动，清空临时 Redis，恢复同一冻结输入／快照／时钟，重试原批次且继续确定性下一批 |
| P1 隔离回归 | 10 项通过 |
| P2 时间线回归 | 7 项通过 |
| 独立浏览器夹具 | 1 项通过；浏览器交互已完成，退出前断言五张正式表均无新增 |
| 前端请求与图表几何 | 6 项通过，前端构建通过 |

业务集成／重启共 30 项，另有浏览器夹具 1 项，全部使用可丢弃的数据库／Redis。测试包含两用户同股、105 条规则／事件分页、相同请求并发、不同请求版本竞争、非法来源／修订／未来点、D1、规则和缓存修改后的冻结、Redis 故障、整批／替换回滚、旧 runId 拒绝及正式表无新增。重启样本明确为合成数据，不能冒充正式实盘提醒证据。

发现并修复了全局错误处理把缺少必填请求头映射成 500 的问题；这类参数绑定错误现按统一 400 返回，不暴露内部绑定细节。新增用例覆盖该行为。

验证命令：

```sh
./mvnw -B -ntp -Pintegration \
  -Dtest=SimulationCalendarTest,SimulationGeneratorTest,SimulationCursorTest,ApiContractTest,StandaloneApplicationTest,RuleTransitionEngineTest \
  -Dit.test=SimulationLifecycleInfrastructureIT,SimulationRestartIT,V2IsolationInfrastructureIT,MarketTimelineInfrastructureIT verify
# 最后补充的两项生命周期边界与完整 64 位 seed 再单独复验：
./mvnw -B -ntp -Dit.test=SimulationLifecycleInfrastructureIT,SimulationRestartIT \
  test-compile failsafe:integration-test failsafe:verify
node --test frontend/tests/simulationRequests.test.ts frontend/tests/timeAxis.test.ts
npm run build --prefix frontend
```

首次组合验收为 26 单元／契约＋28 业务集成，补充用例后定向复验 P3 的 13 项业务集成通过；上表按最终不同用例计数，不把重复执行累计为更多覆盖。原先未受影响的完整单元／业务集成和 Broker／性能没有无故重跑。

## 浏览器验收

浏览器通过临时数据库／Redis、临时打包后端和临时 Vite 访问“合成验收（非实盘）”账户；供应商和 MQ 均关闭，没有连接开发数据库。夹具明确标注合成股票名称，不把测试价格冒充实盘。

1. 选择合成 AAPL 的闭合历史点、RISE、seed=42；LATEST 在没有已接受成交时禁用。
2. 创建实验并推进一次，得到 60 点，从纽约 2026-10-28 收盘跨夜到 2026-10-29 10:30，价格 114.1672；保存 2 次触发。
3. 浏览 MSFT 后原 AAPL 实验保留；刷新恢复同一实验、批次和价格。
4. 图上保留两段蓝色真实前缀、一段棕色模拟虚线和唯一的起点标签；展开聚合标记显示两个冻结规则的实际模拟触发详情。
5. 通知中心为 0 条；结束夹具前正式报价／触发／Outbox／通知／水位计数均为 0，随后关闭临时进程及容器。

截图：`target/v2-p3/ui-synthetic-simulation.jpg`；交互记录：`browser-checks.json`；隔离断言：`browser-isolation.json`。首次夹具就绪探测没有及时完成，已保留 `browser-first-attempt.txt`；为 HTTP 探测增加连接／请求超时后复跑通过，没有将首次失败抹去。

复现浏览器夹具需先保留／移开旧的 `target/v2-p3/browser-stop.signal`，再显式设置 `PRICEPULSE_RUN_SIMULATION_BROWSER=true`，执行 `-Dit.test=SimulationBrowserHarnessIT test-compile failsafe:integration-test failsafe:verify`；完成交互后创建该 signal，夹具才进行正式表断言并清理。普通测试默认不启动该交互夹具。

## 本机运行核验

悉尼 2026-10-29 13:30，通过原 IDEA `PricePulse Local` 配置平稳重启并加载 P3；18081 后端运行，原 5173 前端保留。没有新增迁移、演示账户或开发库实验；沿用已备份的 V6，不重复执行升级或恢复。

- `GET /api/simulations/current` 在后端及 5173 代理均返回当前无实验的正常结果；核验使用 3 分钟、未落库的合成身份，仅 GET，不访问实际账户。
- 真实 AAPL 1D 返回 390 个缓存分钟点，IEX 连接 SUBSCRIBED、businessWritesEnabled=false；本次 lastError 为空。闭市后重启没有用“收到新成交”作为通过条件。
- 前后数据库均为 V6：LEGACY 报价 961、REAL 报价 0、正式水位 0，触发 5、Outbox 5（SENT）、通知 5、模拟实验 0。核验时历史缓存 54,456 根，日历 60 个时段；缓存会继续只读同步。
- Alpaca 私人 env、IDEA Run 和前端代理文件三份指纹均未改变。没有重开旧共享写入口，也没有修改正式写入开关。

只读复验：`node scripts/v2-p2-local-readonly-check.mjs --p3`；报告 `target/v2-p3/local-readonly-api.json`。其 P2 默认完整检查方式保留，本轮没有重复整套开市采样。

主要实现入口：`simulation/SimulationOriginResolver`、`SimulationCalendar`、`SimulationGenerator`、`SimulationService`、`SimulationController`、`SimulationCursor`；前端 `SimulationView.vue`、`PriceSparkline.vue`、`utils/simulationRequests.ts`。已有 `SimulationIsolationService` 的隔离事务仍由 P1 提供。

汇总与源码指纹：`target/v2-p3/verification.json`、`source-manifest.json`；重启报告：`process-restart.json`。这些忽略目录证据若被清理，应以代码和阶段记录为准。

## 阶段边界与下一步

P3 完成并暂停。P4 获得确认后才实施自动播放、暂停／继续和完整页面／双标签页请求竞态。P5 才集中做迁移、Broker、故障与持续容量等整体验收。

P2 保持 PARTIAL：1006 正常关闭确认、真实供应商到正式 MQ／通知端到端及用途许可未完成。当前可从真实历史分钟点做独立模拟；“最新成交”仍依赖已接受的正式 REAL 水位，不能用历史收盘价冒充。P3 通过没有授权启用正式业务写入。
