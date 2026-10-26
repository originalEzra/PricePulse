# PricePulse

> 一个以股票价格提醒为业务载体，重点展示规则索引、状态一致性、可靠异步处理和可重复测试的 Java 后端项目。

PricePulse 允许用户为股票设置“上破某价格”或“下破某价格”的提醒规则。系统持续接收模拟行情，在价格真正穿越阈值时生成触发记录，并异步创建站内通知。

项目不涉及真实交易、资金管理、自动买卖或投资建议。它的重点不是做一个完整券商 App，而是把一个看似简单的提醒功能，落实成正确、可靠且可以量化验证的工程系统。

## 当前进度

**Phase 6B：AI 自然语言规则草稿与用户确认流程已完成。** 下一步进入压测与故障实验。

- [x] Java 21 + Spring Boot 最小工程
- [x] MySQL、Redis、RocketMQ 本地环境
- [x] Flyway、MyBatis、Actuator 基础配置
- [x] Testcontainers 基础设施集成测试
- [x] RocketMQ 真实消息收发测试
- [x] Spring AI 离线结构化输出测试
- [x] Groq 免费 API 真实结构化输出测试
- [x] 7 张核心业务表的 Flyway 迁移
- [x] 统一 API 成功/错误响应与异常处理
- [x] 注册、BCrypt 密码、登录签发与验证 access JWT
- [x] 股票查询、用户数据隔离和提醒规则生命周期
- [x] 模拟行情、回放和异常行情处理
- [x] Redis ZSet 规则索引、增量同步与自动重建
- [x] 穿越匹配、重复提醒状态机、触发历史与 PENDING Outbox
- [x] Outbox 发布、RocketMQ 消费幂等与站内通知
- [x] Vue 3 Web 页面与真实后端联调
- [x] AI 自然语言规则草稿与用户确认
- [ ] 压力测试、故障实验与结果报告

> 下文标注“计划实现”的部分属于后续阶段；当前可以注册登录、管理提醒规则、接入或模拟行情、触发规则，并通过 RocketMQ 异步生成和查询站内通知。

## 用户能够做什么

### 0. 注册和登录

用户可以使用邮箱和密码注册、登录并取得 access JWT。后续规则和通知请求从 JWT 中取得当前用户身份，不能通过请求参数冒充其他用户。

项目采用单 access token：退出时由客户端删除 Token，服务端不提供即时撤销，Token 会在两小时后自然过期。这个限制是明确设计边界，不使用 refresh token 或 Redis 黑名单扩张普通登录模块。

### 1. 管理股票提醒

用户选择一只股票，创建如下规则：

- 价格上破：价格从目标值下方上涨到目标值或以上。
- 价格下破：价格从目标值上方下跌到目标值或以下。
- 一次性提醒：成功触发后自动结束。
- 重复提醒：触发后进入冷却状态，价格离开阈值区域后才能重新激活。
- 暂停、启用、修改和删除规则。

**当前实现：**

- Spring MVC 提供 REST API。
- MyBatis 负责明确可控的 SQL 查询和状态更新。
- MySQL 保存规则、状态和版本，是业务事实来源。
- Jakarta Validation 校验股票代码、方向和阈值等输入。
- 金额使用 `BigDecimal`，最多 4 位小数，不做静默舍入。
- 修改、暂停、恢复和删除携带版本号，通过 MySQL 乐观锁阻止并发覆盖。

规则已接入行情：一次性触发后进入 `COMPLETED`，重复提醒进入 `COOLDOWN`，到达重新激活价后才返回 `ACTIVE`。手动恢复暂停规则会开启新的监控周期，仍需后续价格穿越才能触发。

### 2. 接收和回放模拟行情

系统可以生成多只虚拟股票的价格变化，用来模拟：

- 正常波动
- 快速上涨或下跌
- 阈值附近反复震荡
- 重复行情
- 过期或乱序行情
- 历史行情回放

**当前实现：**

- 行情生成器与回放器都转换为统一的 `QuoteEvent`。
- 实时行情校验股票、正数价格、最多 4 位小数、事件时间和全局唯一 `eventKey`。
- 同一股票通过 MySQL 行锁串行推进最新行情水位，只有事件时间严格更新的行情才能改价。
- 实时行情超过 2 分钟判为过期；超过服务器时间 30 秒判为未来数据；阈值均可配置。
- 历史回放绕过实时新鲜度检查，但仍执行去重和乱序保护。
- 模拟器支持正常、上涨、下跌和震荡模式；固定 `runKey + seed + 初始价 + 开始时间` 可复现实验。
- MySQL 保存接受与丢弃的行情事实及原因，便于追踪和对账。
- 首条被接受的行情只建立基线；重复或丢弃行情不会重新匹配规则。

### 3. 快速匹配可能触发的规则

一只股票可能关联大量提醒规则。系统不能在每次价格变化时全表扫描所有用户规则。

例如股票价格从 `95` 上涨到 `101`，只需要找到阈值位于 `(95, 101]` 区间内的“价格上破”规则。

**当前实现：**

- 每只股票使用一个 Redis ZSet，以四个分值区间区分上破、下破和双向重新激活。
- ZSet 的 `score` 保存经过统一缩放的价格及区间偏移，`member` 保存规则 ID。
- 根据旧价格和新价格执行区间查询，只得到候选规则。
- Redis 只负责加速候选查询，不直接决定规则已经成功触发。
- MySQL 条件更新和状态机完成最终业务校验，防止缓存不一致造成错误通知。
- 数据库 UUID 版本与 Redis 原子校验，缺失或不一致时自动从 MySQL 重建；正常规则变更仅增量同步。

### 4. 防止价格震荡和重复事件产生重复提醒

如果价格不断在阈值附近上下波动，或者同一行情被重复发送，系统不能无限生成相同通知。

**当前实现：**

- 用明确的规则状态机描述“生效、已触发、冷却、重新激活、暂停、结束”等状态。
- 用 MySQL 条件更新或版本字段保证只有符合前置状态的更新才能成功。
- 触发记录设置业务唯一约束，阻止同一轮触发被重复写入。
- 重复提醒通过重新激活区间控制，而不是简单按时间反复发送。
- 重复、乱序和并发事件必须由自动化测试覆盖。

行情、规则管理共用股票行锁确定先后顺序。触发状态、不可变规则快照和 PENDING Outbox 在同一事务提交。Redis 读失败时返回 503，行情事务回滚，调用方可使用原事件键重试。

### 5. 在通知洪峰下可靠生成站内通知

市场剧烈波动时，可能有大量规则在短时间内同时触发。规则匹配线程不应同步等待每条通知处理完成。

**当前实现：**

1. 已实现：在同一个 MySQL 事务中更新规则状态、写入触发记录并写入 PENDING Outbox。
2. 发布器通过数据库行锁和租约领取任务，把普通消息发送到 RocketMQ。
3. 发送失败指数退避；发布器崩溃留下的过期租约会被后续扫描回收。
4. RocketMQ 缓冲集中产生的通知任务，消费失败最多重投 3 次，之后进入死信队列。
5. 消费者核对持久化触发事实，并根据触发快照生成通知。
6. `notifications.trigger_id` 唯一约束吸收重复投递，保证一个触发只产生一条通知。

发布器最多自动领取同一任务 8 次。达到上限后保留 `FAILED` 记录供排查；当前未实现 DLQ 管理页和人工重放接口。

这里选择 RocketMQ 不是因为普通通知必须使用 MQ，而是为了验证“触发洪峰、任务缓冲、失败重试、重复消费和服务恢复”这一组工程问题。

### 6. 查看通知和触发历史

用户可以查看：

- 哪条规则被触发
- 触发时的股票价格和行情时间
- 通知生成时间
- 通知状态和已读状态
- 已结束、已暂停或仍在运行的规则

**当前实现：**

- MySQL 已保存触发记录和当时的规则快照，可通过 `/api/alert-rules/{id}/triggers` 查询自己的历史，包括已删除规则的历史。
- 用户可以通过 `/api/notifications` 查询自己的最新通知，并按 `UNREAD / READ` 筛选。
- `POST /api/notifications/{id}/read` 幂等标记已读；其他用户看不到也不能修改这条通知。
- 通知展示触发时保存的规则快照，不会被规则后续修改或移股污染。
- MyBatis 实现按用户和状态查询，并使用 `limit` 限制单次返回数量。
- REST API 返回稳定的 DTO，不直接暴露数据库实体。
- Vue 3 通知中心已支持查看触发快照、按状态筛选和标记已读。

### 7. 使用自然语言创建提醒草稿

用户不必逐项填写字段，可以输入：

> 当 DEMO001 上涨到 100 时提醒我，只提醒一次。

模型只负责把文字转换成结构化草稿。用户确认前不会真正创建规则。

**当前实现：**

- Spring AI 连接 OpenAI 兼容接口，开发阶段可使用 Groq 免费层。
- JSON Schema 约束模型输出结构，价格使用字符串承载后再由 Java 精确解析。
- 后端按股票代码查询真实股票，并再次执行金额精度、方向、重复提醒重新激活价等业务校验。
- 有歧义或缺少字段时返回一个澄清问题；模型超时、输出损坏或供应商异常统一返回可解释的 503。
- Vue 页面先展示结构化草稿，再填入原有规则表单供用户检查和修改。
- 用户最后仍通过普通规则创建接口保存；AI 接口没有写数据库的能力，不绕过权限和业务规则。
- AI 默认关闭且不影响项目启动；只有显式启用时才要求 API Key。

Phase 0 已通过真实供应商的结构化输出连通性验证。Phase 6B 通过离线模型接口测试和本地模拟模型的浏览器联调验证业务链路；本阶段没有重新调用云端模型，当前草稿 Schema 的真实模型效果仍需配置 API Key 后实测。

### 8. 模拟、压测和故障验证

项目不仅展示功能，还要能够证明设计是否有效。

**计划实现：**

- JUnit：状态机、边界和异常场景单元测试。
- Testcontainers：使用真实 MySQL 和 Redis 验证迁移及访问行为。
- RocketMQ 集成测试：验证真实 Broker 的消息收发。
- k6 或 JMeter：批量生成行情和并发请求。
- 固定随机种子和测试配置，使实验可以重复。
- 记录吞吐量、候选匹配 P95/P99、积压峰值、消化时间、重复触发数和故障恢复结果。

## 一条完整业务链路

```text
用户自然语言
    ↓
Spring AI 生成结构化草稿
    ↓ 用户确认
MySQL 保存提醒规则 ─────→ Redis ZSet 建立可重建索引
                              ↑
模拟 / 回放行情 → 校验与顺序处理 → 区间查询候选规则
                              ↓
        MySQL 状态更新 + 触发记录 + Outbox（同一事务）
                              ↓
                 RocketMQ → 幂等消费者
                              ↓
                    MySQL 站内通知
                              ↓
                     Vue 3 通知中心
```

## 每项技术具体负责什么

| 技术 | 在项目中的职责 | 为什么使用 |
| --- | --- | --- |
| Java 21 | 核心业务、状态机、事件处理 | 类型安全，适合构建可测试的后端业务模型 |
| Spring Boot / Spring MVC | 应用启动、REST API、配置与依赖管理 | 快速建立统一的 Java Web 工程基础 |
| MyBatis | 规则查询、条件更新、分页与性能敏感 SQL | SQL 行为清晰，方便解释索引和并发更新 |
| MySQL | 规则、状态、触发记录、Outbox、通知 | 保存不可丢失的业务事实和事务边界 |
| Flyway | 数据库版本迁移 | 保证不同环境使用相同表结构 |
| Redis ZSet | 按股票和价格区间查询候选规则 | 避免每条行情全量扫描数据库规则 |
| RocketMQ | 通知任务缓冲、异步解耦与失败重试 | 应对集中触发，并验证可靠异步链路 |
| Spring AI | 自然语言到提醒草稿的结构化转换 | 降低规则填写成本，同时与核心业务隔离 |
| Bean Validation | API 参数及 AI 草稿校验 | 不信任客户端或模型输出 |
| Testcontainers | MySQL、Redis 真实集成测试 | 避免只用 Mock 得出错误兼容性结论 |
| Actuator | 健康状态和基础运行信息 | 为后续监控和故障判断提供入口 |
| Vue 3 + TypeScript | 登录、行情与规则、通知中心、行情模拟页面 | 适合快速构建类型明确的管理界面 |
| Pinia / Vue Router | JWT 会话状态、路由与登录守卫 | 使用 Vue 官方常用配套，减少额外选型 |
| 原生 SVG | 轻量展示近期价格走势 | 当前图表简单，不为一个折线图引入大型依赖 |

## 数据边界

- **MySQL 是业务事实来源**：规则是否有效、是否触发、通知是否生成，以 MySQL 为准。
- **Redis 是可重建加速索引**：Redis 丢失时可以从 MySQL 恢复，不能让缓存成为唯一事实。
- **RocketMQ 负责传递任务**：业务触发先在数据库事务中落地，再通过 Outbox 发布，避免数据库成功但消息丢失。
- **AI 只生成草稿**：模型输出必须经过后端校验和用户确认，不能直接修改业务状态。

当前数据库仍是 7 张业务表。V3 为股票表补充索引 UUID 版本字段，总字段数由 Phase 1 的 71 增为 72；迁移增量执行，不改写已有迁移。

## Web 页面

前端使用 Vue 3 + TypeScript + Vite，页面只读取真实后端 API：

1. **登录与注册**：JWT 会话、路由守卫和 401 自动清理。
2. **行情与提醒管理**：股票价格、轻量 SVG 走势图、规则增删改停。
3. **通知中心**：触发价格、触发原因、时间、筛选和已读状态。
4. **模拟行情管理**：选择行情模式、运行可复现实验、查看基础指标。

## 工程结构

当前目录主要内容：

```text
price-pulse/
├── compose.yaml                 # MySQL、Redis、RocketMQ 本地环境
├── deploy/                      # RocketMQ 等组件配置
├── docs/                        # 各阶段决策、实现语义与验收结果
├── frontend/                    # Vue 3 + TypeScript Web 页面
├── src/main/java/               # Spring Boot 应用代码
├── src/main/resources/
│   ├── application.yml          # 公共配置
│   ├── application-standalone.yml
│   ├── application-local.yml
│   └── db/migration/            # Flyway 迁移脚本
├── src/test/java/               # 离线及真实依赖测试
├── pom.xml
└── README.md
```

后续业务代码采用模块化单体，按照 `user`、`stock`、`market`、`alert`、`matching`、`notification`、`assistant` 等业务职责组织，而不是按 Controller、Service、Mapper 建立全局大目录。

## 本地启动

### 1. 默认独立模式

环境要求：安装 JDK 21，并正确配置 `JAVA_HOME`。Maven 使用仓库自带的 Wrapper，无须单独安装。本地完整依赖与集成测试还需要 Docker。

不连接外部中间件，用于确认应用能够编译和启动：

```bash
git clone https://github.com/originalEzra/PricePulse.git
cd PricePulse
java -version
./mvnw test
./mvnw spring-boot:run
```

启动后访问：

- <http://127.0.0.1:18080/api/system/info>
- <http://127.0.0.1:18080/actuator/health>

### 2. 本地完整依赖

```bash
docker compose -f compose.yaml up -d
docker compose -f compose.yaml ps
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

本地端口：

| 服务 | 端口 |
| --- | --- |
| Spring Boot | `18080` |
| MySQL | `13306` |
| Redis | `16379` |
| RocketMQ NameServer | `19876` |
| RocketMQ Broker | `10911` / `10909` |

另开一个终端启动前端：

```bash
cd frontend
npm install
npm run dev
```

浏览器访问 <http://127.0.0.1:5173>。Vite 把 `/api` 代理到 Spring Boot `18080`，本地开发不需要扩大后端 CORS 配置。

如需启用自然语言规则草稿，在启动后端前设置：

```bash
export PRICEPULSE_AI_ENABLED=true
read -s "PRICEPULSE_AI_API_KEY?请输入 Groq API Key: "
echo
export PRICEPULSE_AI_API_KEY
export PRICEPULSE_AI_BASE_URL="https://api.groq.com/openai"
export PRICEPULSE_AI_MODEL="openai/gpt-oss-20b"

./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

未设置 `PRICEPULSE_AI_ENABLED=true` 时，普通业务完整可用；AI 草稿接口会明确返回 503，不会在运行中悄悄尝试外部调用。

前端生产构建与类型检查：

```bash
cd frontend
npm run type-check
npm run build
```

前端将 JWT 会话保存在 `localStorage`。这是本地演示项目的明确取舍；真实互联网产品仍需结合威胁模型评估 HttpOnly Cookie、CSRF 和刷新机制。

停止容器并保留数据：

```bash
docker compose -f compose.yaml down
```

## 测试入口

| 命令 | 验证内容 | 是否依赖外部服务 |
| --- | --- | --- |
| `./mvnw test` | 应用启动、AI 草稿校验、模型接口与超时处理 | 否 |
| `./mvnw -Pintegration verify` | 真实 MySQL、Redis 与 Flyway | Docker，由 Testcontainers 管理 |
| `./mvnw -Pmq-it verify` | RocketMQ 基础发送与消费闭环 | 是，需启动 Compose |
| `./mvnw -Pmq-pipeline-it verify` | Outbox 到站内通知的完整真实 MQ 链路 | 是，需启动 Compose |
| `./mvnw -Pai-live verify` | 云端模型与结构化输出闭环 | 是，需要供应商 API Key |

真实 AI 测试推荐使用 Groq 免费层：

```bash
read -s "PRICEPULSE_AI_API_KEY?请输入 Groq API Key: "
echo
export PRICEPULSE_AI_API_KEY
export PRICEPULSE_AI_BASE_URL="https://api.groq.com/openai"
export PRICEPULSE_AI_MODEL="openai/gpt-oss-20b"

./mvnw -B -ntp -Pai-live verify
unset PRICEPULSE_AI_API_KEY
```

不要把 API Key 写入源码、README、提交记录或聊天消息。

## 开发阶段

| 阶段 | 主要产出 |
| --- | --- |
| Phase 0（已完成） | 最小工程、依赖兼容性、MySQL/Redis/MQ/AI 真实验证 |
| Phase 1（已完成） | 工程规范、正式数据库迁移、统一接口约定 |
| Phase 2（已完成） | 认证、股票、用户隔离和提醒规则生命周期 |
| Phase 3（已完成） | 模拟行情、历史回放、重复/过期/未来/乱序策略 |
| Phase 4（已完成） | Redis ZSet 索引、区间匹配、规则状态机与触发持久化 |
| Phase 5（已完成） | Outbox、RocketMQ、消费幂等和站内通知 |
| Phase 6A（已完成） | Vue 3 四个基础页面与真实业务联调 |
| Phase 6B（已完成） | AI 自然语言规则草稿、澄清与用户确认流程 |
| Phase 7 | 压力测试、故障实验、性能报告和最终项目文档 |

详细记录：

- [`docs/phase-0.md`](docs/phase-0.md)：依赖选型和真实服务验证。
- [`docs/phase-1.md`](docs/phase-1.md)：数据结构、Outbox 决策与 API 契约。
- [`docs/phase-2.md`](docs/phase-2.md)：认证边界、规则 API、用户隔离与乐观锁。
- [`docs/phase-3.md`](docs/phase-3.md)：行情水位、异常策略、回放、确定性模拟与验收结果。
- [`docs/phase-4.md`](docs/phase-4.md)：价格穿越、冷却重新激活、索引同步重建与故障回滚。
- [`docs/phase-5.md`](docs/phase-5.md)：发布租约、失败退避、真实 MQ 链路、消费幂等与通知 API。
- [`docs/phase-6a.md`](docs/phase-6a.md)：Vue 页面、技术取舍与真实业务闭环。
- [`docs/phase-6b.md`](docs/phase-6b.md)：AI 草稿边界、校验、澄清与失败策略。
