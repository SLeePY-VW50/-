# Vue UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This session uses inline execution because no subagents were requested.

**Goal:** 在保留全部业务行为的前提下，将实验室预约 Vue 前端改为明亮、统一、响应式的现代工作台。

**Architecture:** 继续使用 Vue 3 单文件组件与现有全局 `styles.css`。设计变量集中在 `:root`，应用框架、页面和共享组件各自维护局部样式；不新增依赖，不改变 API、路由、Pinia 或表单载荷。

**Tech Stack:** Vue 3.5、TypeScript、Vue Router、Pinia、Element Plus、ECharts、Vite、Vitest。

---

### Task 1: 全局设计变量与共享控件

**Files:**
- Modify: `web/src/styles.css`

- [ ] **Step 1: 建立视觉变量与基础排版**

在 `:root` 中定义并实际使用以下核心变量，字体保持本地系统字体：

```css
:root {
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei UI', sans-serif;
  color: #182230;
  background: #f4f6fa;
  --ink: #182230;
  --paper: #ffffff;
  --wash: #f4f6fa;
  --line: #e4e8f0;
  --green: #178767;
  --primary: #5856d6;
  --primary-dark: #4543b8;
  --primary-soft: #eeedff;
  --amber: #b87518;
  --danger: #cf4d5e;
  --muted: #697586;
  --radius: 16px;
  --shadow: 0 8px 30px rgb(31 42 68 / 5%);
}
```

- [ ] **Step 2: 将现有类映射到统一规范**

保留 `.page`、`.page-header`、`.panel`、`.toolbar`、`.field`、`.button`、`.metrics-grid`、`.data-table`、`.tag`、`.split-layout` 等现有类名和 DOM 契约，仅重写尺寸、边框、圆角、间距、hover/focus、表格表头与 Element Plus 弹窗/分页覆盖；窄屏继续使用现有断点并增加小屏指标单列。

- [ ] **Step 3: 验证全局样式可编译**

Run: `npm run build` in `web`.
Expected: `vue-tsc --noEmit` 和 `vite build` 均成功。

### Task 2: 应用导航框架

**Files:**
- Modify: `web/src/layouts/AppLayout.vue`

- [ ] **Step 1: 保留逻辑并调整品牌与导航结构**

继续使用 `navItems`、`visibleItems`、`sections`、`collapsed`、`isActive` 和 `logout`。品牌改为不依赖图片的几何标识：

```vue
<router-link class="brand" to="/dashboard" aria-label="校园实验室工作台">
  <span class="brand__mark" aria-hidden="true"><i /><i /><i /><i /></span>
  <span class="brand__copy"><strong>校园实验室</strong><small>LABFLOW WORKSPACE</small></span>
</router-link>
```

侧栏底部保留“服务数据实时读取”，顶栏保留终端入口、账号和角色。导航链接增加当前项视觉指示，不新增路由。

- [ ] **Step 2: 实现白色侧栏与响应式顶栏**

桌面网格使用 `248px minmax(0, 1fr)`，折叠为 `80px`；侧栏为白色，导航选中使用 `--primary-soft` 和 `--primary`。`max-width: 900px` 继续使用抽屉与遮罩，保证用户菜单和菜单按钮可点击。

- [ ] **Step 3: 验证权限与路由**

Run: `npm test -- tests/router.spec.ts` in `web`.
Expected: 3 个路由守卫测试全部通过。

### Task 3: 工作台与指标组件

**Files:**
- Modify: `web/src/views/DashboardView.vue`
- Modify: `web/src/components/MetricCard.vue`

- [ ] **Step 1: 重排真实数据层级**

保留 `load`、`renderChart` 和 `quickEntries`。新增计算属性只负责拆分已有指标，不请求新接口：

```ts
const canSeeCredit = auth.roles.includes('STUDENT') || auth.roles.includes('TEACHER')
const canReserve = canSeeCredit
```

模板依次渲染欢迎区、四个核心指标（实验室、今日预约、待排程、使用中）、趋势与快捷入口、辅助指标（计划利用率、实际利用率、未到场率以及按角色显示的信用分）。预约按钮仅在 `canReserve` 时显示并链接 `/labs`。

- [ ] **Step 2: 重做 MetricCard 外观**

保留 props `label`、`value`、`hint`、`tone`，新增可选 `compact?: boolean`；根元素类名：

```vue
<article class="metric-card" :class="[`metric-card--${tone || 'ink'}`, { 'metric-card--compact': compact }]">
```

核心卡片使用图标式语义标记或顶部色条、32px 数值与清晰 hint；辅助卡片减少高度。颜色全部来自全局变量。

- [ ] **Step 3: 更新趋势图视觉但不改数值**

保留 ECharts 数据映射，主色改为 `#5856d6`，未到场线改为 `#e19a35`，坐标与分割使用浅灰，柱形增加顶部圆角。

### Task 4: 登录与签到终端

**Files:**
- Modify: `web/src/views/LoginView.vue`
- Modify: `web/src/views/TerminalView.vue`

- [ ] **Step 1: 重做登录页结构与时间板**

保留 `accounts`、`fill`、`login`、开发环境判断和重定向逻辑。左侧加入纯 CSS 时间板，格子的类只表达装饰状态，不伪装真实数据：

```vue
<div class="schedule-preview" aria-hidden="true">
  <div class="schedule-preview__head"><span>本周预约</span><i /><i /><i /></div>
  <div class="schedule-preview__grid">
    <i v-for="index in 35" :key="index" :class="{ filled: [3, 4, 10, 17, 18, 25, 31].includes(index) }" />
  </div>
  <div class="schedule-preview__axis"><span>08:00</span><span>12:00</span><span>18:00</span></div>
</div>
```

右侧保留账号、密码、错误、登录按钮、演示账号和终端链接；移动端隐藏装饰时间板。

- [ ] **Step 2: 同步签到终端品牌样式**

保留 `post`、`resetSession`、`createSession`、`checkin`、`selectReservation`、倒计时和敏感字段清理。使用新变量重做终端页头、步骤、卡片、成功标记和单选预约列表。

- [ ] **Step 3: 浏览器检查公开页面**

打开 `/login` 与 `/terminal`，验证输入、开发账号填充、工作人员登录链接和终端返回链接；后端不可用时只确认界面显示既有错误，不绕过认证。

### Task 5: 业务组件、图表与全站收尾

**Files:**
- Modify: `web/src/components/SlotGrid.vue`
- Modify: `web/src/components/ReservationForm.vue`
- Modify: `web/src/components/EmptyState.vue`
- Modify: `web/src/views/LabsView.vue`
- Modify: `web/src/views/ReservationsView.vue`
- Modify: `web/src/views/AssistantView.vue`
- Modify: `web/src/views/StatisticsView.vue`
- Modify: `web/src/components/UsageHeatmap.vue`
- Modify: `web/src/views/CreditView.vue`
- Modify: `web/src/views/ScheduleView.vue`
- Modify: `web/src/views/admin/ResourcesView.vue`
- Modify: `web/src/views/admin/UsersView.vue`
- Modify: `web/src/views/admin/RulesView.vue`
- Modify: `web/src/views/admin/AppealsView.vue`

- [ ] **Step 1: 同步局部硬编码颜色与控件**

将局部样式中的旧主色 `#0e756d`、旧墨色 `#17232b`、旧边框 `#dce5e7` 等替换为对应 `var(--green)`、`var(--ink)`、`var(--line)` 或新主色变量；不改变模板条件、事件、字段名称和任何 `data-testid`。

- [ ] **Step 2: 更新时间格与表单状态**

可用格使用白底和绿色容量条，选中格使用靛蓝，占用格使用中性灰，冲突格使用红色。保留 48 格、起止选择、1–8 片限制、键盘方向键和 ARIA。预约表单保留全部校验和提交流程，模式切换改为圆角分段控件。

- [ ] **Step 3: 同步 ECharts 调色板**

统计趋势使用靛蓝与绿色，热力图低值使用 `#f4f6fa`、中值使用 `#aaa8ef`、高值使用 `#5856d6`，不改变比率、tooltip 或每日平均计算。

- [ ] **Step 4: 运行完整自动化验证**

Run: `npm test` in `web`.
Expected: 所有现有测试通过，预约表单的 FIXED/SCHEDULABLE 载荷、24:00 和冲突保留输入测试均通过。

Run: `npm run build` in `web`.
Expected: 类型检查与生产构建成功。

- [ ] **Step 5: 浏览器验收桌面与移动端**

以约 1440px 桌面和 390px 移动视口检查登录、工作台、实验室、预约记录和签到页。后端可用时完成真实登录并打开新建预约弹窗；后端不可用时明确记录限制。检查主导航、移动抽屉、筛选、时间格和弹窗，无页面级横向溢出。

## 自审

- 规范覆盖：设计变量、框架、工作台、登录/终端、共享业务组件、图表、响应式和验证均有对应任务。
- 无占位需求：所有改动均指定文件和具体行为；完整样式以现有类为基础在实现文件中落地，不引入额外抽象。
- 类型一致：`MetricCard` 仅新增布尔 `compact`，原有调用保持有效；模板继续使用已有 `DashboardData` 字段和角色类型。
- 本目录无 Git 元数据且用户未要求提交，因此不执行提交步骤。
