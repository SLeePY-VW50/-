# LabSpace

高校实验室智能预约与排期系统，面向学生、教师、实验室管理员和系统管理员，提供整室/工位预约、自动排期、签到签退、信用申诉、统计分析、微信小程序和 AI 预约助手等功能。

系统由 Spring Boot 后端、Vue Web 端、原生微信小程序和 FastAPI 智能助手组成，使用 MySQL 持久化业务数据，使用 Redis 支持并发控制与会话历史。

## 核心功能

- **多角色权限**：学生、教师、实验室管理员、系统管理员分权访问，支持 JWT 认证和管理员操作。
- **双粒度预约**：支持整间实验室或指定数量工位预约，通过 48 位时间掩码检测跨粒度时段冲突。
- **固定与可调度需求**：固定时空预约提交后生效；可调度需求进入排期任务，由主算法重检并发布结果。
- **智能排期**：内置 FCFS、贪心、遗传三种算法，共享资源快照，支持方案对比和自动发布。
- **使用闭环**：提供二维码签到、公共终端签到、提前签退、自动生命周期处理、信用记录与申诉。
- **统计与管理**：提供工作台指标、使用趋势、预约 CSV 导出，以及用户、资源、规则和终端管理。
- **微信小程序**：支持微信登录与学号绑定、资源查询、预约申请、记录管理、扫码签到和签退。
- **AI 预约助手**：支持流式问答、会话历史、后端只读工具调用和预约草稿确认；未配置模型时不影响普通预约流程。

## 技术栈

| 模块 | 主要技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5、Spring Security、MyBatis-Plus、Flyway |
| Web 端 | Vue 3、TypeScript、Vite、Element Plus、Pinia、ECharts |
| 微信小程序 | 原生小程序、TypeScript、Vitest |
| AI 服务 | Python、FastAPI、LangChain、LangGraph、DeepSeek |
| 数据与基础设施 | MySQL 8、Redis、JWT |

## 项目结构

```text
.
├── ai-service/          # FastAPI 智能助手及单元测试
├── doc/                 # 系统设计说明、UML 图源与生成图
├── docs/                # 功能设计与实施计划
├── miniprogram/         # 微信小程序、配置及测试
├── scripts/             # Windows 本地开发启动与停止脚本
├── server/              # Spring Boot 后端、数据库迁移及测试
├── web/                 # Vue Web 端及测试
└── DESIGN.md            # Web 端视觉与交互规范
```

## 请求关系

| 调用方 | 目标服务 | 说明 |
| --- | --- | --- |
| Web 端 `/api/**` | Spring Boot `8080` | 除 AI 外的业务接口 |
| Web 端 `/api/ai/**` | FastAPI `8000` | 由 Vite 开发服务器代理 |
| 微信小程序 | Spring Boot `8080` | 登录、资源、预约和签到接口 |
| FastAPI | Spring Boot 内部接口 | 携带内部令牌调用白名单只读工具 |
| FastAPI | DeepSeek、Redis | 模型调用与会话历史 |

## 本地运行

### 1. 环境要求

- JDK 21、Apache Maven
- Node.js 24、npm
- Python 3.12
- MySQL 8、Redis
- 可选：微信开发者工具

本地配置默认连接 `127.0.0.1:3307` 的 MySQL 和 `127.0.0.1:6379` 的 Redis。请提前创建 `lab_reservation` 数据库及 `lab_app` 账号；后端启动时由 Flyway 自动执行 `server/src/main/resources/db/migration/` 中的迁移。运行后端测试还需要 `lab_reservation_test` 数据库。

如果数据库端口不同，可通过 Spring 标准环境变量 `SPRING_DATASOURCE_URL` 覆盖本地配置。

### 2. 获取代码

```bash
git clone https://github.com/SLeePY-VW50/-.git lab-reservation
cd lab-reservation
```

### 3. 配置环境变量

PowerShell 示例：

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:LAB_DB_PASSWORD = "你的本地数据库密码"
$env:LAB_REDIS_PASSWORD = ""       # Redis 未设置密码时留空
$env:AI_INTERNAL_TOKEN = "local-ai-internal-token"
$env:DEEPSEEK_API_KEY = ""         # 可选：使用真实 AI 模型时填写
```

| 变量 | 用途 |
| --- | --- |
| `LAB_DB_PASSWORD` | `lab_app` 连接 MySQL 的密码 |
| `LAB_MYSQL_ROOT_PASSWORD` | Windows 准备脚本创建隔离测试库时使用的管理员密码 |
| `LAB_REDIS_PASSWORD` | Redis 密码；AI 服务可通过 `REDIS_PASSWORD` 单独配置 |
| `AI_INTERNAL_TOKEN` | AI 服务调用后端内部接口的共享令牌 |
| `DEEPSEEK_API_KEY` | DeepSeek API Key，不配置时普通预约仍可使用 |
| `WECHAT_APP_ID` / `WECHAT_APP_SECRET` | 接入真实微信登录时配置 |
| `WECHAT_CHECKIN_QR_SECRET` | 签到二维码内容签名密钥 |

不要把真实密码、API Key 或生产令牌提交到仓库。`local` 配置中的令牌和账号仅用于本机开发，生产部署必须使用独立的高强度密钥。

### 4. 启动后端

```powershell
mvn -f server/pom.xml spring-boot:run
```

后端地址：`http://127.0.0.1:8080`。

### 5. 启动 AI 服务

在新的 PowerShell 终端中设置所需环境变量，然后执行：

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

健康检查：`http://127.0.0.1:8000/api/ai/health`。

### 6. 启动 Web 端

```powershell
cd web
npm ci
npm run dev -- --host 127.0.0.1 --port 5173
```

- Web 端：`http://127.0.0.1:5173`
- 独立签到终端：`http://127.0.0.1:5173/terminal`

### 7. 运行微信小程序

```powershell
cd miniprogram
npm ci
npm run typecheck
```

使用微信开发者工具导入 `miniprogram/` 目录。默认接口地址位于 `miniprogram/miniprogram/config/index.ts`，指向 `http://127.0.0.1:8080`。

`project.config.json` 使用 `touristappid`。接入真实微信登录时，请配置自己的小程序 AppID 和后端微信环境变量；真机调试还需将接口地址改为电脑可访问的地址，并遵守微信合法域名要求。

## Windows 开发脚本

仓库提供一键准备、启动和停止脚本：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\bootstrap-dev.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-dev.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-dev.ps1
```

脚本会启动后端 `8080`、AI 服务 `8000` 和 Web 端 `5173`，日志及 PID 写入 `.run/`。

**注意：当前脚本保留了原开发机器的便携环境路径 `D:\lab-reservation`，包括 MySQL 启动脚本和 Maven 可执行文件路径。在其他电脑上使用时，请先修改 `scripts/bootstrap-dev.ps1` 与 `scripts/start-dev.ps1` 中的路径，或按上一节手动启动各模块。**

## 本地演示账号

仅在 `local` profile 下创建：

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 学生 | `20240001` | `Lab@2026` |
| 教师 | `T1001` | `Lab@2026` |
| 实验室管理员 | `L1001` | `Lab@2026` |
| 系统管理员 | `A1001` | `Lab@2026` |

签到终端演示编码为 `LAB-A-TERM`，演示秘密为 `Terminal@2026`。这些凭据不会由生产 profile 自动创建，请勿用于生产环境。

## 测试与构建

后端集成测试需要可用的 MySQL 测试库、Redis 及相应环境变量：

```powershell
mvn -f server/pom.xml clean test
```

Web 端：

```powershell
npm --prefix web test
npm --prefix web run build
```

微信小程序：

```powershell
npm --prefix miniprogram test
npm --prefix miniprogram run typecheck
```

AI 服务：

```powershell
cd ai-service
.\.venv\Scripts\python.exe -m unittest discover -s tests -t . -v
```

后端生产构建：

```powershell
mvn -f server/pom.xml clean package
```

构建产物位于 `server/target/lab-reservation-server-0.0.1-SNAPSHOT.jar`。

## 文档索引

- [系统分析与设计说明书](doc/系统分析与设计说明书.md)
- [工位级预约细化设计说明](doc/工位级预约细化设计说明.md)
- [UML 图集说明](doc/UML图集说明.md)
- [系统设计架构现状](doc/系统设计-架构现状.md)（缺口见 [系统设计-缺口清单](doc/系统设计-缺口清单.md)）
- [Web 端设计规范](DESIGN.md)
- [功能设计与实施计划](docs/superpowers/)

## 生产部署注意事项

- 使用 `prod` profile，并显式配置 `LAB_DB_URL`、`LAB_DB_USERNAME`、`LAB_DB_PASSWORD`、`LAB_JWT_SECRET` 和 `AI_INTERNAL_TOKEN`。
- 为微信、Redis、签到二维码和 AI 模型配置独立的生产凭据。
- 关闭或限制开发演示账号与开发登录方式，启用 HTTPS 和安全 Cookie。
- 不在日志、截图、配置文件或 README 中记录真实秘密。
