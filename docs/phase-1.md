# Phase 1：正式数据结构与 API 契约

状态：**已完成（2026-10-25）**

## 目标

Phase 1 将 Phase 0 的兼容性工程升级为可以承载正式业务的后端底座。本阶段不实现股票、规则和通知的业务接口，只冻结所有后续模块共同依赖的数据边界和 HTTP 契约。

本阶段交付：

- 7 张核心业务表及必要约束、外键和查询索引。
- Flyway `V2` 迁移，可在已执行 `V1` 的环境上增量升级。
- 统一的成功与错误响应结构。
- 业务异常、参数校验异常、非法 JSON 和未知异常的集中处理。
- 数据库存储 UTC 时间、API 使用 ISO-8601 时间的约定。
- 模块化单体的代码组织约定。

## 数据模型

| 表 | 字段数 | 负责内容 |
| --- | ---: | --- |
| `users` | 7 | 用户身份和状态 |
| `stocks` | 10 | 股票基础信息和最新行情快照 |
| `quote_events` | 10 | 可去重、可回放的行情输入 |
| `alert_rules` | 14 | 阈值、方向、模式和状态机数据 |
| `alert_triggers` | 8 | 不可丢失的业务触发事实 |
| `outbox_events` | 13 | MQ 待发布事件、重试状态和发布器租约 |
| `notifications` | 9 | 用户可见的站内通知和已读状态 |

共 7 张表、71 个字段。`phase0_probe` 只用于兼容性回归，不属于业务模型，后续可在单独清理迁移中删除。

### 关键约束

- `users.email` 唯一。
- `stocks(market, symbol)` 唯一。
- `quote_events.event_key` 唯一，负责行情去重。
- 重复提醒必须设置符合方向的 `rearm_price`：上破规则的重新激活价低于阈值，下破规则高于阈值。
- `alert_triggers.trigger_key` 唯一，负责业务触发幂等。
- `notifications.trigger_id` 唯一，负责 MQ 重复消费幂等。
- Redis 不是事实来源，ZSet 后续从 `alert_rules` 重建。

## 为什么使用 Outbox

股票行情不会因为 RocketMQ 故障而停止变化。如果先发送事务半消息，Broker 不可用时本地触发事务无法执行，持续推进的行情可能让一次阈值穿越被遗漏。

因此正式链路使用本地 Outbox：

```text
MySQL 事务：规则状态 + 触发记录 + PENDING Outbox
                               ↓
                独立 Publisher 普通发送 RocketMQ
                               ↓
                       幂等创建站内通知
```

Publisher 只有收到 Broker ACK 后才把记录改为 `SENT`。失败时增加 `attempts` 并设置 `next_retry_at`。发布实例先把记录从 `PENDING` 抢占为 `SENDING`，同时写入 `locked_at` 和 `locked_by`；实例宕机后，过期租约可以被其他实例回收。

消息成功发送但来不及标记 `SENT` 时会重复发送，因此消费者仍必须依靠 `notifications.trigger_id` 唯一约束实现幂等。

## HTTP 响应契约

成功响应：

```json
{
  "success": true,
  "data": {
    "id": 1
  },
  "error": null
}
```

失败响应：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": [
      {
        "field": "thresholdPrice",
        "message": "must be greater than 0"
      }
    ]
  }
}
```

规则：

- HTTP 状态码表达协议结果，不能所有请求都返回 `200`。
- `code` 是前端稳定判断依据；`message` 用于用户或开发者阅读。
- 参数错误可返回字段级 `details`，业务冲突通常不需要字段详情。
- `500` 响应不暴露堆栈、SQL、密钥或第三方响应正文。
- Actuator 端点保持 Spring Boot 原生格式，不套业务响应信封。

## 代码组织

公共能力只放在 `common`：

```text
common/
├── api/       # ApiResponse、ApiError、FieldViolation
└── error/     # ErrorCode、BusinessException、统一异常处理
```

正式业务按领域组织：

```text
user/
stock/
market/
alert/
matching/
notification/
assistant/
```

每个领域内部再包含自己的 Controller、Service、Mapper 和模型。禁止建立全局 `controller/`、`service/`、`mapper/` 大目录，也禁止一个模块直接修改另一个模块的数据表。

## 验收

- `./mvnw test`：7 个测试通过，覆盖默认启动、成功响应、业务错误、参数错误、非法 JSON 和离线 AI 转换。
- `./mvnw -Pintegration verify`：3 个集成测试通过；Flyway 在真实 MySQL 8.4 执行到 `v2`，核对 7 张表和 71 个字段，并验证 MyBatis 与 Redis。
- `./mvnw -Pmq-it verify`：1 个集成测试通过，真实 RocketMQ Broker 的普通消息收发未发生回归。

Phase 1 完成后，Phase 2 才开始实现股票、用户隔离和提醒规则生命周期。
