# PricePulse 本地启动与配置

本页供新环境部署参考；当前运行实例不得为截图或性能实验重启。性能实验使用独立 Testcontainers，不执行这里的启动步骤。

环境：JDK 21+、Node.js 22.12+、npm、Docker Compose。编译目标 Java 21，本机基准实际使用 Java 25。

```bash
git clone https://github.com/originalEzra/PricePulse.git
cd PricePulse
docker compose up -d
# 仅在没有私人 local 配置的新环境复制，勿覆盖已有配置。
cp docs/examples/application-local.example.yml src/main/resources/application-local.yml
export JWT_SECRET="$(openssl rand -hex 32)"
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

模板使用 Compose 公开的本地开发数据库默认账号／密码；若自定义 `DB_PASSWORD`，同步设置 `SPRING_DATASOURCE_PASSWORD`。JWT 至少 32 字节，保存在自己的环境。私人 `application-local.yml`、`.local/`、`.run/`、`.env.local` 均忽略 Git。

新库应用 V1–V10 和本地演示数据。已有库必须先备份、核对迁移并停止旧接入，不能并开同账户实例。默认 `standalone` 仅用于独立启动检查，完整业务使用 `local`。后端默认 18080；本机既有实例为 18081。

另开终端启动前端：

```bash
cd frontend
npm ci
npm run dev
```

访问 <http://127.0.0.1:5173>。若后端为 18081，在私人 `frontend/.env.local` 设置 `PRICEPULSE_BACKEND_URL=http://127.0.0.1:18081`。Vite 代理 `/api`，前后端进程均须保持运行，Compose 只管理中间件。

没有供应商数据时明确显示无数据；真实起点与模拟时钟依赖缓存的历史／交易日历，不自动生成替代价格。IDEA Application 主类为 `com.ezra.pricepulse.PricePulseApplication`、模块 `price-pulse`、工作目录为仓库根目录，参数 `--spring.profiles.active=local --server.port=18081`。最终本机的实际启动方式和 PID 见交付记录，不能假定旧工具窗口仍管理当前进程。

### 启用真实接入

仅在后端私人环境或 `.local/alpaca.env` 配置，模板按 properties 格式导入后者：

```dotenv
APCA_API_KEY_ID=<your-key-id>
APCA_API_SECRET_KEY=<your-secret>
APCA_API_ENV=paper
PRICEPULSE_ALPACA_ENABLED=true
PRICEPULSE_ALPACA_BUSINESS_WRITES_ENABLED=true
PRICEPULSE_MONITORING_ENABLED=true
PRICEPULSE_DYNAMIC_SUBSCRIPTIONS_ENABLED=true
PRICEPULSE_ADOPT_LEGACY_HOLDS=true
PRICEPULSE_MESSAGING_ENABLED=true
```

这些开关会启用真实订阅、规则处理与通知，须在 V10、预算和原规则已核对后使用，Key 不放前端。兼容持有仅在当前连接实际确认且不存在孤立有效规则时接管；不要用第二条 IEX 验证额度。

AI 可选且默认关闭，通过 `PRICEPULSE_AI_ENABLED`、`PRICEPULSE_AI_API_KEY`、`PRICEPULSE_AI_BASE_URL`、`PRICEPULSE_AI_MODEL` 配置支持结构化输出的兼容供应商。模型仅产出草稿，手动规则不依赖模型；未评估自然语言准确率。
