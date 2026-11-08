# LabSpace 四角色模板适配设计

## 目标

以用户提供的 `E:/微软/lab-reservation-web-four-roles/lab-reservation-web` 为实际业务模板，替换当前 Cal.com 通用视觉，保留现有真实业务实现。

## 模板审查结论

- `four-roles-preview.html` 提供四角色视觉与演示数据；数字、姓名、静态预约和健康状态不可复制。
- 模板 Vue 工程使用单角色 `auth.role`，README 明确部分接口与 DTO 尚未验证；当前项目支持多角色并已联调，不覆盖这些实现。
- 可迁移部分为 LabSpace 品牌、蓝色令牌、白色导航、卡片层级、彩色指标图标、角色化首页文案和组件外观。

## 实施范围

- 根目录 `DESIGN.md` 改为 LabSpace 规范，避免旧 Cal 模板继续误导后续开发。
- 更新全局样式、应用导航、工作台、指标卡、登录/终端、时间格、预约表单、图表和局部残留颜色。
- 工作台按 STUDENT、TEACHER、LAB_ADMIN、SYS_ADMIN 显示真实角色文案；指标仍使用现有 `DashboardData`，趋势仍使用 ECharts。
- MetricCard 新增可选图标和 accent 外观参数，不改变已有 value/label/hint/tone/compact 调用。
- 登录页保留真实认证、开发账号回填、错误提示和重定向；终端保留分步验证、倒计时和敏感信息清理。
- 不新增路由、接口、演示指标或静态通知。

## 验证

新增 MetricCard 外观参数的最小组件测试并先确认旧实现不支持，再实现。运行完整 Vitest 与生产构建；通过项目标准脚本恢复本地服务，真实登录后检查工作台、实验室、预约记录和弹窗。检查移动公开页面；不提交业务数据。
