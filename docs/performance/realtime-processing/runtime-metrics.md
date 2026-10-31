# 后续自然行情观测的运行时指标

新增 `MarketLaneTelemetry`，以 `price-pulse.market.telemetry-enabled=true` 显式启用，默认关闭。本轮没有修改私人配置、重启正式进程或自动开放 Actuator 端点。原正式进程仍没有这些新增指标。

注册范围为当前活动股票，标签只有内部股票 ID，不包含用户、规则、事件 ID 或凭据；退订时移除该股指标。计数因此按活动订阅周期累计，采集器必须处理计数器重置。逐笔只更新内存聚合，不同步记录逐笔日志。

| Micrometer 名称 | 对应测量 |
| --- | --- |
| `pricepulse.market.input` | 有效行情 offer 总数；时间窗差分得到输入率 |
| `pricepulse.market.processed` | 真正返回 PROCESSED 的成交数量 |
| `pricepulse.market.rejected` | 重复／乱序／过期等正常返回但未接纳的行情，不计作处理成功 |
| `pricepulse.market.failed` | 工作线程处理异常 |
| `pricepulse.market.discarded` | 实际从队列移出的不确定任务数量，含重连／溢出等 |
| `pricepulse.market.overflow` | 队列溢出次数，不用它推算实际舍弃量 |
| `pricepulse.market.queue.depth` | 当前等待任务数，不含正在处理的任务 |
| `pricepulse.market.queue.oldest.wait` | 队首等待毫秒数 |
| `pricepulse.market.queue.wait` | 入队至工作线程开始的单调时钟间隔 |
| `pricepulse.market.process` | 工作线程完整 handler 耗时，包含事务返回 |
| `pricepulse.market.transaction` | ingest 事务代理外层耗时，含真正 commit；不含排队 |
| `pricepulse.market.receive.lag` | 既有接收 Instant 至 handler 完成的墙钟间隔，含等待；须同时核对时钟及连续性诊断 |
| `pricepulse.market.invalid_clock` | 接收墙钟间隔为负的样本，未伪造为 0 延迟 |
| `pricepulse.market.phase.rule_decision` | 单笔内纯状态判定累计时间 |
| `pricepulse.market.phase.trigger_outbox_write` | 单笔内触发／Outbox 累计调用时间 |

Timer 使用 Micrometer 时间单位，导出时按导出器定义转换为 ms；声明 P50／P95／P99。运行时滚动统计不能冒充离线原始样本的精确 nearest-rank 分位数。Counter 的差分窗口需记录实际起止时间和样本量。

MySQL 各 SQL 阶段、连接获取与 JDBC commit 的精细拆分由隔离 `PipelineProbe` 实测，不默认加在正式 Mapper 上。不要声称当前正式 SQL 已安装该代理。

## 正确性边界

主路径仍每股单线程。观测异常不得改变业务返回值或异常，线程作用域在完成／失败后释放；指标类的单元测试及开启该 bean 的隔离回归已覆盖。该组件不持有数据库连接，不创建供应商连接或通知，不修改规则／行情状态。

接收至完成使用现有接收墙钟，不能跨未经核对的系统时钟跳变计算真实延迟；排队和处理时长另外采用单调时钟。真实市场验收须保留客户端暂停／时钟缺口诊断，不能把长时间无成交当作连接故障。

## 受控加载后如何读取

指标只在显式启用且有对应活动 lane 后产生。默认 Actuator 仅暴露 health／info；代码没有扩展暴露范围。现有安全配置对 `/actuator/**` 放行，因此若为本机验收开放 metrics，必须在受控加载计划中保留回环绑定并核对访问范围，不把端点直接共享出去。

本轮尚未执行正式加载、未获得自然行情 P95／P99；详见 [待验清单](market-acceptance.md)。
