# UML 图集（面向对象方法结合生命周期法）

本目录按**面向对象方法结合生命周期法**的实施顺序组织，图为论文正文可用的插图。每张图同时提供 `.puml`（源文件，可改）与 `.png` / `.svg`（成品，`.svg` 适合清晰排版）。

## 一、图清单与论文对应位置

| # | 生命周期阶段 | 图名 | 源文件 | 建议论文位置 |
|---|---|---|---|---|
| 1 | 可行性研究与计划 | 图 2-1 系统功能模块结构图 | `uml/feasibility/02_function_modules.puml` | 第 2 章需求分析 → 功能需求 |
| 2 | 系统分析（用例分析） | 图 3-1 系统用例图 | `uml/system-analysis/01_use_case.puml` | 第 3 章系统分析 → 用例分析 |
| 3 | 系统分析（对象·属性·方法） | 图 3-2 系统概念类图 | `uml/system-analysis/03_concept_class.puml` | 第 3 章系统分析 → 领域对象分析 |
| 4 | 系统设计（类设计） | 图 4-1 系统设计类图 | `uml/oo-design/04_design_class.puml` | 第 4 章系统设计 → §4.1 静态结构 |
| 5 | 系统设计（动态模型） | 图 4-2 提交预约顺序图 | `uml/oo-design/05_sequence_reservation.puml` | 第 4 章系统设计 → §4.2 动态模型 |
| 6 | 系统设计（动态模型） | 图 4-3 批量排期顺序图 | `uml/oo-design/06_sequence_scheduling.puml` | 第 4 章系统设计 → §4.2 动态模型 |
| 7 | 系统设计（状态模型） | 图 4-4 预约单状态图 | `uml/oo-design/07_state_reservation.puml` | 第 4 章系统设计 → §4.3 状态模型 |
| 8 | 系统设计（动态模型） | 图 4-5 智能排期活动图 | `uml/oo-design/08_activity_scheduling.puml` | 第 4 章系统设计 → §4.2 动态模型 |
| 9 | 系统设计（数据设计） | 图 4-6 数据库 E-R 图 | `uml/database/09_er.puml` | 第 4 章系统设计 → §4.5 数据设计 |
| 10 | 系统设计（物理设计） | 图 4-7 系统部署图 | `uml/oo-design/10_deployment.puml` | 第 4 章系统设计 → §4.10 物理设计 |
| 11 | 业务流程分析（总览） | 图 4-8 系统业务总览 | `uml/business-process/11_bp_overview.puml` | 第 4 章系统设计 → §4.4 业务流程 |
| 12 | 业务流程分析（预约） | 图 4-9 学生预约提交流程图 | `uml/business-process/12_bp_reservation_apply.puml` | 第 4 章系统设计 → §4.4 业务流程 |
| 13 | 业务流程分析（只读查看） | 图 4-10 实验室使用情况查看流程图 | `uml/business-process/13_bp_usage_monitor.puml` | 第 4 章系统设计 → §4.4 业务流程 |
| 14 | 业务流程分析（使用与违规） | 图 4-11 使用签到、签退与违规处理流程图 | `uml/business-process/14_bp_checkin_violation.puml` | 第 4 章系统设计 → §4.4 业务流程 |
| 15 | 业务流程分析（批量预约） | 图 4-12 任课教师批量预约流程图 | `uml/business-process/15_bp_teacher_batch.puml` | 第 4 章系统设计 → §4.4 业务流程 |
| 16 | 业务流程分析（排期执行） | 图 4-13 智能排期执行流程图 | `uml/business-process/16_bp_scheduling.puml` | 第 4 章系统设计 → §4.4 业务流程 |
| 17 | 系统设计（智能助手） | 图 4-14 智能预约助手顺序图 | `uml/oo-design/17_sequence_ai_assistant.puml` | 第 4 章系统设计 → §4.2 动态模型 / §4.9 智能助手设计 |

> 表内 `#` 是图集登记序号，与文件名前缀 01–17 对应；“图 2-1 / 3-1 / 4-x”是论文插图编号。业务流程图按图 4-8 至图 4-13 连续编号，智能助手顺序图为图 4-14。

**当前设计基线**：

- 学生可使用微信小程序完成查询、预约、取消、扫码签到、签退、信用、申诉和智能问答；实验室电脑提供学号+密码的签到专用认证。
- 固定预约在事务成功后直接进入已通过，并同时具有完整正式座位分配和位图占用。
- 可调度需求由定时任务处理，主算法合格方案自动重检发布，其他算法只用于实验对照。
- FastAPI + LangChain 作为独立智能助手服务，通过 Spring Boot 白名单只读接口获取数据，预约草稿由用户确认后进入原预约事务。
- 实验室管理员只读；系统管理员负责用户权限、资源规则、签到终端、违规处理与申诉核定。
- 数据模型为 23 张表，用例模型为 20 个用例、6 个用例包、5 类参与者；图集共 17 张图。
- 算法对比统一五项指标，可靠性指标单列；遗传算法使用锦标赛选择。

## 二、生命周期方法与本图集的对应关系

| 生命周期阶段 | 本图集产出 |
|---|---|
| 可行性分析、用例分析 | 图 2-1 功能模块结构图、图 3-1 用例图 |
| 面向对象系统分析 | 图 3-2 概念类图（对象、属性、方法） |
| 系统设计：类图 | 图 4-1 四层设计类图及独立 AI 服务类 |
| 系统设计：状态图 | 图 4-4 预约单状态图 |
| 系统设计：顺序图 | 图 4-2 提交预约顺序图、图 4-3 批量排期顺序图、图 4-14 智能预约助手顺序图 |
| 系统设计：活动、数据与部署 | 图 4-5 活动图、图 4-6 E-R 图、图 4-7 部署图 |
| 业务流程分析 | 图 4-8 至图 4-13，覆盖总览、预约、只读查看、使用违规、批量与自动排期 |

## 三、图中承载的核心设计

1. **固定 48 位掩码**：每实验室或工位每天一个掩码，单次位运算为 O(1)；全工位扫描为 O(m)，不以一次 SQL 查询替代复杂度分析。见图 3-1、图 3-2、图 4-2、图 4-6。
2. **双粒度双向互斥**：工位申请先查整室再查工位；整室申请检查整室与全部工位掩码并集，两种预约次序均不会重复占用。见图 4-2、图 4-9、图 4-6。
3. **固定预约直接生效**：已通过预约、正式座位分配、位图和初始日志在同一事务提交，任何部分失败都整体回滚。见图 4-2、图 4-4、图 4-9。
4. **固定与可调度需求分流**：FIXED 提交时生效；SCHEDULABLE 提交后进入待排期，主方案发布时生效。见图 4-4、图 4-8、图 4-13。
5. **主方案自动发布**：三套算法各自任务共享快照，只有主任务合格方案自动重检并占用资源；对照任务只保存实验数据。见图 4-3、图 4-5、图 4-13。
6. **微信小程序与学号绑定**：后端通过临时登录凭证获得 openid，再验证学号密码并建立双向唯一绑定，敏感凭据不下发且不写日志。见图 3-1、图 4-1、图 4-6、图 4-7。
7. **双渠道统一签到**：小程序扫码和实验室电脑学号密码只负责采集凭证，后端统一校验身份、本人预约、实验室和签到时间窗。见图 4-4、图 4-8、图 4-11。
8. **公共终端安全**：签到电脑与实验室绑定，使用密码哈希、失败限次、短期选择凭证和表单清理，不保留完整个人中心会话。见图 4-7、图 4-11。
9. **实验室管理员只读**：页面只提供筛选和查看，后端同步拒绝写接口；资源规则与申诉核定由系统管理员负责。见图 3-1、图 4-10。
10. **统一五项评价指标**：分配成功率、平均工位利用率、工位偏好错配率、时间碎片率、两阶段求解耗时由 Evaluator 产出；落座失败率作为可靠性指标单列。见图 4-1、图 4-3、图 4-13。
11. **实验可复现**：任务保存数据集、需求/资源快照、参数、随机种子、重复序号和运行环境；实验表通过唯一任务编号保存一次运行结果。见图 4-3、图 4-6。
12. **批量与信用审计**：教师批次包含独立预约明细，整批成功后直接生效；扣分与恢复均追加信用流水，申诉核定保留完整记录。见图 4-12、图 4-11、图 4-6。
13. **独立智能助手服务**：FastAPI 负责接口、身份校验、SSE 和异常边界，LangChain Agent 只调用白名单只读工具；对话按用户隔离存于 Redis，预约草稿经 Pydantic 校验和用户确认后进入 Spring Boot 原预约事务，AI 不直连业务数据库。见图 4-1、图 4-7、图 4-14。

## 四、如何修改与重新渲染

- **只改文字/字段**：直接编辑对应 `.puml` 文件；共用样式位于 `uml/_style.inc.puml`。
- **重新渲染全部**：在 `E:\yyy\doc` 下，使用当前可用的 Windows PowerShell 执行

  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File .\render-all.ps1 -Vector -Clean
  ```

  若已安装 PowerShell 7，也可执行 `pwsh -File .\render-all.ps1 -Vector -Clean`。默认仅输出 PNG，`-Vector` 同时输出 SVG，`-Clean` 先清理临时测试产物。

- **只重渲染单张**：

  ```powershell
  java -jar E:\yyy\tools\plantuml-full.jar -charset UTF-8 -tpng .\uml\oo-design\04_design_class.puml
  ```

- **只检查语法**：对目标 `.puml` 使用 `-checkonly`，渲染脚本返回后仍应检查实际 PNG/SVG 是否更新。

## 五、渲染环境说明

| 项 | 值 |
|---|---|
| 图形引擎 | PlantUML `E:\yyy\tools\plantuml-full.jar`（v1.2024.8） |
| 运行环境 | JDK 21；Windows PowerShell 5.1 或 PowerShell 7 |
| 布局引擎 | 类图/用例图/E-R 图/部署图用内置布局；顺序图/活动图使用 Smetana，无需安装 Graphviz |
| 字体 | `Microsoft YaHei` |
| 编码 | 源图使用 UTF-8；`render-all.ps1` 保留 UTF-8 BOM 以兼容 Windows PowerShell 5.1；渲染参数 `-charset UTF-8` |

## 六、本机环境核查摘要（2026-10-24）

完整逐项版本与处理建议见 `doc/系统分析与设计说明书.md` §1.2。

- **已验证可用**：JDK/Javac 21、Node 24.15.0、npm 11.12.1、Python 3.12.7、FastAPI/Pydantic/LangChain/LangGraph 及相关包、微信开发者工具、Git、PowerShell。
- **已验证初始化**：FastAPI OpenAPI 可生成；`from langchain.chat_models import init_chat_model` 可返回 `ChatDeepSeek`，`create_agent` 可返回 `CompiledStateGraph`。
- **已安装并验证**：MySQL 8.0.46（独立实例 3307）、Memurai for Redis 8.2（6379）、Nginx 1.28.3（8088）、Maven 3.10.0，均位于 `D:\lab-reservation`；MySQL 读写回滚、Redis 读写删除、Nginx 默认页和启停脚本均通过验证。
- **仍需用户提供**：一个模型 API Key，以及正式发布所需的微信 AppID/AppSecret；这些无法通过安装软件获得。
- **部署边界**：原 MySQL 5.1 未修改；Memurai 仅用于开发测试，生产许可需另行评估；未安装系统服务、未调用真实模型或微信接口、未创建应用工程。

## 七、目录结构

```text
doc/
├─ render-all.ps1
├─ 系统分析与设计说明书.md
├─ 工位级预约细化设计说明.md
├─ UML图集说明.md
└─ uml/
   ├─ _style.inc.puml
   ├─ feasibility/
   │  └─ 02_function_modules.puml / .png / .svg
   ├─ system-analysis/
   │  ├─ 01_use_case.puml / .png / .svg
   │  └─ 03_concept_class.puml / .png / .svg
   ├─ oo-design/
   │  ├─ 04_design_class.puml / .png / .svg
   │  ├─ 05_sequence_reservation.puml / .png / .svg
   │  ├─ 06_sequence_scheduling.puml / .png / .svg
   │  ├─ 07_state_reservation.puml / .png / .svg
   │  ├─ 08_activity_scheduling.puml / .png / .svg
   │  ├─ 10_deployment.puml / .png / .svg
   │  └─ 17_sequence_ai_assistant.puml / .png / .svg
   ├─ business-process/
   │  ├─ 11_bp_overview.puml / .png / .svg
   │  ├─ 12_bp_reservation_apply.puml / .png / .svg
   │  ├─ 13_bp_usage_monitor.puml / .png / .svg
   │  ├─ 14_bp_checkin_violation.puml / .png / .svg
   │  ├─ 15_bp_teacher_batch.puml / .png / .svg
   │  └─ 16_bp_scheduling.puml / .png / .svg
   └─ database/
      └─ 09_er.puml / .png / .svg
```
