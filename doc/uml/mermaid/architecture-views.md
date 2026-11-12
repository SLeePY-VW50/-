# 架构图 mermaid 源稿（现状）

对照 GitHub main（提交 `2d73a6489babc1ea665feb037c8cdcee16bc1e75`，2026-11-10 23:33 中国标准时间）。已按代码核实。与 `doc/uml/oo-design/10_deployment.current.puml`、`doc/uml/oo-design/05_sequence_reservation.current.puml` 描述同一现状，不含仓库中不存在的 Nginx、独立 `:8081` Worker。不替换原 `10_deployment.puml` 与 `05_sequence_reservation.puml`。本文是 mermaid 源稿，未渲染 png/svg。

---

## 1. 部署视图（当前可运行拓扑）

```mermaid
flowchart LR
  MP[微信小程序] -->|HTTP :8080 /api| SB
  WEB[Vue Web :5173] -->|Vite 代理 /api/**| SB
  WEB -->|Vite 代理 /api/ai/**| AI
  TERM[签到终端页 /terminal] -->|/api/checkin/terminal| SB
  subgraph host [应用主机]
    SB[Spring Boot :8080<br/>业务 API 与进程内定时任务]
    AI[FastAPI :8000<br/>只读助手]
    DB[(MySQL 本地 :3307<br/>lab_reservation)]
    RD[(Redis :6379)]
  end
  AI -->|内部令牌 + Bearer<br/>/api/internal/**| SB
  SB --> DB
  SB --> RD
  AI --> RD
  SB -.可选.-> WX[微信登录接口]
  AI -.可选.-> LLM[DeepSeek API]
```

说明：生产库地址由 `LAB_DB_URL` 注入；上图端口是本地默认值。小程序默认直连 8080，不经过 Vite。图 4-7 主文件仍是目标部署，本图不替代它。

---

## 2. 固定 / 可调度预约时序

```mermaid
sequenceDiagram
  actor User as 学生或教师
  participant Client as Web或小程序
  participant Ctrl as ReservationController
  participant Svc as ReservationService
  participant Tx as ReservationTransactionService
  participant Lock as RedisLockManager
  participant Det as BitmapConflictDetector
  participant DB as MySQL
  participant Redis as Redis

  User->>Client: 提交分配模式、粒度、时段与人数
  Client->>Ctrl: POST /api/reservations（Bearer JWT）
  Ctrl->>Svc: create(userId, request)
  alt SCHEDULABLE
    Svc->>Tx: createSchedulable
    Tx->>DB: 插入 PENDING_SCHEDULE 与初始日志（不占位）
    Tx-->>Ctrl: 待排期
  else FIXED
    Svc->>Lock: acquire resv:lab:{labId}:{date}
    Lock->>Redis: SET NX
    alt 加锁失败
      Svc-->>Ctrl: 高峰请重试
    else 加锁成功
      Svc->>Tx: createFixed
      Tx->>DB: 锁用户与整室日位图，读掩码
      Tx->>Det: 双向冲突检测
      alt 冲突或工位不足
        Tx->>DB: ROLLBACK
        Tx-->>Ctrl: 冲突
      else 通过
        Tx->>DB: 插入 APPROVED、正式座位、位图、日志并提交
        Tx-->>Ctrl: 已通过
      end
      Svc->>Lock: finally 按令牌释放
    end
  end
  Ctrl-->>Client: ApiResponse
  Client-->>User: 展示结果
```

---

## 3. 智能助手（开发期，无 Nginx）

```mermaid
sequenceDiagram
  actor User as 学生或教师
  participant Web as Vue /assistant
  participant AI as FastAPI /api/ai
  participant Auth as AuthGateway
  participant SB as Spring Boot
  participant Redis as Redis
  participant LLM as DeepSeek（可选）

  User->>Web: 输入问题与 threadId
  Web->>AI: POST /api/ai/chat/stream 或 /invoke（Bearer）
  AI->>Auth: 验证业务令牌
  Auth->>SB: GET /api/internal/auth/verify<br/>Authorization + X-Internal-Service-Token
  alt 身份无效
    AI-->>Web: 401 或 403
  else 身份有效
    AI->>Redis: 读取 ai:chat:{userId}:{threadId}
    opt 需要规则、空闲或本人预约
      AI->>SB: 白名单 GET /api/internal/**
    end
    opt 已配置模型密钥
      AI->>LLM: 生成回答或草稿
    end
    alt 模型未配置或失败
      AI-->>Web: 降级，回退普通预约页
    else 输出有效
      AI->>Redis: 追加历史
      AI-->>Web: 回答或 ReservationDraft
      opt 用户确认草稿
        Web->>SB: POST /api/reservations
        SB-->>Web: 业务校验后的预约结果
      end
    end
  end
```

说明：小程序目前没有该调用链的页面。已确认：信用、申诉与 AI 当前仅 Web 已有页面，小程序仍为规划；若论文保留「小程序 invoke」，须加此注，不把规划写成已实现页面。

---

## 4. 数据流（写占用与只读助手）

```mermaid
flowchart TB
  subgraph clients [客户端]
    W[Web]
    M[小程序]
    T[签到终端]
  end
  W -->|预约、签到、管理写接口| SB[Spring Boot :8080]
  M -->|预约、签到| SB
  T -->|终端会话与账号签到| SB
  W -->|AI 问答| AI[FastAPI :8000]
  AI -->|只读内部 API| SB
  SB --> DB[(MySQL)]
  SB --> RD[(Redis 锁与终端会话)]
  AI --> RD2[(Redis 对话历史)]
  AI -.草稿确认后回到写接口.-> W
```
