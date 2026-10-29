# V2-P4：自动播放、暂停恢复与请求竞态

日期：2026-10-29，Australia/Sydney。用户在两页 UI 布局验收后明确要求进入下一阶段，按已确认方案实施 P4。

**状态：P4 已完成并验收，至此暂停。P5 尚未开始；P2 仍为 PARTIAL，正式真实行情写入保持关闭。**

后续用户已明确要求并完成 P5 软件验收，见 [P5 记录](v2-phase-5.md)。以下保留 P4 当时的交付和样本口径，不将旧样本累计为本轮新验证。

## 本阶段交付

- 自动播放使用页面内单个串行循环：每次请求一个交易小时，等待写响应及完整保存结果读取成功，才安排下一次。慢速／标准／快速分别等待 5／2／1 秒；速度只影响现实等待，不改变 seed、模式、幅度、模拟时钟或生成价格。
- 暂停立即取消下一次调度。已经受理的一小时允许完成；离开页面、进入后台、pagehide 或开始退出登录同样停止新批次。返回页面、刷新和重新登录均恢复数据库保存的事实，并默认暂停。没有服务端后台自动推进器或关闭页面后的持续播放。
- Pinia 保存当前会话唯一实验、待重试命令、新实验草稿与阈值线选择。浏览其他股票只更新新实验起点，已保存实验继续明确标注原股票；提供返回原股票起点的入口。模式、幅度、Seed 等输入明确仅供新实验使用，不修改已开始实验。
- 版本冲突、旧 runId、读取版本落后或价格分页异常都会停止播放，提示恢复保存结果。不同用户／凭据会话切换清除旧状态并隔离迟到读写响应；即使重新登录同一用户，页面实例也会更新。
- 原规则快照表、事件双向定位、聚合详情、固定概要和共享时间轴继续使用，不重做已完成的 UI，也不发送模拟通知。

## 请求与恢复语义

`frontend/src/utils/simulationRequests.ts` 中的 `SimulationSession` 是可独立测试的会话控制器，复用原 `RetriableSimulationRequest`。`frontend/src/stores/simulation.ts` 提供 Pinia 与既有 API 的适配；`SimulationView.vue` 负责表单和展示，`App.vue` 在退出／凭据变化时停止旧会话。

同一页面只执行一项实验操作。重复点击、手动推进与播放不能并发派发。等待定时器带调度版本，暂停或改变等待速度后，已经进入队列的旧回调也不能额外请求一小时。

网络错误或 5xx 保留原幂等键和冻结请求体，不自动重试；由用户明确重试原操作。仅查询未变化的保存结果不能证明原命令失败，因此不再盲目“结束重试”。查询看到原实验版本／批次确实前进，或旧实验已被替换，可结清待确认推进；未知开始请求仍须沿用原 Key 重试，防止复活或替换错误实验。

成功写响应建立最低可接受版本。随后的旧 GET 不会将前端倒退，也不会继续播放。run、timeline、快照及事件完整读取后一起发布；current／timeline 发生跨标签页版本竞争时，不拼成混合视图。404／409 停止播放并要求恢复，由既有后端归属、版本、批次和幂等约束裁决，不修改这些约束。

读请求支持 AbortSignal，离页取消读取和后续分页；写请求不使用中止信号，因为客户端中止不能撤销已受理事务。账号切换使用会话代次隔离，旧请求的响应、错误和 finally 都不能覆盖新会话或清除其 busy 状态。待重试命令只保存在 Pinia 内存中，实验事实始终来自后端。

## 长实验的价格分页

浏览器验收中超过十小时后发现：旧版恢复只读首批 600 点，最新价格与图中尾部脱节。本轮已修正：按既有固定水位游标读齐保存点，拒绝重复游标、水位改变或点数不完整；没有取消后端分页上限或修改接口。

继续推进时保留已加载的不可变历史，只合并真实提交批次的返回点，避免每小时重复下载全部旧页。原始时间与价格保持不变，未生成补点。`simulationPointBreaks` 仍为缺少连续点号提供断线保护，复用现有图表的 breakTimes，不改交易时间映射或休市断轴规则。

事件继续按原只读分页完整读取，统计来自已保存事件。没有从最终 COOLDOWN／COMPLETED 状态倒推触发次数，也没有把实验结果写入正式提醒链路。

## 验证结果

| 范围 | 结果 |
| --- | --- |
| 前端测试 | 38 项通过，新增 14 项播放／竞态用例；原时间轴、精确时间、规则展示和请求重试回归通过 |
| TypeScript／生产构建 | `npm run build`（含 `vue-tsc -b`）通过 |
| 后端定向集成 | 3 项通过：并发重复／竞争推进、确定性幂等与正式事实隔离、失败替换及旧 runId 不复活 |
| 独立浏览器夹具 | 1 项通过；真实打包后端＋临时 MySQL／Redis，供应商／MQ 关闭，未连接开发库 |
| 暂停与继续 | 首个已受理小时完成后暂停，超过原等待间隔仍保持 1 小时；继续及定时循环推进到 4 小时 |
| 浏览与离页 | MSFT 起点浏览与 AAPL 实验分离；离页时已受理批次完成到 5 小时，返回保持暂停且保留浏览选择 |
| 双标签页 | A 页推进至 6 小时；B 页旧版本自动播放收到冲突，禁用继续并提示恢复；明确恢复后显示 6 小时、保持暂停 |
| 退出与刷新 | 退出登录停止播放；重新登录恢复保存结果并暂停。长实验刷新完整显示 840 点／14 小时，概要与最新价一致 |
| 原 UI 回归 | 事件行定位原始 #1、时间及价格正确；固定概要仍为 113px；1500px 桌面和 390px 手机无页面横向溢出，三个播放控件均在视口内 |
| 正式业务事实 | 浏览器夹具退出时 quote_events、alert_triggers、outbox_events、notifications、market_stream_state 均为 0；通知中心为空 |

前端新增覆盖：串行及暂停在途、等待速度和旧回调、离页恢复、丢响应同 Key、未知结果查询、双标签页冲突、实验外部替换、账号切换迟到读写、成功写后旧 GET、current／timeline 竞争、开始重试及失败替换、长实验分页保留与增量合并、坏游标和固定水位、不同速度的相同保存路径。Node 测试使用可控 Promise 与定时器，不用实际等待冒充确定性断言。

定向命令：

```sh
node --experimental-strip-types --test frontend/tests/*.test.ts
npm run build --prefix frontend
./mvnw -B -ntp '-Dit.test=SimulationLifecycleInfrastructureIT#concurrentDuplicateAndCompetingPublicAdvancesOnlyCommitOnce+batchesAreDeterministicIdempotentOwnedAndNeverMutateFormalFacts+failedReplacementPreservesOldResultsAndReplacedIdsCannotBeRevived' test-compile failsafe:integration-test failsafe:verify
PRICEPULSE_RUN_SIMULATION_BROWSER=true ./mvnw -B -ntp \
  -Dit.test=SimulationBrowserHarnessIT -Dpricepulse.browser-report-dir=target/v2-p4/browser \
  test-compile failsafe:integration-test failsafe:verify
```

交互夹具要求新的报告目录中不存在旧 stop signal；验收后创建该目录的 `browser-stop.signal` 才会做五张表的隔离断言并关闭进程／容器。没有新增测试账户到开发库，原用户保存实验未被本轮测试替换。

证据在忽略目录 `target/v2-p4/`：`frontend-tests.log`、`frontend-build.log`、`lifecycle.log`、`browser-harness.log`、`browser-checks.json`、`browser/browser-isolation.json`、`verification.json`、`source-manifest.json`。截图 `ui-playback-desktop.jpg`、`ui-playback-mobile.jpg`、`ui-tab-conflict.jpg`、`ui-long-restore.jpg` 均为合成验收，不能当成实盘提醒证据。

## 运行边界与下一步

本轮没有修改 Java 后端、规则状态机、Redis／Lua、Outbox、MQ、数据库迁移或正式写入开关。原 5173 Vite（前端改动自动加载）和 18081 后端保留，未重启开发后端；临时浏览器环境已关闭。工作区原有未提交改动均保留，无提交、推送或远端覆盖。

P4 无已知阻塞。长实验的大规模持续容量、整体现有业务回归、V1 升级演练和真实 Broker／故障验收仍属于 P5，尚未执行；本轮 14 小时／840 点样本不作为持续容量或最大规模结论。P2 的正常关闭握手、正式提醒端到端及用途许可遗留继续保留。进入 P5 或开启正式写入仍须用户另行确认。
