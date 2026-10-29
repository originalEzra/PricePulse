# Phase 5：Outbox、RocketMQ 与站内通知

## 本阶段目标

Phase 4 已在“规则触发”事务中同时保存触发记录和 `PENDING` Outbox。本阶段把这条持久化任务真正发送到 RocketMQ，并由消费者幂等生成站内通知。

完整链路为：

```text
行情穿越阈值
  → MySQL：规则状态 + 触发快照 + PENDING Outbox（同一事务）
  → Outbox Publisher：数据库租约 + 普通 RocketMQ 消息
  → RocketMQ：缓冲和失败重投
  → Notification Consumer：校验事件 + 幂等写入
  → MySQL notifications
  → 用户查询并标记已读
```

本阶段没有追求消息只发送一次。它采用更现实的 **at-least-once 传递 + 业务幂等**：消息可以重复到达，但同一个触发只能生成一条用户通知。

## 为什么继续使用 Outbox，而不是事务半消息

规则状态、触发记录和 Outbox 已经由一个本地 MySQL 事务提交。即使 Broker 暂时不可用，行情处理也能先保存不可丢失的业务事实，发布器恢复后再发送。

如果消息已经被 Broker 接收，但应用在把 Outbox 标记为 `SENT` 前崩溃，该任务会在租约过期后再次发送。因此系统不宣称端到端 exactly-once；最终正确性由 `notifications.trigger_id` 唯一约束保证。

## 发布器状态与租约

Outbox 使用已有四种状态：

```text
PENDING ──领取──→ SENDING ──Broker SEND_OK──→ SENT
                       └──失败──→ FAILED ──到达重试时间──→ SENDING
```

实现细节：

- 每次用 `SELECT ... FOR UPDATE SKIP LOCKED` 按 ID 领取最多 50 条任务，多实例不会等待同一批行。
- 领取时写入随机 `workerId`、`lockedAt`，并增加 `attempts`。
- 只有持有当前租约的发布器才能把任务改为 `SENT` 或 `FAILED`。
- `SENDING` 超过 30 秒视为发布器崩溃，后续扫描可以重新领取。
- 发送失败使用指数退避：从 2 秒开始，最多 2 分钟。
- 最多领取 8 次；达到上限的任务保留为 `FAILED`，不再自动领取，方便后续排查。
- 每轮最多处理 4 批，之后让出调度线程，避免一次扫描长期占用进程。

RocketMQ 的 `SEND_OK` 只能证明 Broker 接受了消息，不证明消费者已经完成通知。因此发布确认和消费结果是两个独立阶段。

## 消费幂等与历史快照

消息负载版本为 `schemaVersion=1`，包含：

- `triggerId`
- `userId`
- `ruleId`
- `stockId`

消费者不会直接相信消息字段，而是按 `triggerId` 读取数据库中的触发事实，并核对用户、规则和股票。校验通过后，使用触发时保存的 `rule_snapshot` 生成标题和内容。

必须使用快照，而不能读取规则当前值。因为消息可能延迟：规则触发后，用户可能修改规则或把规则移到另一只股票；历史通知仍应显示触发当时的股票、阈值和规则名称。

`notifications.trigger_id` 有唯一约束，插入使用幂等 upsert。下列重复情况都会得到同一条通知：

- RocketMQ 重复投递。
- 发布成功但 Outbox 未及时标记 `SENT`，租约恢复后再次发送。
- 消费者写入通知后、向 Broker 返回成功前崩溃。

消费者错误最多由 RocketMQ 重投 3 次，之后进入该消费组对应的死信队列。当前尚未实现 DLQ 管理页和人工重放接口，这属于明确的运维边界。

## 通知接口

接口均要求 access JWT，并按当前用户隔离：

| 接口 | 作用 |
| --- | --- |
| `GET /api/notifications?status=UNREAD&limit=50` | 查询自己的最新通知，可按 `UNREAD / READ` 筛选 |
| `GET /api/notifications/feed` | 获取自己的通知总数、准确未读数及最新 20 条通知，用于轻量轮询 |
| `POST /api/notifications/{notificationId}/read` | 幂等标记自己的通知为已读 |
| `POST /api/notifications/read-all` | 将当前用户已有未读通知批量标为已读，返回本次变更条数 |

响应包含触发 ID、规则 ID、触发时股票、触发价格、行情时间、通知内容、已读状态和创建时间。访问别人的通知按不存在处理。

`feed` 在一个只读、可重复读事务中查询计数和最新记录。未读数覆盖该用户的全部通知，不受列表最多 100 条的限制。总数用于发现新通知，不把自增 ID 当作提交顺序；最新记录用于生成提示内容。批量已读不会修改已有已读时间，也不会提前标记之后才生成的通知。新生成的通知文案价格显示两位小数，原始触发价格精度不变。

## 配置与运行边界

完整本地环境在 `application-local.yml` 中启用消息链路。默认 `standalone` 模式仍不连接数据库、Redis 或 MQ。公共配置中的 `price-pulse.messaging.enabled=false` 防止没有 Broker 的测试或引导环境误启动消费者；部署到其他环境时需要显式启用并配置 NameServer。

关键配置：

| 配置 | 默认值 |
| --- | --- |
| Topic | `price-pulse-alert-triggered` |
| Consumer Group | `price-pulse-notification-consumer` |
| 扫描批量 | 50 |
| 单轮最大批次 | 4 |
| 发布最大尝试次数 | 8 |
| 发布租约 | 30 秒 |
| 发布退避 | 2 秒起，最多 2 分钟 |
| 消费失败重投 | 3 次，之后 DLQ |

## 验收

离线和真实依赖测试覆盖：

- 默认独立模式仍可在没有中间件时启动。
- `PENDING / FAILED` 任务领取、失败退避和最终 `SENT`。
- 发布器领取后崩溃，过期 `SENDING` 租约可以恢复。
- 达到最大尝试次数后停止自动领取并释放租约。
- 同一消息消费两次只生成一条通知。
- 规则在触发后被移到其他股票，通知仍使用触发快照。
- 通知列表、状态筛选、用户隔离和幂等已读。
- 真实 RocketMQ Broker 上完成 Outbox → Broker → Consumer → Notification，并再次发送同一负载验证最终幂等。

验收命令：

```bash
./mvnw -B -ntp test
./mvnw -B -ntp -Pintegration verify
docker compose -f compose.yaml up -d
./mvnw -B -ntp -Pmq-pipeline-it verify
```

- 默认离线测试：7 个通过。
- 真实 MySQL/Redis 集成测试：15 个通过。
- 完整 RocketMQ 通知链路测试：1 个通过。
- 数据库仍为 7 张业务表、72 个字段；本阶段复用了 Phase 1 已预留的 Outbox 和通知字段，没有新增迁移。

本阶段尚未做通知洪峰压测、积压消化时间统计、DLQ 人工重放和可视化监控，不提前宣称吞吐量；这些数据属于 Phase 7。
