# V2：接口与时间契约

状态：真实时间线、模拟 API、P4／P5 已实施；十五股正式写入及开市提醒闭环已通过本机验收，P2 保留 IEX 关闭遗留项为 PARTIAL。最新运行见[开市记录](v2-p2-live-open-20261030.md)。本契约以最新产品方案及已确认决策为准；2026-10-30 新增开盘跳空的可空事件上下文，加载状态见[策略记录](v2-opening-gap-policy.md)。

## 公共约定

- 沿用 `ApiResponse(success, data, error)`、JWT、现有错误码；用户身份取 JWT，不接受请求体中的用户 ID。
- 股票／真实规则 ID 沿用现有表示；runId 为新 UUID；pointRef 为不透明的服务端引用。
- 新时间线／实验 DTO 的价格用十进制字符串，例如 `"100.0000"`；绘图可转换为 Number，业务比较留在后端。现有 V1 规则接口不在 P0 改型。
- 所有事件时间为带时区的 RFC3339 字符串，输出 UTC；可另外返回字符串 `epochNanos`，不能用 JS Number 承载纳秒整数。
- `1D` 是视图窗口，不是“一根日 K”；1H／1D／1W／1M 均返回时间范围及适合展示的真实价格点。
- GET 全部只读，不隐式创建实验、运行匹配、推进时钟、写触发、创建 Outbox 或通知。
- 价格查询与事件分页独立。不得因价格采样、阈值筛选或 limit 而静默丢掉实际触发。
- 本文件记录字段职责和操作语义；实现入口为 `MarketTimelineController` 和 `SimulationController`，standalone／mq-it 不开放模拟服务。

## 真实时间线

真实时间线事件、事件分页及规则触发历史新增可空 `triggerContext`，普通／旧事件为 null；开盘跳空时包含 `kind=OPENING_GAP`、十进制字符串 `referencePrice`、`referenceTime`／字符串 `referenceEpochNanos`、`referenceSource`、`referenceBarId`／`referenceRevision`／`referenceFetchedAt`、`sessionDate`／`sessionOpen`／`ruleEffectiveAt`。这些字段冻结本次比较依据，不替换实际成交的 `price`、`eventTime`、`epochNanos` 或原规则快照。通知使用持久化上下文标记“开盘跳空”，Outbox／MQ 消息协议保持原样。

`GET /api/market/stocks/{stockId}/timeline?window=1D&anchor=<UTC>`

| 字段 | 含义 |
| --- | --- |
| scope | 固定 `REAL` |
| stock / source | 股票、USD、provider、feed；IEX 明示单交易所 |
| range | from、to、asOf、marketTimeZone=America/New_York |
| coverage / health | 可用范围、缺口、最后事件时刻、休市／延迟／断线状态 |
| sessions | 真实交易日历的 date／open／close（UTC）；包含相邻时段边界，供交易时间轴压缩已知休市 |
| dataQuality | 已有／展示分钟点、预期闭合分钟端点、暂无分钟线的区间数、下载／日历范围覆盖及分钟线是否采样 |
| points | pointRef、eventTime、price、TRADE／BAR_CLOSE；分钟线另给 intervalStart、intervalEnd、availableAt、revision |
| thresholds | 当前用户的当前规则配置；明确不是完整历史配置 |
| events | triggerId、quoteId、eventTime、price、触发时规则快照、通知处理状态 |
| eventCursor / watermark | 事件独立翻页和固定查询水位，防止翻页时混入后续数据 |

`health.stream` 继续提供累计连接、订阅、Ping／Pong、心跳超时及最近失败／关闭信息。本轮连接诊断追加 `clientTimingGaps`、`lastCloseRequestedAt`、`lastCloseSentAt`、最多 20 项的 `recentFailures`（时间、错误码、连接尝试编号、上次帧／Ping／Pong、可空调度间隔／接收静默／墙钟偏差毫秒值）。`CLIENT_TIMING_GAP` 表示客户端时间不连续，`HEARTBEAT_TIMEOUT` 表示调度仍运行时接收静默；二者都走既有缺口重建基线策略。历史计数和失败不因恢复而清空，不包含供应商原文或认证数据。源码与运行加载状态见[诊断记录](v2-stream-diagnostics-20261030.md)。

`GET /api/market/stocks/{stockId}/timeline/events?from=...&to=...&cursor=...`

事件查询仍按 JWT 所有权过滤。历史股票归属来自原始报价，规则移动或删除不能改写旧事件。通知“等待创建”与“已创建但未读”分别表达，不用 Outbox SENT 代替通知已经生成。

窗口：

- 1H：锚点前 60 个墙钟分钟；休市时使用最近正常交易时段收盘锚点。
- 1D：当前／最近交易日开闭市区间，未发生部分留空。
- 1W：锚点前 7 个自然日中的正常交易时段数据。
- 1M：纽约日历向前一个月中的正常交易时段数据。

按 2026-10-29 用户确认的调整：1H／1D 保留真实时间比例；1W／1M 采用交易时间轴，仅压缩 sessions 确认的休市，显示 // 断轴及虚线跳空连接。每个连续交易时段内仍按实际经过时长排列，不能按数组下标等距。悬停显示纽约时间及原始 UTC，保留原始小数精度。

历史分钟 SQL 无行数截断，API 返回查询范围内全部缓存分钟点，不再额外按墙钟桶采样；实际成交尾段仍明确为采样的原始成交。dataQuality 区分缓存本身不足与展示采样：unavailableMinutes 表示日历内没有缓存分钟线的端点，可能为 IEX 稀疏无成交或缓存不足，不能据此补造行情。缺失的交易分钟不压缩，下载／日历范围不足时页面明确提示。

## 最近实验查询

`GET /api/simulations/current`

无实验返回 `success=true, data=null`，不自动创建。存在时返回：

- runId、stockId、version、lastBatchNo、simClock、latestPrice、nextPointSeq。
- origin（含服务端 point、prefixFrom、calendarVersion）、parameters（mode／stepRatio）、seed、generatorVersion、generatorSupported、ruleSnapshots 和 stats（triggered／rearmed）。
- seed 返回十进制字符串，避免 JavaScript 丢失 64 位整数精度；规则概览包含不可变 config、来源状态、初始状态和当前实验状态。
- 默认恢复为页面暂停状态；播放状态不是服务端后台任务。

查询结果不受页面当前 browseStockId 影响。选其他股票不能清空它。

## 开始／替换实验

`POST /api/simulations/start`，必填 `Idempotency-Key`（客户端生成 UUID）。

```json
{
  "stockId": 1,
  "origin": {
    "kind": "HISTORICAL_POINT",
    "pointRef": "opaque-server-reference",
    "revision": "returned-revision"
  },
  "mode": "NORMAL",
  "stepRatio": "0.003",
  "seed": "42",
  "expectedCurrentRunId": null
}
```

- `LATEST` 起点只需 kind，由后端读取有效真实最新价。历史起点必须提供服务端返回的 pointRef／revision，不能自行填价。
- seed 可省略或为 null，由后端生成并保存；接受有符号 64 位十进制字符串，安全整数也可传 JSON 数字。页面保留字符串，不先转为 Number。0.003 表示 0.3%，不表示 30%；幅度须大于 0、最多六位小数。
- expectedCurrentRunId 必须显式提供：无旧实验为 null，有旧实验为其 UUID。
- 同一开始 Key、相同规范化请求返回同一个新实验；同 Key 不同请求为 409。
- 并发替换检查实际旧实验；过期请求返回 409，客户端刷新并重新确认。旧请求不能复活已替换实验。
- 创建／替换事务失败不丢旧结果。模拟起点、真实前缀和自己的规则配置使用同一一致性快照。
- 开始只保存基线，不因起点已经超过阈值而直接触发。D1 初始化状态，D3 固定输入。

成功返回与 current 相同的实验 DTO，包括实际采用的起点、规则快照列表、version=0、lastBatchNo=0。LATEST 只取已接受的 ALPACA／iex 正式成交水位；没有该水位返回 503，不回退到历史收盘价。

## 推进一个交易小时

`POST /api/simulations/{runId}/advance`，必填 `Idempotency-Key`。

```json
{
  "expectedVersion": 0,
  "expectedBatchNo": 0
}
```

expectedBatchNo 指最后已提交批次编号，初始为 0。服务端固定推进 60 个正常交易分钟，生成 60 个按交易时长间隔一分钟的点；不接受调用方改变持续时长或实验参数。

成功返回 runId、batchNo、versionAfter、fromClock、toClock、firstPointSeq、lastPointSeq、points，以及本批 triggered／rearmed 数量。事件通过独立 events 接口查询。

关键顺序：

1. 校验实验归属、仍是最近实验，并锁定实验行。
2. 先查该 Key 的已提交记录；相同请求返回原批次，不因其旧 version 已落后而重复生成。
3. 对新请求检查预期版本与批次，执行本小时事务。
4. 原子提交价格、状态、事件、时钟与版本。任何一步失败均不留下半小时结果。

旧批次重试返回的是该批次原始 versionAfter；前端不能用旧响应覆盖更高版本的当前状态。后端不能给原批次伪造一个新版本冒充新推进。

P3 仅手动请求。P4 的自动播放将等待该请求完成再发送下一次；暂停／离开页面停止新请求，已经受理的小时可完成。不提供服务端持续运行的播放任务。

## 模拟只读图表与事件

`GET /api/simulations/{runId}/timeline?from=...&to=...&cursor=...`

返回 scope=SIMULATION、runId、stockId、version、simClock、origin、range、thresholds、stats、pointCursor、watermark 和两段曲线（字段分别为 realPrefix／simulation）：

- REAL_PREFIX：开始时冻结的真实历史，只到起点。
- SIMULATION：本实验已经提交的生成价格，只从起点之后开始。

曲线起点只画一次，不能在模拟后缀混入后来发生的真实价格。返回同样的 sessions 字段，来源为实验冻结日历；真实前缀与模拟后缀共用上述交易时间轴，压缩休市、显示 // 和虚线跳空，交易时段内仍保留真实间隔，不能生成盘后假点。该接口不调用真实规则或正式通知查询来推导模拟结果。

from／to 可省略，默认覆盖冻结前缀起点到已保存 simClock；响应返回实际范围。每页最多十个小时批次／600 个模拟点，真实前缀单独返回。继续翻页须重复原范围并传 pointCursor，固定批次水位；后续推进不会混入旧页，version／simClock／stats 对应该水位。thresholds 的 config 始终来自冻结配置；当前规则状态以 current 为准。

`GET /api/simulations/{runId}/events?from=...&to=...&cursor=...`

返回本实验 TRIGGERED／REARMED 事件；触发详情包含稳定事件 ID、pointSeq、时间、价格、快照规则 ID／名称／方向／阈值、状态转换。前端仅将 TRIGGERED 用 ▲／▼ 展示，REARMED 可用于现有冷却轨迹。

from／to 必填。每页最多 100 条，返回 events／eventCursor／watermark。事件有 epochNanos 字符串和完整 ruleSnapshot；独立游标绑定本人、实验、事件查询类型、范围和固定事件水位，不能与价格游标混用。

非本人或已替换 runId 返回 404；不能通过提供其他 runId 查询另一用户的快照。模拟事件没有通知状态、通知 ID 或 MQ 投递状态。

## 错误与阶段边界

- 401：未登录／JWT 无效。
- 404：无权访问或已替换实验、无相应股票／事件。
- 409：预期实验／批次／版本冲突，起点修订，同幂等 Key 不同内容，或旧生成器版本不再支持推进（仍可浏览）。
- 400／422：沿用当前参数／业务校验的区分，不在本轮改统一错误结构。
- 503：行情、日历或 Redis 不可用；原实验／原时钟保留，客户端重试使用原 Key。

P0 固定契约及验证外部数据语义；P1 实施存储隔离，P2 接真实时间线，P3 已接模拟生命周期，P4 待确认后接播放体验。阶段交付不自动开启正式行情写入。
