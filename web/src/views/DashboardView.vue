<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { echarts, type EChartsOption, type EChartsType } from '../charts'
import { chartAxis, chartLegend, chartPalette, chartSplit, chartTooltip } from '../chartTheme'
import { ArrowRight, Calendar, ChatDotSquare, CircleCheck, Clock, Cpu, DataAnalysis, Medal, Monitor, OfficeBuilding, Tickets } from '@element-plus/icons-vue'
import http, { errorMessage } from '../api/http'
import type { DashboardData, Page, Reservation } from '../types'
import { useAuthStore } from '../stores/auth'
import { formatDateTime, percent, reservationTime, statusLabels, timeOnly, today } from '../utils'
import MetricCard from '../components/MetricCard.vue'
import EmptyState from '../components/EmptyState.vue'

const auth = useAuthStore()
const data = ref<DashboardData | null>(null)
const reservations = ref<Reservation[]>([])
const loading = ref(true)
const error = ref('')
const chartElement = ref<HTMLDivElement>()
let chart: EChartsType | null = null

const isOps = computed(() => auth.roles.includes('LAB_ADMIN') || auth.roles.includes('SYS_ADMIN'))
const isLearner = computed(() => !isOps.value && (auth.roles.includes('STUDENT') || auth.roles.includes('TEACHER')))
const canSeeCredit = auth.roles.includes('STUDENT') || auth.roles.includes('TEACHER')
const isTeacher = auth.roles.includes('TEACHER')

const rolePresentation = computed(() => {
  if (auth.roles.includes('SYS_ADMIN')) return { eyebrow: '系统管理', title: '系统管理总览', description: '统一管理实验室资源、用户权限、开放规则与申诉流程。' }
  if (auth.roles.includes('LAB_ADMIN')) return { eyebrow: '实验室运营', title: '实验室运行总览', description: '关注资源使用、预约执行与自动排程运行情况。' }
  if (auth.roles.includes('TEACHER')) return { eyebrow: '今日实验', title: `你好，${auth.user?.name || '老师'}`, description: '先看今天的实验安排，再处理课程批次与预约。' }
  return { eyebrow: '今日实验', title: `你好，${auth.user?.name || '同学'}`, description: '先确认今天要去哪间实验室，再完成签到与实验。' }
})

const activeStatuses = new Set(['PENDING_SCHEDULE', 'APPROVED', 'IN_USE'])
const upcoming = computed(() => {
  const day = data.value?.date || today()
  return reservations.value
    .filter((item) => activeStatuses.has(item.status) && item.date >= day)
    .sort((a, b) => {
      const left = a.startTime || `${a.date}T${timeOnly(a.windowStart)}`
      const right = b.startTime || `${b.date}T${timeOnly(b.windowStart)}`
      return left.localeCompare(right)
    })
})
const focusReservation = computed(() => upcoming.value[0] || null)
const todayItems = computed(() => {
  const day = data.value?.date || today()
  return upcoming.value.filter((item) => item.date === day)
})

const focusJourney = computed(() => {
  const item = focusReservation.value
  if (!item) {
    return [
      { label: '选择实验室', done: false, current: true },
      { label: '提交预约', done: false, current: false },
      { label: '到场签到', done: false, current: false },
    ]
  }
  const approved = ['APPROVED', 'IN_USE', 'COMPLETED'].includes(item.status) || item.status === 'PENDING_SCHEDULE'
  const checkedIn = Boolean(item.checkinTime) || item.status === 'IN_USE' || item.status === 'COMPLETED'
  const started = item.status === 'IN_USE' || item.status === 'COMPLETED'
  return [
    { label: item.status === 'PENDING_SCHEDULE' ? '等待排程' : '预约已确认', done: approved, current: !checkedIn && item.status !== 'PENDING_SCHEDULE' },
    { label: checkedIn ? '已签到' : '到场签到', done: checkedIn, current: approved && !checkedIn && item.status === 'APPROVED' },
    { label: started ? '实验进行中/完成' : '开始实验', done: started, current: checkedIn && !started },
  ]
})

const quickEntries = computed(() => [
  { path: '/labs', label: '查看实验室', detail: '查询48格时间尺与工位状态', icon: OfficeBuilding, accent: 'pine', roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/reservations', label: '预约记录', detail: '查询详情与正式座位', icon: Calendar, accent: 'ink', roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/assistant', label: 'AI预约助手', detail: '先形成草稿，再核对提交', icon: ChatDotSquare, accent: 'pine', roles: ['STUDENT', 'TEACHER'] },
  { path: '/schedules', label: '排程任务', detail: '查看算法进度与分配结果', icon: Cpu, accent: 'pine', roles: ['LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/statistics', label: '使用统计', detail: '计划与实际利用率对照', icon: DataAnalysis, accent: 'ink', roles: ['TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/credit', label: '信用记录', detail: '查看违规与提交申诉', icon: Tickets, accent: 'amber', roles: ['STUDENT', 'TEACHER'] },
].filter((item) => item.roles.some((role) => auth.roles.includes(role as never))))

function focusHint(item: Reservation) {
  if (item.status === 'PENDING_SCHEDULE') return '已提交，等待排程结果发布正式时段。'
  if (item.status === 'APPROVED' && !item.checkinTime) return '预约已批准。到达实验室后请使用终端或扫码签到。'
  if (item.status === 'IN_USE') return '你已签到，可开始实验；结束后记得签退。'
  if (item.checkinTime) return `已于 ${formatDateTime(item.checkinTime)} 签到。`
  return '请按正式时段到场。'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const tasks: Array<Promise<unknown>> = [http.get<DashboardData>('/api/statistics/dashboard')]
    if (isLearner.value) tasks.push(http.get<Page<Reservation>>('/api/reservations/my', { params: { page: 0, size: 20 } }))
    const [dashboard, mine] = await Promise.all(tasks)
    data.value = dashboard as DashboardData
    reservations.value = isLearner.value ? ((mine as Page<Reservation>)?.items || []) : []
    loading.value = false
    await nextTick()
    if (isOps.value) renderChart()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function renderChart() {
  if (!chartElement.value || !data.value || !isOps.value) return
  chart ||= echarts.init(chartElement.value)
  const trend = data.value.trend || []
  const option: EChartsOption = {
    animation: false,
    color: [chartPalette.pine, chartPalette.amber],
    grid: { left: 44, right: 16, top: 40, bottom: 32 },
    legend: { ...chartLegend, data: ['预约数', '未到场数'] },
    tooltip: { ...chartTooltip, trigger: 'axis' },
    xAxis: { type: 'category', data: trend.map((item) => item.date.slice(5)), ...chartAxis },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: chartPalette.muted, fontSize: 11, fontFamily: 'ui-monospace, Consolas, monospace' }, splitLine: chartSplit },
    series: [
      { name: '预约数', type: 'bar', barMaxWidth: 18, data: trend.map((item) => item.reservationCount), itemStyle: { borderRadius: [2, 2, 0, 0], color: chartPalette.pine } },
      { name: '未到场数', type: 'line', data: trend.map((item) => item.noShowCount), smooth: false, symbol: 'circle', symbolSize: 6, lineStyle: { width: 1.5, color: chartPalette.amber }, itemStyle: { color: chartPalette.amber, borderColor: chartPalette.paper, borderWidth: 1 } },
    ],
  }
  chart.setOption(option, true)
}

const resize = () => chart?.resize()
onMounted(() => { load(); window.addEventListener('resize', resize) })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose() })
</script>

<template>
  <div class="page dashboard-page">
    <section class="dashboard-hero">
      <div class="dashboard-hero__copy">
        <span class="eyebrow">{{ rolePresentation.eyebrow }} · {{ data?.date || '今日' }}</span>
        <h1>{{ rolePresentation.title }}</h1>
        <p>{{ rolePresentation.description }}</p>
      </div>
      <div class="dashboard-hero__actions">
        <router-link class="button" to="/reservations">查看预约记录</router-link>
        <router-link v-if="isLearner" class="button button--primary" to="/labs"><el-icon><OfficeBuilding /></el-icon>立即预约<el-icon><ArrowRight /></el-icon></router-link>
      </div>
    </section>

    <div v-if="error" class="error-banner" role="alert">{{ error }} <button class="button button--small" type="button" @click="load">重新加载</button></div>

    <div v-if="loading" class="dashboard-skeleton" aria-busy="true" aria-live="polite">
      <p class="sr-only">正在读取工作台</p>
      <div class="skeleton-grid"><div v-for="index in 4" :key="index" class="skeleton-card"><span class="skeleton-line" style="width:36%" /><span class="skeleton-line skeleton-line--lg" /><span class="skeleton-line skeleton-line--sm" /></div></div>
      <div class="split-layout dashboard-main"><div class="panel skeleton-panel" /><div class="panel skeleton-panel" /></div>
    </div>

    <template v-else-if="data && isLearner">
      <div class="split-layout learner-layout">
        <section class="panel focus-panel" aria-labelledby="focus-title">
          <div class="panel__header">
            <div>
              <h2 id="focus-title">{{ focusReservation ? (focusReservation.date === (data.date || today()) ? '下一场实验' : '最近一场实验') : '今天还没有实验安排' }}</h2>
              <span class="panel__subtitle">{{ focusReservation ? focusHint(focusReservation) : '从实验室时间尺选时段，或让 AI 先整理草稿。' }}</span>
            </div>
            <span v-if="focusReservation" class="tag" :class="focusReservation.status === 'APPROVED' || focusReservation.status === 'IN_USE' ? 'tag--green' : focusReservation.status === 'PENDING_SCHEDULE' ? 'tag--amber' : ''">{{ statusLabels[focusReservation.status] }}</span>
          </div>

          <template v-if="focusReservation">
            <div class="focus-main">
              <div>
                <p class="focus-time mono">{{ reservationTime(focusReservation) }}</p>
                <strong class="focus-lab">{{ focusReservation.labName || '实验室待排程分配' }}</strong>
                <p class="focus-meta">{{ statusLabels[focusReservation.allocationMode] }} · {{ statusLabels[focusReservation.reservationMode] }} · {{ focusReservation.attendeeCount }} 人<span v-if="focusReservation.seats.length"> · {{ focusReservation.seats.map((seat) => seat.seatCode).join('、') }}</span></p>
                <p class="focus-purpose">{{ focusReservation.purpose }}</p>
              </div>
              <ol class="journey" aria-label="实验进度">
                <li v-for="(step, index) in focusJourney" :key="step.label" :class="{ done: step.done, current: step.current }">
                  <span class="journey__index"><el-icon v-if="step.done" :size="14"><CircleCheck /></el-icon><template v-else>{{ index + 1 }}</template></span>
                  <span>{{ step.label }}</span>
                </li>
              </ol>
            </div>
            <div class="button-row focus-actions">
              <router-link class="button" :to="`/reservations`">查看详情</router-link>
              <router-link v-if="focusReservation.status === 'APPROVED' && !focusReservation.checkinTime" class="button button--primary" to="/terminal">去终端签到<el-icon><ArrowRight /></el-icon></router-link>
              <router-link v-else class="button button--primary" to="/labs">继续预约<el-icon><ArrowRight /></el-icon></router-link>
            </div>
          </template>
          <EmptyState v-else title="暂无进行中的实验" description="选择开放时段提交预约后，这里会显示下一场安排与签到指引。">
            <router-link class="button button--primary" to="/labs">去实验室预约</router-link>
          </EmptyState>
        </section>

        <aside class="learner-side">
          <section class="panel side-card">
            <div class="panel__header"><div><h2>今日摘要</h2><span class="panel__subtitle">只看和你相关的数字</span></div></div>
            <div class="side-metrics">
              <div><span>今日实验</span><strong class="mono">{{ todayItems.length }}</strong></div>
              <div><span>待处理预约</span><strong class="mono">{{ upcoming.length }}</strong></div>
              <div v-if="canSeeCredit"><span>信用分</span><strong class="mono">{{ data.creditScore }}</strong></div>
            </div>
            <div class="button-row">
              <router-link class="button button--small" to="/credit">信用与申诉</router-link>
              <router-link v-if="isTeacher" class="button button--small" to="/reservations">课程批次</router-link>
            </div>
          </section>
          <section class="panel side-card">
            <div class="panel__header"><div><h2>快捷入口</h2></div></div>
            <div class="quick-list">
              <router-link v-for="entry in quickEntries" :key="entry.path" :to="entry.path">
                <span class="quick-list__icon" :class="entry.accent"><el-icon><component :is="entry.icon" /></el-icon></span>
                <span class="quick-list__copy"><strong>{{ entry.label }}</strong><small>{{ entry.detail }}</small></span>
                <el-icon><ArrowRight /></el-icon>
              </router-link>
            </div>
          </section>
        </aside>
      </div>

      <section class="panel upcoming-panel" aria-labelledby="upcoming-title">
        <div class="panel__header"><div><h2 id="upcoming-title">接下来的安排</h2><span class="panel__subtitle">按正式时段排序，最多展示 5 条</span></div></div>
        <EmptyState v-if="!upcoming.length" title="近期没有待执行预约" description="新的固定预约或排程结果会出现在这里。" />
        <div v-else class="upcoming-list">
          <article v-for="item in upcoming.slice(0, 5)" :key="item.resvId" class="upcoming-item">
            <div>
              <strong>{{ item.labName || '待分配实验室' }}</strong>
              <span class="mono small">{{ reservationTime(item) }}</span>
            </div>
            <span class="tag" :class="item.status === 'APPROVED' || item.status === 'IN_USE' ? 'tag--green' : item.status === 'PENDING_SCHEDULE' ? 'tag--amber' : ''">{{ statusLabels[item.status] }}</span>
            <router-link class="button button--small" to="/reservations">详情</router-link>
          </article>
        </div>
      </section>
    </template>

    <template v-else-if="data">
      <section aria-labelledby="core-metrics-title"><div class="section-heading"><h2 id="core-metrics-title">今日概览</h2><span>当前账号权限范围内的实时数据</span></div><div class="metrics-grid">
        <MetricCard label="涉及实验室" :value="data.labCount" hint="当前账号统计范围" :icon="OfficeBuilding" accent="pine" />
        <MetricCard label="今日预约" :value="data.reservationCount" :hint="`已批准 ${data.approvedCount}`" :icon="Calendar" accent="ink" />
        <MetricCard label="待排程" :value="data.pendingScheduleCount" hint="等待算法分配" :icon="Clock" accent="amber" tone="amber" />
        <MetricCard label="使用中" :value="data.inUseCount" :hint="`已完成 ${data.completedCount}`" :icon="Monitor" accent="pine" tone="green" />
      </div></section>
      <div class="split-layout dashboard-main">
        <section class="panel trend-panel"><div class="panel__header"><div><h2>近7天预约趋势</h2><span class="panel__subtitle">预约数量与未到场记录的变化</span></div><span class="trend-badge">近 7 天</span></div><div v-if="data.trend?.length" ref="chartElement" class="chart" role="img" :aria-label="`近7天预约趋势，共 ${data.trend.length} 个数据点`" /><EmptyState v-else title="近7天暂无趋势数据" description="产生预约后，趋势会按真实记录显示。" /></section>
        <section class="panel quick-panel"><div class="panel__header"><div><h2>快捷入口</h2><span class="panel__subtitle">继续你的常用操作</span></div></div><div class="quick-list"><router-link v-for="entry in quickEntries" :key="entry.path" :to="entry.path"><span class="quick-list__icon" :class="entry.accent"><el-icon><component :is="entry.icon" /></el-icon></span><span class="quick-list__copy"><strong>{{ entry.label }}</strong><small>{{ entry.detail }}</small></span><el-icon><ArrowRight /></el-icon></router-link></div></section>
      </div>
      <section class="secondary-section" aria-labelledby="secondary-metrics-title"><div class="section-heading"><h2 id="secondary-metrics-title">运营指标</h2><span>利用率、到场情况与信用状态</span></div><div class="metrics-grid metrics-grid--secondary">
        <MetricCard label="计划利用率" :value="percent(data.plannedUtilization)" :icon="DataAnalysis" accent="ink" compact />
        <MetricCard label="实际利用率" :value="percent(data.actualUtilization)" :icon="Monitor" accent="pine" tone="green" compact />
        <MetricCard label="未到场率" :value="percent(data.noShowRate)" :hint="`未到场 ${data.noShowCount} · 取消 ${data.cancelledCount}`" :icon="Clock" accent="amber" tone="red" compact />
        <MetricCard v-if="canSeeCredit" label="信用分" :value="data.creditScore" hint="违规与流水见信用记录" :icon="Medal" accent="ink" compact />
      </div></section>
    </template>
  </div>
</template>

<style scoped>
.dashboard-page { max-width: 1440px; }
.dashboard-hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 22px; }
.dashboard-hero h1 { margin: 8px 0 6px; font-size: clamp(30px, 3vw, 36px); letter-spacing: -.04em; }
.dashboard-hero p { margin: 0; color: var(--body); font-size: 14px; line-height: 1.6; }
.dashboard-hero__actions { display: flex; align-items: center; gap: 8px; }
.section-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 14px; margin: 0 0 12px; }
.section-heading h2 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 16px; }
.section-heading h2::before { width: 14px; height: 2px; background: var(--primary); content: ''; }
.section-heading span { color: var(--muted); font-size: 12px; }
.dashboard-main { grid-template-columns: minmax(0, 1.55fr) minmax(280px, .9fr); }
.learner-layout { grid-template-columns: minmax(0, 1.65fr) minmax(260px, .85fr); margin-bottom: 16px; }
.focus-panel { border-left: 3px solid var(--primary); }
.trend-panel .chart { height: 300px; }
.trend-badge { padding: 4px 8px; color: var(--primary); background: var(--primary-soft); border-radius: 6px; font-size: 12px; font-weight: 600; }
.quick-list { display: grid; }
.quick-list a { display: grid; grid-template-columns: 32px minmax(0, 1fr) 16px; align-items: center; gap: 10px; min-height: 56px; padding: 8px 0; color: var(--ink); border-bottom: 1px solid var(--line); }
.quick-list a:last-child { border-bottom: 0; }
.quick-list a:hover { text-decoration: none; }
.quick-list a:hover .quick-list__copy strong { color: var(--primary); }
.quick-list__icon { display: grid; place-items: center; width: 32px; height: 32px; color: var(--primary); background: var(--primary-soft); border-radius: 6px; }
.quick-list__icon.ink { color: var(--ink); background: #efece6; }
.quick-list__icon.amber { color: #8a5a12; background: #fff4e4; }
.quick-list__copy strong, .quick-list__copy small { display: block; }
.quick-list__copy strong { font-size: 14px; font-weight: 600; }
.quick-list__copy small { margin-top: 2px; overflow: hidden; color: var(--muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.quick-list a > .el-icon { color: var(--muted); font-size: 14px; }
.focus-main { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(200px, .85fr); gap: 20px; align-items: start; }
.focus-time { margin: 0 0 6px; color: var(--ink); font-size: 22px; letter-spacing: -.03em; }
.focus-lab { display: block; font-size: 18px; letter-spacing: -.02em; }
.focus-meta, .focus-purpose { margin: 0; color: var(--body); font-size: 13px; line-height: 1.6; }
.focus-purpose { margin-top: 8px; color: var(--muted); }
.journey { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.journey li { display: flex; align-items: center; gap: 10px; min-height: 40px; padding: 6px 10px; color: var(--muted); background: #f7f4ee; border: 1px solid var(--line); border-radius: 6px; font-size: 13px; }
.journey li.done { color: #185546; background: var(--primary-soft); border-color: #c9e3d8; }
.journey li.current { color: var(--primary); background: var(--paper); border-color: var(--primary); font-weight: 600; }
.journey__index { display: grid; place-items: center; width: 22px; height: 22px; color: inherit; background: var(--paper); border-radius: 6px; font-size: 11px; font-weight: 700; }
.focus-actions { margin-top: 18px; }
.learner-side { display: grid; gap: 16px; align-content: start; }
.side-metrics { display: grid; gap: 10px; margin-bottom: 14px; }
.side-metrics > div { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; padding-bottom: 8px; border-bottom: 1px solid var(--line); }
.side-metrics > div:last-child { border-bottom: 0; padding-bottom: 0; }
.side-metrics span { color: var(--muted); font-size: 12px; }
.side-metrics strong { font-size: 22px; }
.upcoming-panel { margin-bottom: 0; }
.upcoming-list { display: grid; gap: 8px; }
.upcoming-item { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: 12px; align-items: center; padding: 12px; background: #f7f4ee; border: 1px solid var(--line); border-radius: 6px; }
.upcoming-item strong, .upcoming-item span { display: block; }
.upcoming-item .mono { margin-top: 4px; color: var(--muted); }
.secondary-section { margin-top: 4px; }
.metrics-grid--secondary { margin-bottom: 0; }
@media (max-width: 1100px) {
  .learner-layout, .focus-main, .dashboard-main { grid-template-columns: 1fr; }
}
@media (max-width: 900px) { .dashboard-hero { align-items: flex-start; flex-direction: column; } }
@media (max-width: 720px) {
  .dashboard-hero__actions { align-items: stretch; flex-direction: column; width: 100%; }
  .dashboard-hero__actions .button { width: 100%; }
  .section-heading span { display: none; }
  .upcoming-item { grid-template-columns: 1fr; }
}
</style>
