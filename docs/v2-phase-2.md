# V2-P2：真实行情适配、历史缓存与只读图表

日期：2026-10-29（Australia/Sydney）。

2026-10-30 连接专项补充：用户确认短停、独占 IEX 复验与原配置恢复后，心跳／客户端暂停分流、有界关闭等待及失败历史已加载到后端 81042／18081，十五股正式写入继续开启。93 秒休市观察与规则／业务数据保留通过；真实 IEX 主动关闭仍为 1006，严格关闭测试失败证据保留，P2 继续 PARTIAL。见[诊断、复验与加载记录](v2-stream-diagnostics-20261030.md)。

最新补充：用户已确认启用正常开盘跳空策略，正式库现为 V8，原 IDEA 后端及十五股正式写入保持运行。迁移数据保留与休市订阅／心跳复核通过，真实恢复首笔和后续连续跨日仍待自然行情，见[策略与运行记录](v2-opening-gap-policy.md)。以下均为此前阶段的历史结果，不能据此重做迁移或把历史“未启用”当作当前状态。

本文件保存初次 P2 实施及当时的只读状态。最新状态见 [2026-10-30 开市与 CTA 修复加载记录](v2-p2-live-open-20261030.md)：用户已明确确认同库正式写入及加载修复，V7、十五股及 30 条一次性验收规则已启用；十五股五分钟持续接收与 11 次自然触发的正式通知闭环已通过，94 项单元测试通过。P3／P4／P5 软件阶段已完成，P2 因供应商主动关闭遗留项仍为 PARTIAL。以下历史结论不覆盖后续授权与结果。

**状态：已补开市只读接收采样与临时库的实际采样处理实验；P2 整体仍为 PARTIAL。正式行情写入保持关闭，供应商正常关闭握手及实际行情到正式通知的端到端验收未完成。未进入 P3。**

开市结果见 [开市验收补充记录](v2-p2-open-session-acceptance.md)：四分钟收到 895 笔成交，414 笔符合生产条件，接收延迟 P95 134.08 ms；临时库每股 100 条规则、10 倍回放处理 414/414，事务 P95 51.80 ms，无溢出。关闭流程已修正并通过回归，但在线仍收到 1006，不能标记正常关闭已通过。

**后续经用户确认已完成本机备份、V6 升级和原 IDEA 后端重启，真实行情只读接入现已运行。**五股四窗口、事件页及前端代理通过，正式写入仍关闭。详见 [本机升级记录](v2-p2-local-rollout.md)。

## 本轮范围

沿用 Vue 3 原生 SVG、Spring Boot、MyBatis、MySQL、Redis 和现有 Outbox/RocketMQ，没有新增依赖。

- 增加 Alpaca IEX 的服务端 Java WebSocket：认证、固定 1–5 股订阅、退避重连、重新订阅、Ping/Pong、消息大小限制及连接健康状态。
- 增加交易日历和分钟历史缓存，历史下载独立于正式处理链路。首页切换为 AAPL、MSFT、NVDA、AMZN、GOOGL。
- 增加 1H / 1D / 1W / 1M 只读时间线与正式触发的独立分页。
- 图表按实际经过的时间定位，不压缩跨夜／周末；支持准星、轴高亮、当前阈值线及可展开的重叠触发标记。
- 保留 P1 的模拟隔离和旧入口门禁，没有开放 P3 的实验开始／推进接口。

## 数据与事务

新增 `V6__add_real_market_timeline.sql`，不改写已发布的 V1–V5：

| 位置 | 变化 |
| --- | --- |
| `quote_events` | 增加精确纳秒时间、provider、feed、接受顺序、处理完成时间、基线原因，共 6 列及查询索引 |
| `market_stream_state` | 每股一个正式处理水位，记录来源、连续段和纳秒时间；与原有股票行锁在同一事务提交 |
| `market_sessions` | 保存供应商正常交易日的 UTC 开闭市时刻，按纽约时区解析夏令时和提前收市 |
| `market_price_bars` | IEX / 1Min / raw 的 OHLC 缓存；仅保存已结束的正常交易分钟，值变化时增加修订号 |
| `market_history_coverage` | 保存最近一次完整下载所请求的范围及同步时刻，不把“请求过”解释为每分钟都有成交 |
| 股票目录 | 增加首批 5 股；已有同市场同代码记录保留名称、价格与业务关联 |

`stocks FOR UPDATE` 仍是正式匹配与真实规则编辑的串行边界。历史缓存表和覆盖表不设股票父行外键：运行时先从受控目录验证 ID，元数据采用软禁用；避免缓存批量插入因外键父行共享锁阻塞实时处理。增加了“持有股票排他锁时缓存事务仍能提交”的真实 MySQL 测试。

Redis 正式规则仍使用 P1 的 `pricepulse:v2:real:rules:{stock:ID}`；模拟仍使用独立 run UUID Key。首批只允许一种固定 provider/feed，不支持同时把多个来源混入同一个股票匹配基线。

历史同步只写新缓存，不修改 `stocks.latest_price`、真实规则、真实触发、Outbox、通知或模拟实验。

## 实时处理语义

- 保留原始 RFC3339 纳秒精度，MySQL 可读时间用微秒，排序与幂等另存 BIGINT 纳秒；对浏览器输出纳秒字符串。
- 成交身份由 provider / feed / 股票 / 纽约交易日期 / 交易所 / 供应商成交 ID 组成，哈希为固定长度 Key。成交 ID 不承担时间顺序。
- 同时刻不同成交按每股接收顺序处理，赋独立接受顺序；更早时间的迟到成交丢弃。相同 Key 同 payload 返回重复；不同 payload 冲突。
- 接收时刻在排队前记录，处理时刻另记；过期和未来行情不推进正式水位。
- WS 回调不执行数据库事务。每股有界串行队列；溢出或处理失败会清除不确定队尾、标记缺口并换连续段，下一笔建立基线。没有“静默抽样但声称提醒完整”。
- 启动、重连、明确缺口及新的正常交易日均换基线：ACTIVE 不凭空触发，COOLDOWN 可按当前价重新激活。历史缓存不补发缺口提醒。
- 本阶段只接收价格提醒所用的保守 regular-sale 子集（条件仅 `@` / `F`）；odd-lot、迟报等其他条件不用于匹配。报价、分钟线、撤销、修正不冒充实时成交；撤销／修正会标记连续性缺口，不追溯改写已经发送的业务事实。
- API Key 只从后端本机配置读取；错误只记录安全代码，不回传供应商响应体、密钥或原始认证帧。

这些策略不能保证供应商故障期间“不漏提醒”；Outbox 继续保障的是**已经提交的业务触发**的后续投递。

## 只读 API

```text
GET /api/market/stocks/{stockId}/timeline?window=1D&anchor=<optional UTC>
GET /api/market/stocks/{stockId}/timeline/events?from=<UTC>&to=<UTC>&cursor=<opaque>
```

沿用 JWT 与 ApiResponse。时间线含 REAL 作用域、明确的 IEX 来源、范围／新鲜度／覆盖信息、来源点引用、十进制字符串价格、当前配置阈值、实际触发快照和通知状态。

- 默认 1D，覆盖当前或最近正常交易日的完整开闭市范围，未来部分留空；1H 为锚点前一个墙钟小时；1W 为纽约日历前 7 天；1M 为纽约日历前一个月。
- 缺少日期对应的缓存日历时返回 503，不硬编码交易时间或猜测节假日。日历和历史由独立任务同步，GET 不联网补抓。
- 历史分钟点以区间结束时刻定位，给出 intervalStart / intervalEnd / availableAt / revision；不能在分钟开始时提前知道收盘价。
- 历史分钟缓存加后续已接受的实际成交尾段使用同一 IEX/raw 口径。绘图采样保留原始价格与 pointRef，不制造均价成交。
- 触发记录不依赖被采样的行情点。每页 100 条，额外读取 1 条判定下一页；带 HMAC 的游标绑定用户、股票、时间范围与已提交触发水位。
- 历史股票来自原始 quote，规则移动、修改或软删除不改写旧触发；所有权按原规则用户验证。
- 页面翻页时固定事件水位，价格与健康状态仍可继续刷新；手动刷新取新的触发快照。页面不可见时不轮询，切股／切窗中止旧请求。
- 所有新 GET 均只有查询依赖，测试断言报价、规则版本、股票价格、触发、Outbox、通知和模拟表保持不变。

`POST /api/market/quotes`、旧共享 replay / simulate 仍为 403。测试专用受控报价入口仍只存在于 test sources，不进入应用包。

## 实际账号验证

本节保留首次开市前验证；后续开市结果另见上述补充记录，不覆盖原始报告。

使用本机已配置凭据，独立运行 Java 客户端；**没有启动 Spring 业务上下文，没有连接业务数据库，没有启用正式写入**。

报告：`target/v2-p2/provider-readonly.json`（忽略文件，不含凭据）。

| 检查 | 结果 |
| --- | --- |
| IEX / raw 历史分页 | 5 页，42,774 根已结束的正常交易分钟线 |
| AAPL / MSFT / NVDA / AMZN / GOOGL | 分别 8,579 / 8,545 / 8,578 / 8,504 / 8,568 根 |
| 交易日历 | 55 个交易日；实际开闭市数据参与过滤 |
| Java WS 认证／5 股订阅 | 成功 |
| 主动断开后的自动重连／重新订阅 | 成功，建立 2 次连接 |
| 实际 Ping/Pong | 收到 1 次 Pong |
| 正常时段成交样本 | 本次短采样为 0；没有据此宣称吞吐验收通过 |
| 关闭握手 | 主动请求正常关闭后收到 1006；保留警告，正常关闭确认未通过 |
| 正式提醒端到端 | 未启用，待后续获准进行开市验收 |

1006 不被改写成“正常关闭”。实际运行仍将非预期关闭视为缺口并重连。前一次把关闭码必须为 1000 作为断言的在线核验失败，随后将“连通／重连”与“关闭握手”分开报告；没有把后者从遗留问题中删除。

## 测试与证据

| 检查 | 结果 |
| --- | --- |
| `./mvnw -B -ntp -Pintegration verify` | 58 单元测试 + 42 集成测试，全部通过；包含原有 24 个业务、P1 的 11 个隔离／迁移、7 个新时间线集成测试 |
| `./mvnw -B -ntp -Pmq-pipeline-it verify` | 真实临时 Broker 的通知链路 1 项通过 |
| `./mvnw -B -ntp -Pperformance -Dperf.scales=100 -Dperf.samples=50 -Dperf.burst=10 verify` | 小规模兼容／故障场景 1 项通过；不作为真实 WS 容量结论 |
| `node --test frontend/tests/timeAxis.test.ts` | 3 项通过，包含不等间距准星与分段几何 |
| `npm run build --prefix frontend` | 类型检查与构建通过，未增加包依赖 |
| 合成数据浏览器验收 | 四窗口、跨日间隔、轴高亮、旧阈值快照、同点两条事件展开、桌面／窄屏布局 |

单元测试涵盖纳秒身份、消息类型／条件门禁、历史分页、闭合分钟、RTH 过滤、DST／提前收市、签名游标、采样保留原点、认证重订阅、异常关闭、心跳超时、有界过载和故障基线。

集成测试额外覆盖同刻不同成交、去重冲突、旧事件顺序、基线重新激活、缓存修订幂等、缓存与股票锁互不阻塞、105 条本人事件翻页、越权游标、规则移动删除后的历史归属、正式写入口禁止、GET 无副作用及已有美股目录升级保留。

本地最初沙箱无法访问 Docker，提升本机容器访问权限后通过；开发业务库没有用测试数据替换。测试中发现并修复了首次水位 nullable 条件表达式的自动拆箱错误。

## 启动与阶段边界

公开默认配置两个开关均关闭。本机忽略文件 `.local/alpaca.env` 已准备只读连接：

```properties
APCA_API_KEY_ID=<仅本机填写，不提交>
APCA_API_SECRET_KEY=<仅本机填写，不提交>
APCA_API_ENV=paper
PRICEPULSE_ALPACA_ENABLED=true
```

`application-local.yml` 从项目工作目录导入该文件。`price-pulse.alpaca.business-writes-enabled` 仍为 **false**，无需把密钥再次放进 IDEA 或聊天。旧本机 `.run` 配置包含私人环境值，已排除 Git，并明确工作目录。

首次实现时没有停止旧后端或迁移开发库；**后续用户确认后已完成备份、停止旧实例、由原 IDEA 配置重启并应用 V4–V6**。当前 `PricePulse Local` 为 local / 18081，工作目录为含 pom.xml 的文件夹；前端 Vite 原实例保留。不要将 V1 与 V2 同时连接同一业务库，也不要重复执行本次升级。新环境初次试用仍应先备份再按此顺序升级，首次历史同步前数据明确显示缺失。

只读核验命令（固定 IEX，不购买订阅、不访问交易接口）：

```sh
node --env-file=.local/alpaca.env scripts/v2-p2-readonly-smoke.mjs
```

已补正常时段接收速率、事件到接收延迟、重连后的成交、临时库加速实验及升级后本机只读接入。临时实验不是实际业务写入或最大容量结论。待后续确认：供应商正常关闭握手及实际提醒到通知完整链路。数据展示／自动提醒用途许可仍须单独核实；IEX 不代表全市场实时行情。

## 修改位置

- `db/migration/V6__add_real_market_timeline.sql`：增量存储与目录。
- `market/AlpacaProperties`、`AlpacaProtocol`、`AlpacaDataClient`、`AlpacaStream`、`MarketQuoteLane`、`RealMarketRuntime`、`MarketFeedHealth`：服务端接入。
- `market/MarketDataModels`、`MarketDataMapper`（含 XML）、`MarketHistoryCache`、`MarketTime`、`MarketWindows`、`TimelineCursor`、`MarketTimelineService/Controller`：缓存、日历与只读契约。
- `market/QuoteCandidate/QuoteEvent/QuoteEventMapper/QuoteIngestionService/MarketConfiguration`、`alert/AlertRuleMapper/AlertEvaluationService`：精确顺序、基线与既有状态机适配。
- `application*.yml`、`.gitignore`：默认门禁、本机密钥导入与私人 Run 配置隔离。
- `frontend/src/api`、`DashboardView`、`PriceSparkline`、`utils/chart/timeAxis`：真实看板与原生图表。
- `market/*Test`、`MarketTimelineInfrastructureIT`、迁移／原业务 schema 测试、前端几何测试、只读在线核验脚本及本文档。

本轮临时浏览器数据是明确标注的合成验收夹具，截图不能作为实盘触发或真实性能证据。
