# Phase 0：最小工程与技术验证

## 本次交付范围

建立单个 Maven / Spring Boot 工程，包名为 `com.ezra.pricepulse`，提供默认独立启动模式、本地中间件配置和分层测试入口。

本次只搭建兼容性验证基础。Redis 大规模匹配实验、业务状态机、Outbox 可靠发布、正式规则接口和前端仍待后续阶段实现。

## 架构与模块边界

当前保持模块化单体，不拆分微服务。正式模块按业务职责组织，避免所有模块互相直接操作数据库表或 Redis Key。

| 模块 | 负责内容 | 后续接入方式 |
| --- | --- | --- |
| 用户与权限 | 登录、身份与用户数据隔离 | 为业务接口提供当前用户 |
| 股票与行情接入 | 股票信息、模拟与历史回放、统一行情事件 | 新行情源通过同一接入接口进入 |
| 行情处理 | 事件合法性、过期与乱序策略、同股票顺序处理 | 先单实例固定分区执行器 |
| 提醒规则 | 阈值、方向、启停、一次性与重复提醒 | 提供规则生命周期接口 |
| 规则索引 | 按股票及阈值维护 Redis ZSet，可重建 | 输出候选规则，不决定业务触发 |
| 匹配与状态机 | 穿越判断、重新激活、条件更新 | MySQL 状态与唯一约束作最终校验 |
| 触发与 Outbox | 同事务保存状态变化、触发记录与待发消息 | 发布器重试未确认的 Outbox |
| 通知 | 消费幂等、站内通知、失败处理 | 后续通知渠道通过发送接口接入 |
| AI 规则草稿 | 自然语言转结构化草稿 | 后端校验、用户确认后调用规则接口 |
| 测试与指标 | 测试数据、故障注入、性能记录 | 可重复脚本与报告 |

核心业务链路规划：

```text
模拟 / 回放行情 → 校验与按股票顺序处理 → Redis 候选规则
                                                ↓
                   MySQL 条件更新 + 触发记录 + Outbox（同一事务）
                                                ↓
                                  MQ → 幂等消费 → 站内通知
```

价格采用 `BigDecimal` 和按固定精度缩放的整数阈值。重复、过期和乱序行情的最终语义须在状态机实现前写入测试用例。

## 版本选择

本工程选 Java 21、Spring Boot 3.5.16、MyBatis Starter 3.0.5、RocketMQ Spring Starter 2.3.6、RocketMQ 5.3.2 和 Spring AI 1.1.8。

MyBatis 3.0 系列支持 Boot 3.2–3.5；Spring AI 1.1 与 Boot 3.5 属于兼容的版本线。RocketMQ 采用传统 Remoting 客户端，当前只需 NameServer 和 Broker。组件版本固定，测试数据和运行环境也应记录版本，方便重现实验。

测试依赖、驱动和通用库优先使用 Spring Boot BOM。若后续修改其中任一版本，应重跑启动和真实服务访问测试。

官方参考：

- [MyBatis Starter 兼容表](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)
- [Spring Boot 3.5 依赖管理](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html)
- [RocketMQ Spring 发布记录](https://github.com/apache/rocketmq-spring/releases)
- [RocketMQ Docker 快速开始](https://rocketmq.apache.org/docs/quickStart/02quickstartWithDocker/)
- [Spring AI 1.1 文档](https://docs.spring.io/spring-ai/reference/1.1-SNAPSHOT/getting-started.html)

## 本次最小工程验收

| 项目 | 验证方法 | 验证结果 |
| --- | --- | --- |
| Java 21 编译 | Maven 编译与打包 | 通过，Temurin 21.0.13，`./mvnw verify` BUILD SUCCESS |
| 默认启动 | 访问系统信息与健康接口 | 通过，运行打包 JAR，两个接口均返回 HTTP 200，健康状态 UP |
| MyBatis / MySQL | Testcontainers 中写入并读取记录 | 通过，真实 MySQL 8.4.7 容器完成 Flyway 迁移并验证访问 |
| Redis ZSet | Testcontainers 中写入并查询区间 | 通过，真实 Redis 7.4.6 容器完成 ZSet 区间查询验证 |
| RocketMQ | 真实 Broker 上发送并消费同一条验证消息 | 通过，Compose Broker 启动健康，消息收发闭环 1 项测试成功 |
| Spring AI 离线转换 | JSON Schema / 类型转换测试 | 通过，3 项测试；连同启动检查共 4 项，0 失败、0 跳过 |
| 真实模型输出 | Groq 免费 API + `openai/gpt-oss-20b` 执行 `ai-live` | 通过，真实请求返回指定 JSON Schema，Java DTO 转换与 Bean Validation 均成功 |
| Compose 配置 | Compose `up -d` 与容器健康检查 | 通过，MySQL、Redis、NameServer、Broker 均健康；权限初始化容器正常退出 0 |

验证日期：2026-10-24（Australia/Sydney）。默认构建不会运行 `*IT`；中间件和真实模型分别通过显式 Maven profile 启用。已用 `-Pintegration failsafe:integration-test failsafe:verify` 确认 Docker 不可用时明确失败，没有自动跳过。

已核对 MySQL 8.4.7、Redis 7.4.6-alpine、RocketMQ 5.3.2 镜像均包含 ARM64，未强制 x86 模拟。依赖树中 Spring Framework 为 6.2.19，Jackson 为 2.21.4，RocketMQ Client 为 5.3.2，Testcontainers 为 1.21.4。RocketMQ 间接带入的重复 `commons-logging` 已排除，使用 Spring 的 `spring-jcl`；其上游 Javassist 旧 POM 仍有 Maven 模型告警，但编译、应用启动和 MQ 收发实测均通过，当前仅作为已知告警记录。

本机 Docker Desktop 已恢复，验证版本为 Engine 29.9.2、Compose v5.6.1。RocketMQ 首次启动时发现持久化卷由 root 创建，Broker 用户 UID 3000 无法写入；Compose 已增加一次性 `rocketmq-init` 服务修正卷所有权，修复后 Broker 健康并完成真实收发测试。

真实模型测试默认不随普通构建执行。2026-10-24 已使用 Groq 免费 API 完成一次真实调用验证；未配置 API Key 时显式执行 `ai-live` 会明确失败，不用本地假响应替代实测结论。

## 后续业务阶段待验证项

Phase 0 已经完成依赖兼容性和基础链路验证。下面这些问题必须跟随正式业务模块逐步实现和验证，不能因为工程底座已经创建就自动视为完成。每项都应形成“问题、候选方案、最小实验、验收条件、实际结果、最终决策”的记录。

- [ ] 明确价格精度、阈值穿越边界、规则创建与重新启用的起算语义。
- [ ] 明确重复、过期与乱序行情策略及同股票顺序处理边界。
- [ ] 验证 ZSet 在不同规则规模和候选命中数量下的正确性、P95 / P99 和内存。
- [ ] 验证规则修改、暂停、删除与 Redis 索引同步；约定生效语义和失败补偿。
- [ ] 验证一次性、重复提醒和重新激活状态机，覆盖并发竞争。
- [ ] 验证 Outbox 发布重试、重复发送、消费幂等与服务重启恢复。
- [ ] 约定行情降采样、原始数据保留和测试数据清理策略。
- [ ] 用固定样本验证 AI 草稿准确性、歧义处理、业务校验和超时回退。
- [ ] 冻结首轮基准测试规模、资源上限和报告字段。

## 后续阶段

| 阶段 | 产出 | 进入下一阶段的条件 |
| --- | --- | --- |
| Phase 0 | 最小工程、兼容性记录、关键语义与决策 | 关键依赖可用，未决问题有结论或明确边界 |
| Phase 1 | 工程规范、配置、数据库迁移、接口约定 | 基础启动和测试稳定 |
| Phase 2 | 股票、用户隔离和规则生命周期 | 权限、字段校验和状态操作测试通过 |
| Phase 3 | 模拟行情、回放、顺序与异常策略 | 可重复生成确定性行情并核对结果 |
| Phase 4 | ZSet 索引、匹配与状态机 | 正常、震荡、重复、并发场景正确 |
| Phase 5 | Outbox、MQ 和站内通知 | 重试、重复消费和重启恢复经过演练 |
| Phase 6 | AI 规则草稿和三个基础页面 | 用户确认后走普通规则创建链路 |
| Phase 7 | 压力 / 故障测试、README 和实验报告 | 结果可重复，记录资源与适用边界 |

后续可以增加真实行情源、新规则类型、SSE 或更多通知渠道。当前仅保留清晰接口，不预先实现微服务、多实例协调或复杂运维平台。
