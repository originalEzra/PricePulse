# Phase 2：认证、股票目录与提醒规则生命周期

状态：**已完成（2026-10-25）**

## 目标

Phase 2 在已冻结的数据结构上建立第一个可以实际使用的多用户业务闭环：用户注册和登录后，可以查询股票，并且只能管理属于自己的提醒规则。

本阶段不实现行情生成、阈值匹配和通知消费；这些能力分别属于 Phase 3、Phase 4 和 Phase 5。

## 认证边界

- 注册时规范化邮箱并依靠数据库唯一键防止重复注册。
- 密码使用 BCrypt 保存，原始密码不会落库。
- 登录成功签发 HS256 access JWT，默认有效期 2 小时。
- JWT 包含用户 ID `sub`、邮箱、签发时间、过期时间和 issuer。
- Spring Security 验证签名、issuer 和过期时间。
- 业务 Controller 通过 `@CurrentUserId` 获取用户 ID，不直接解析 JWT。
- 没有 refresh token、Redis 黑名单、设备会话或服务端即时撤销。
- 退出接口只声明客户端应删除 Token；旧 Token 在过期前仍然有效，这一边界由自动化测试固定。

本地有开发用默认密钥。非本地环境必须通过 `JWT_SECRET` 提供至少 32 字节的随机密钥。

## 股票目录

股票是系统维护的数据，普通用户只能查询，不能自行创建或修改。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/stocks?query=&limit=` | 按代码或名称搜索活跃股票 |
| `GET` | `/api/stocks/{stockId}` | 查询单只活跃股票 |

`local` profile 通过独立的 Flyway repeatable migration 写入 3 只虚拟股票。正式迁移不夹带演示数据。

## 提醒规则 API

所有规则接口都要求 `Authorization: Bearer <token>`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/alert-rules` | 创建规则，初始状态为 `ACTIVE` |
| `GET` | `/api/alert-rules` | 按股票或状态筛选当前用户的规则 |
| `GET` | `/api/alert-rules/{id}` | 查询自己的单条规则 |
| `PUT` | `/api/alert-rules/{id}` | 修改 `ACTIVE` 或 `PAUSED` 规则 |
| `POST` | `/api/alert-rules/{id}/pause` | `ACTIVE → PAUSED` |
| `POST` | `/api/alert-rules/{id}/resume` | `PAUSED → ACTIVE` |
| `DELETE` | `/api/alert-rules/{id}?version=` | 软删除规则 |

所有读取和更新 SQL 同时携带 `rule_id + user_id`。访问其他用户的规则统一返回 404，不暴露目标 ID 是否存在。

## 规则语义

- 金额使用 `BigDecimal`，最多 4 位小数，不做静默舍入。
- `ONCE` 规则不能设置 `rearmPrice`。
- `REPEAT` 规则必须设置 `rearmPrice`。
- 上破规则的重新激活价必须低于阈值；下破规则必须高于阈值。
- 删除采用 `DELETED` 软删除，普通列表和详情不再返回。
- 修改、暂停、恢复和删除都要求客户端提交 `version`。
- SQL 使用 `WHERE version = ?` 并原子执行 `version = version + 1`；并发写入只有一个成功，旧版本返回 409。

`COOLDOWN` 和 `COMPLETED` 已在数据库状态中预留，但它们将在规则匹配状态机实现后才由系统产生。

## 验收结果

- 默认离线测试：7 个通过。
- MySQL/Redis 集成测试：4 个通过。
- 真实测试覆盖注册、邮箱冲突、BCrypt、正确与错误密码、JWT、过期 Token、未认证访问、跨用户隔离、规则校验、暂停、恢复、更新、软删除和乐观锁冲突。
- 本地演示股票迁移在真实 MySQL 8.4 上执行成功。
- RocketMQ 回归测试继续通过；Phase 2 未改变消息收发链路。

Phase 3 将实现确定性的模拟行情、历史回放，以及重复、过期和乱序行情策略。
