# LabSpace Template Adaptation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This session uses inline execution because no subagents were requested.

**Goal:** 将当前真实 Vue 业务系统适配为用户提供的四角色 LabSpace 模板视觉，不迁移演示数据或占位接口。

**Architecture:** 保留现有 Vue 组件、路由、Pinia、API 与 ECharts。通过全局令牌和局部 SFC 样式迁移视觉；MetricCard 以可选 Component 图标和 accent 参数复用模板外观。

**Tech Stack:** Vue 3、TypeScript、Element Plus、ECharts、Vite、Vitest、LabSpace DESIGN.md。

---

### Task 1: MetricCard 图标能力（测试先行）

**Files:**
- Create: `web/tests/MetricCard.spec.ts`
- Modify: `web/src/components/MetricCard.vue`

- [ ] **Step 1: 写失败测试**

```ts
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { OfficeBuilding } from '@element-plus/icons-vue'
import MetricCard from '../src/components/MetricCard.vue'

describe('MetricCard', () => {
  it('显示模板图标与accent外观', () => {
    const wrapper = mount(MetricCard, {
      props: { label: '涉及实验室', value: 3, icon: OfficeBuilding, accent: 'purple' },
      global: { stubs: { 'el-icon': { template: '<span><slot /></span>' } } },
    })
    expect(wrapper.find('.metric-card__icon.purple').exists()).toBe(true)
    expect(wrapper.text()).toContain('涉及实验室')
    expect(wrapper.text()).toContain('3')
  })
})
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `npm test -- tests/MetricCard.spec.ts` in `web`.
Expected: `.metric-card__icon.purple` 不存在，测试因新外观能力缺失而失败。

- [ ] **Step 3: 最小实现并验证 GREEN**

新增 `icon?: Component` 与 `accent?: 'blue' | 'purple' | 'green' | 'orange'`，默认图标为 `DataLine`，图标元素：

```vue
<span class="metric-card__icon" :class="accent || 'blue'"><el-icon><component :is="icon || DataLine" /></el-icon></span>
```

保留原 props 和值展示。Run: `npm test -- tests/MetricCard.spec.ts`；预期通过。

### Task 2: 全局主题、导航与品牌

**Files:**
- Modify: `web/src/styles.css`
- Modify: `web/src/layouts/AppLayout.vue`
- Modify: `web/index.html`

- [ ] **Step 1: 应用模板令牌**

使用 `#3867e8` primary、`#2f56c4` hover、`#eaf0ff` 软色、`#18243a` ink、`#f4f7fb` 画布、`#e8edf4` 边线和 `0 8px 28px rgb(32 55 96 / 6%)` 阴影。按钮与输入保持 40px，卡片 14px，标签 pill。

- [ ] **Step 2: 迁移 LabSpace 导航**

品牌显示蓝色渐变 `L`、`LabSpace` 和“高校实验室预约与排期平台”；顶栏 72px，当前导航浅蓝，头像蓝色圆形。保留多角色过滤、折叠、移动抽屉、退出和 ARIA。

- [ ] **Step 3: 同步 favicon**

浏览器主题色使用 `#f4f7fb`，favicon 使用 `#3867e8`。

### Task 3: 工作台、登录与业务组件

**Files:**
- Modify: `web/src/views/DashboardView.vue`
- Modify: `web/src/views/LoginView.vue`
- Modify: `web/src/views/TerminalView.vue`
- Modify: `web/src/components/SlotGrid.vue`
- Modify: `web/src/components/ReservationForm.vue`
- Modify: `web/src/components/UsageHeatmap.vue`
- Modify: `web/src/views/StatisticsView.vue`
- Modify: `web/src/views/LabsView.vue`
- Modify: `web/src/views/AssistantView.vue`

- [ ] **Step 1: 角色化真实工作台**

使用计算属性按 SYS_ADMIN、LAB_ADMIN、TEACHER、STUDENT 优先级返回模板 eyebrow、标题和说明。保留 `DashboardData`、真实指标、趋势、快捷入口与日期；指标传入 OfficeBuilding、Calendar、Clock、Monitor 等现有 Element Plus 图标，不使用模板演示数字。

- [ ] **Step 2: 迁移公开页面**

登录页采用深蓝 LabSpace 产品区、白色表单区和蓝色预约界面碎片；保留真实登录和演示账号密码回填。终端采用浅灰蓝画布、白色卡片和蓝色主操作，不改安全流程。

- [ ] **Step 3: 同步业务组件与图表**

选中时间格和主图表使用 `#3867e8`，成功/警告/错误使用模板语义色；热力图采用 `#fff → #eaf0ff → #9db5ef → #3867e8`。移除旧 Cal 近黑主题残留，保持所有交互与 data-testid。

### Task 4: 验证与清理

**Files:**
- Verify: `web/tests/**/*.spec.ts`

- [ ] **Step 1: 完整自动化**

Run: `npm test && npm run build` in `web`.
Expected: 6 个测试文件、12 项测试通过，类型检查与生产构建成功。

- [ ] **Step 2: 真实浏览器验收**

通过项目标准 `scripts/start-dev.ps1` 恢复服务。真实登录后检查四角色文案中的学生路径、真实指标与趋势、实验室时间格、预约记录和弹窗；检查 390px 公开页面，不提交预约。

- [ ] **Step 3: 清理**

停止仅用于查看模板的 5174 静态服务器；保留当前项目预览服务。删除本次运行产生且不需要的临时文件，不触碰用户模板目录。

## 自审

- 设计规范、视觉令牌、导航、角色化工作台、公开页面、组件与验证均覆盖。
- 新 props 类型为 Vue `Component`，accent 与既有 tone 分离，旧调用保持有效。
- 不执行 Git 提交或合并，不修改用户模板目录。
