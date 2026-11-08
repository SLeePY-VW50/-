# Cal.com DESIGN.md Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This session uses inline execution because no subagents were requested.

**Goal:** 使用已安装的 Cal.com DESIGN.md 模板收敛 Vue 前端的视觉系统，同时保留全部业务行为。

**Architecture:** 继续使用 Vue SFC 和全局 CSS。先修改共享变量与基础控件，再调整导航、工作台、登录/终端的局部样式，最后同步图表和少量局部硬编码；不改变脚本数据流。

**Tech Stack:** Vue 3、TypeScript、Element Plus、ECharts、Vite、Vitest、DESIGN.md。

---

### Task 1: 安装核验与全局令牌

**Files:**
- Create: `DESIGN.md`
- Modify: `web/src/styles.css`
- Modify: `web/index.html`

- [ ] **Step 1: 安装模板并确认来源**

将 `_unzipped/awesome-design-md/design-md/cal/DESIGN.md` 原样复制到 `DESIGN.md`，确认标题为 `Cal.com-design-analysis`。仓库没有 `SKILL.md`，不执行 Skills CLI 技能安装。

- [ ] **Step 2: 应用 Cal 基础令牌**

```css
:root {
  --ink: #111111;
  --paper: #ffffff;
  --wash: #ffffff;
  --line: #e5e7eb;
  --green: #10b981;
  --primary: #111111;
  --primary-dark: #242424;
  --primary-soft: #f5f5f5;
  --amber: #f59e0b;
  --danger: #ef4444;
  --muted: #6b7280;
  --radius: 12px;
  --shadow: 0 1px 2px rgb(0 0 0 / 5%);
}
```

正文使用模板允许的本地回退字体，标题 600 字重；按钮和输入框 8px 圆角，内容卡片 12px，产品预览最多 16px。主按钮不添加彩色阴影，页面不使用点阵或紫色氛围背景。

- [ ] **Step 3: 更新浏览器主题色**

将 `meta[name="theme-color"]` 设置为 `#ffffff`，favicon 主色设置为 `#111111`。

### Task 2: 应用框架与核心页面

**Files:**
- Modify: `web/src/layouts/AppLayout.vue`
- Modify: `web/src/views/DashboardView.vue`
- Modify: `web/src/components/MetricCard.vue`
- Modify: `web/src/views/LoginView.vue`
- Modify: `web/src/views/TerminalView.vue`

- [ ] **Step 1: 收敛导航**

顶栏使用 64px 白底；品牌标识、当前导航和主要操作使用近黑，导航选中使用 `#f5f5f5`。头像改为 36px 圆形。保留 `collapsed`、角色过滤、移动抽屉和退出登录逻辑。

- [ ] **Step 2: 收敛工作台与指标**

欢迎区使用浅灰产品卡片，移除紫色光晕。核心指标使用 Cal 的浅灰 feature-card，产品趋势与快捷入口保留白色 product-mockup-card。标题 600 字重，去除卡片漂浮和位移动效。

- [ ] **Step 3: 收敛登录与终端**

登录页采用白色背景、浅灰预约时间板和近黑登录按钮；时间板填充使用 `#111111`、次级格使用 `#d1d5db`。保留真实表单、演示账号、终端分步验证和错误状态。

### Task 3: 共享组件、图表与验证

**Files:**
- Modify: `web/src/components/SlotGrid.vue`
- Modify: `web/src/components/ReservationForm.vue`
- Modify: `web/src/components/EmptyState.vue`
- Modify: `web/src/components/UsageHeatmap.vue`
- Modify: `web/src/views/DashboardView.vue`
- Modify: `web/src/views/StatisticsView.vue`
- Modify: `web/src/views/LabsView.vue`
- Modify: `web/src/views/AssistantView.vue`

- [ ] **Step 1: 同步交互组件**

选中时间格使用近黑，开放状态使用模板 success；分段控件采用浅灰 pill 容器和白色活动项。移除局部紫色硬编码，保留 48 格、键盘操作和测试标识。

- [ ] **Step 2: 同步图表**

工作台趋势主色使用 `#111111`，统计图使用 `#111111` 与 `#10b981`，热力图采用 `#ffffff → #e5e7eb → #9ca3af → #111111`，不改变数值。

- [ ] **Step 3: 完整自动化验证**

Run: `npm test` in `web`.
Expected: 5 个测试文件、11 项测试全部通过。

Run: `npm run build` in `web`.
Expected: 类型检查和生产构建成功。

- [ ] **Step 4: 浏览器验证**

使用真实学生账号检查工作台、实验室和预约记录，选择连续时段并打开预约弹窗；检查登录、终端和移动导航。不提交新数据。记录任何模拟器限制，不将未执行的真机测试声明为通过。

## 自审

- 模板安装、核心令牌、导航、工作台、登录/终端、组件、图表和验证均有对应任务。
- 不新增依赖、路由、接口或业务状态，不改变任何测试期望。
- 无 Git 元数据且用户未要求提交，因此不执行提交、分支或合并步骤。
