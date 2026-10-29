# 真实与模拟图表：统一交易时间轴

日期：2026-10-29，Australia/Sydney。用户在 P3 完成后明确提出的图表优化；不代表进入 P4，也不启用正式行情写入。

状态：实现、自动化、五分支浏览器及本机只读核验均通过，已通过原 IDEA 配置加载到本机。P4／P5 未开始。

## 显示规则

两个页面继续使用同一个 `PriceSparkline.vue`，它们的坐标、刻度、价格、事件、起点分界及准星都使用 `utils/timeAxis.ts` 的 `createTimeAxis`。

- 真实 1H／1D 使用经过时间；1W／1M 及模拟使用交易时间轴。查询窗口的真实起止范围不变。
- 压缩仅依据后端返回的相邻正常交易时段 open／close。真实图取缓存日历，模拟取实验冻结日历。缺少日历的未知范围不猜测休市，也不从报价间距推断休市。
- 每段休市保留小型可见间隔，底部 // 双斜杠和浅色带标出省略区间；可查看实际休市起止。全图最多 24% 宽度用于这些间隔，其余按真实交易经过时长分配。
- 连续交易时段内仍按时间比例，不按数组下标等距；交易时段内缺少五分钟仍占五分钟的宽度，不压缩为一个普通点间距。
- 休市前后使用分段曲线、无跨休市面积填充，并以细虚线表示仅连接两个已知价格的跳空。没有插入任何行情点。
- 悬停／键盘准星在映射后的坐标中选择最近的实际行情点。纽约标签显示日期、秒、原始小数精度及 EDT／EST，同时显示原始 UTC；没有改写时间戳、价格或触发事件。
- 模拟的真实前缀、模拟后缀、起点和事件统一映射。前缀中的交易时段内缺失分钟同样断线并提示，不能借休市压缩隐藏。

## 1W／1M 数据审计与修复

历史 SQL 的 `MarketDataMapper.bars` 没有 LIMIT，按 ALPACA／iex／1Min／raw、真实时间范围读取。供应商下载已经逐页读取，并在重复 page token 时拒绝发布不完整覆盖范围。

发现的问题在 API 后处理：原 `MarketTimelineService.sample(...,1000)` 按整个墙钟范围分桶，在隔夜／周末很多的 1W／1M 中丢掉大量已有分钟点。修复前本机 AAPL 返回 1H=61、1D=390、1W=199、1M=221。数据库本身有约一个月的分钟缓存，不能把这种过度采样误认成供应商只有少量数据。

现在返回查询范围内全部缓存分钟点，保留原 pointRef／revision／时间／价格；没有平均值或虚构点。实际成交尾段继续明确为原始成交采样，不改变正式处理。

新增只读 `dataQuality`：

| 字段 | 含义 |
| --- | --- |
| availableBars／displayedBars | SQL 读取的缓存分钟线数／返回的分钟点数，目前相等 |
| expectedMinutes | 日历内已闭合、端点落在查询范围内的分钟区间数量，含范围端点 |
| unavailableMinutes | 预期区间中没有缓存分钟线的数量；可能是 IEX 无成交，也可能是缓存不足，不能自行填价 |
| historyCoverageComplete | 记录的历史下载范围是否覆盖本次查询 |
| calendarCoverageComplete | 日历日期范围是否覆盖本次查询 |
| barSamplingApplied | 固定 false，历史分钟线不再额外采样 |
| tradeTailPoints | 返回的实际成交尾段采样点数量，和历史分钟线区分 |

页面展示这些数量和覆盖警告。交易时段内缺口保持真实宽度并断线；下载／日历范围不足时明确提示。断轴只改变已知休市的展示宽度，不能作为数据完整性的证明。

悉尼 14:05，本机修复后 AAPL 的 1W 返回 1,951 点、1M 返回 8,580 点；后者预期 8,581 个端点，仍有 1 个区间无缓存分钟线，页面按真实缺口提示。五股的下载／日历范围均覆盖查询，实际分钟线仍存在以下稀疏区间，没有补造价格：

| 股票 | 1W 缓存点／暂无分钟线区间 | 1M 缓存点／暂无分钟线区间 |
| --- | --- | --- |
| AAPL | 1,951／0 | 8,580／1 |
| AMZN | 1,946／5 | 8,505／76 |
| GOOGL | 1,950／1 | 8,569／12 |
| MSFT | 1,951／0 | 8,547／34 |
| NVDA | 1,951／0 | 8,579／2 |

该数量来自核验时缓存，后续供应商修订／同步可能变化；下载范围覆盖不等于每一分钟都有 IEX 成交。

## 验证

自动化覆盖：1H／1D 不规则时间间距、跨周末与 DST、假日及提前收市日程、月份断轴总宽度、坐标正反映射、映射后准星、纽约纳秒标签、真实前缀缺失分钟、完整月份超过 8,000 个点无截断，以及所有查询不写业务状态。

```sh
node --test frontend/tests/timeAxis.test.ts frontend/tests/simulationRequests.test.ts
npm run build --prefix frontend
./mvnw -B -ntp -Dtest=MarketDataTest,SimulationCalendarTest,SimulationGeneratorTest,SimulationCursorTest test
./mvnw -B -ntp -DskipTests package
./mvnw -B -ntp -Dit.test=MarketTimelineInfrastructureIT,SimulationLifecycleInfrastructureIT,SimulationRestartIT \
  test-compile failsafe:integration-test failsafe:verify
```

浏览器使用独立可丢弃的合成环境，专门保留每个交易日的五分钟数据缺口，检查四窗口及模拟分支；不创建开发库演示账户。夹具可通过 `-Dpricepulse.browser-report-dir=target/v2-time-axis -Dpricepulse.chart-fixture=true` 选择独立证据目录，不覆盖 P3 原证据。

最终通过 19 项相关后端单元、21 项不同业务集成／重启、12 项前端测试及构建，另 1 项浏览器夹具。首次组合集成有一条新增断言仍假定日历首项就是当前日；因为现在会包含相邻日，该假定不成立。改为按日期定位后，8 项时间线回归全部复验通过，原失败日志保留；没有改动业务代码来迎合错误断言。

浏览器结果（均为合成数据）：

| 分支 | 绘制点数 | 断轴／跳空连接 | 观察 |
| --- | --- | --- | --- |
| 1H | 61 | 0／0 | 原始经过时间，15:00–16:00 |
| 1D | 385 | 0／0 | 五分钟缺线导致两端相隔六个普通分钟点距，比例实测约 6 |
| 1W | 1,926 | 5／5 | 隔夜和周末压缩；保留 25 个暂无分钟线区间 |
| 1M | 8,471 | 22／22 | 所有缓存点参与绘图；保留 110 个暂无分钟线区间 |
| 模拟 | 770 个真实前缀＋60 个模拟点 | 2／2 | 唯一起点，原点到跨夜首个模拟点约 12.88 px；前缀内两段缺线仍断开 |

月图准星最终点显示 `2026-10-28 16:00:00 EDT（纽约）`，保留原 UTC `2026-10-28T20:00:00Z`。模拟的两个事件仍为纽约 09:31／09:32、100.3457／100.5203；刷新后恢复相同交易轴、断轴和 60 个后缀点。结束夹具前五张正式表新增恒为 0，临时页面／进程／容器均已关闭。

截图：`ui-1h.jpg`、`ui-1d.jpg`、`ui-1w.jpg`、`ui-1m.jpg`、`ui-1m-hover.jpg`、`ui-simulation.jpg`；交互实测值见 `browser-checks.json`。以上均在 `target/v2-time-axis/`，不能作为真实提醒端到端证据。

只读本机审计：`node scripts/v2-p2-local-readonly-check.mjs --axis`；旧实例比较使用 `--axis-before`。本轮汇总保存在忽略目录 `target/v2-time-axis/`，既有阶段记录、汇总和截图保留；通用重启用例的默认日志会更新为最新复验，当前重启报告另复制到本轮目录。

本机通过原 IDEA `PricePulse Local` 平稳重启后端 18081，原 5173 前端保留；五股四窗口共 20 个 GET 和 5173 代理均通过。Flyway 保持 V6，无新迁移；LEGACY 报价 961、REAL／正式水位 0、触发／Outbox／通知各 5。用户已有 1 个实验、版本 1、1 个批次，前后均保留；未访问实际账户或替换其结果。Alpaca env、IDEA Run 和前端代理三份私人文件指纹一致。

汇总及源码指纹：`verification.json`、`source-manifest.json`；本机前后结果：`live-before.json`、`live-after.json`。上述证据的点数与合成浏览器点数应分开解读。

正式写入保持关闭，P2 的 1006／正式通知端到端／用途许可遗留不因本次图表优化自动通过；P4／P5 未开始。
