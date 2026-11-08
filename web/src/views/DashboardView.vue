<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import { ArrowRight, Calendar, ChatDotSquare, Clock, Cpu, DataAnalysis, Medal, Monitor, OfficeBuilding, Tickets } from '@element-plus/icons-vue'
import http, { errorMessage } from '../api/http'
import type { DashboardData } from '../types'
import { useAuthStore } from '../stores/auth'
import { percent } from '../utils'
import MetricCard from '../components/MetricCard.vue'
import EmptyState from '../components/EmptyState.vue'

const auth = useAuthStore()
const data = ref<DashboardData | null>(null)
const loading = ref(true)
const error = ref('')
const chartElement = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
const canSeeCredit = auth.roles.includes('STUDENT') || auth.roles.includes('TEACHER')
const rolePresentation = computed(() => {
  if (auth.roles.includes('SYS_ADMIN')) return { eyebrow: 'SYSTEM CONTROL CENTER', title: '系统管理总览', description: '统一管理实验室资源、用户权限、开放规则与申诉流程。' }
  if (auth.roles.includes('LAB_ADMIN')) return { eyebrow: 'LAB OPERATIONS', title: '实验室运行总览', description: '关注资源使用、预约执行与自动排程运行情况。' }
  if (auth.roles.includes('TEACHER')) return { eyebrow: 'TEACHER WORKSPACE', title: '教师工作台', description: '统筹课程实验时段、批量预约与实验室使用情况。' }
  return { eyebrow: 'STUDENT SPACE', title: `你好，${auth.user?.name || '同学'}`, description: '今天也为你的实验学习安排一个高效的时间段。' }
})
const quickEntries = [
  { path: '/labs', label: '查看实验室', detail: '查询48格时间尺与工位状态', icon: OfficeBuilding, accent: 'blue', roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/reservations', label: '预约记录', detail: '查询详情与正式座位', icon: Calendar, accent: 'purple', roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/assistant', label: 'AI预约助手', detail: '先形成草稿，再核对提交', icon: ChatDotSquare, accent: 'green', roles: ['STUDENT', 'TEACHER'] },
  { path: '/schedules', label: '排程任务', detail: '查看算法进度与分配结果', icon: Cpu, accent: 'green', roles: ['LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/statistics', label: '使用统计', detail: '计划与实际利用率对照', icon: DataAnalysis, accent: 'orange', roles: ['TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/credit', label: '信用记录', detail: '查看违规与提交申诉', icon: Tickets, accent: 'orange', roles: ['STUDENT', 'TEACHER'] },
].filter((item) => item.roles.some((role) => auth.roles.includes(role as never)))

async function load() {
  loading.value = true; error.value = ''
  try {
    data.value = await http.get<DashboardData>('/api/statistics/dashboard')
    loading.value = false
    await nextTick(); renderChart()
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
function renderChart() {
  if (!chartElement.value || !data.value) return
  chart ||= echarts.init(chartElement.value)
  const trend = data.value.trend || []
  const option: EChartsOption = {
    animation: false,
    color: ['#3867e8', '#e39a37'],
    grid: { left: 48, right: 20, top: 48, bottom: 38 },
    legend: { data: ['预约数', '未到场数'], top: 0, right: 0, itemWidth: 10, itemHeight: 10, icon: 'roundRect', textStyle: { color: '#77849a', fontSize: 11 } },
    tooltip: { trigger: 'axis', backgroundColor: '#fff', borderColor: '#e8edf4', textStyle: { color: '#18243a', fontSize: 12 }, padding: 12 },
    xAxis: { type: 'category', data: trend.map((item) => item.date.slice(5)), axisLine: { lineStyle: { color: '#e8edf4' } }, axisTick: { show: false }, axisLabel: { color: '#77849a', fontFamily: 'Consolas', fontSize: 11, margin: 14 } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: '#96a1b2', fontSize: 11 }, splitLine: { lineStyle: { color: '#f0f3f8', type: 'dashed' } } },
    series: [
      { name: '预约数', type: 'bar', barMaxWidth: 26, data: trend.map((item) => item.reservationCount), itemStyle: { borderRadius: [5, 5, 0, 0], color: '#3867e8' } },
      { name: '未到场数', type: 'line', data: trend.map((item) => item.noShowCount), smooth: .25, symbol: 'circle', symbolSize: 7, lineStyle: { width: 2.5 }, itemStyle: { color: '#e39a37', borderColor: '#fff', borderWidth: 2 } },
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
      <div class="dashboard-hero__copy"><span class="eyebrow">{{ rolePresentation.eyebrow }} / {{ data?.date || 'TODAY' }}</span><h1>{{ rolePresentation.title }}</h1><p>{{ rolePresentation.description }}</p></div>
      <div class="dashboard-hero__actions"><router-link class="button" to="/reservations">查看预约记录</router-link><router-link v-if="canSeeCredit" class="button button--primary" to="/labs"><el-icon><OfficeBuilding /></el-icon>立即预约<el-icon><ArrowRight /></el-icon></router-link></div>
    </section>
    <div v-if="error" class="error-banner" role="alert">{{ error }} <button class="button button--small" @click="load">重新加载</button></div>
    <div v-if="loading" class="loading-line">正在读取工作台指标</div>
    <template v-else-if="data">
      <section aria-labelledby="core-metrics-title"><div class="section-heading"><h2 id="core-metrics-title">今日概览</h2><span>当前账号权限范围内的实时数据</span></div><div class="metrics-grid">
        <MetricCard label="涉及实验室" :value="data.labCount" hint="当前账号统计范围" :icon="OfficeBuilding" accent="blue" />
        <MetricCard label="今日预约" :value="data.reservationCount" :hint="`已批准 ${data.approvedCount}`" :icon="Calendar" accent="purple" />
        <MetricCard label="待排程" :value="data.pendingScheduleCount" hint="等待算法分配" :icon="Clock" accent="orange" tone="amber" />
        <MetricCard label="使用中" :value="data.inUseCount" :hint="`已完成 ${data.completedCount}`" :icon="Monitor" accent="green" tone="green" />
      </div></section>
      <div class="split-layout dashboard-main">
        <section class="panel trend-panel"><div class="panel__header"><div><h2>近7天预约趋势</h2><span class="panel__subtitle">预约数量与未到场记录的变化</span></div><span class="trend-badge">7 DAYS</span></div><div v-if="data.trend?.length" ref="chartElement" class="chart" /><EmptyState v-else title="近7天暂无趋势数据" description="产生预约后，趋势会按真实记录显示。" /></section>
        <section class="panel quick-panel"><div class="panel__header"><div><h2>快捷入口</h2><span class="panel__subtitle">继续你的常用操作</span></div></div><div class="quick-list"><router-link v-for="entry in quickEntries" :key="entry.path" :to="entry.path"><span class="quick-list__icon" :class="entry.accent"><el-icon><component :is="entry.icon" /></el-icon></span><span class="quick-list__copy"><strong>{{ entry.label }}</strong><small>{{ entry.detail }}</small></span><el-icon><ArrowRight /></el-icon></router-link></div></section>
      </div>
      <section class="secondary-section" aria-labelledby="secondary-metrics-title"><div class="section-heading"><h2 id="secondary-metrics-title">运营指标</h2><span>利用率、到场情况与信用状态</span></div><div class="metrics-grid metrics-grid--secondary">
        <MetricCard label="计划利用率" :value="percent(data.plannedUtilization)" :icon="DataAnalysis" accent="blue" compact />
        <MetricCard label="实际利用率" :value="percent(data.actualUtilization)" :icon="Monitor" accent="green" tone="green" compact />
        <MetricCard label="未到场率" :value="percent(data.noShowRate)" :hint="`未到场 ${data.noShowCount} · 取消 ${data.cancelledCount}`" :icon="Clock" accent="orange" tone="red" compact />
        <MetricCard v-if="canSeeCredit" label="信用分" :value="data.creditScore" hint="违规与流水见信用记录" :icon="Medal" accent="purple" compact />
      </div></section>
    </template>
  </div>
</template>

<style scoped>
.dashboard-page { max-width: 1500px; }
.dashboard-hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 28px; margin-bottom: 26px; padding: 3px 1px 0; }
.dashboard-hero h1 { margin: 8px 0 8px; font-size: clamp(28px, 3vw, 36px); letter-spacing: -.04em; }
.dashboard-hero p { margin: 0; color: #77849a; font-size: 13px; line-height: 1.6; }
.dashboard-hero__actions { display: flex; align-items: center; gap: 10px; padding-bottom: 2px; }
.section-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 14px; margin: 0 1px 13px; }
.section-heading h2 { margin: 0; font-size: 16px; }
.section-heading span { color: #96a1b2; font-size: 11px; }
.dashboard-main { grid-template-columns: minmax(0, 1.55fr) minmax(300px, 1fr); }
.trend-panel, .quick-panel { min-height: 408px; }
.trend-panel .chart { height: 315px; }
.trend-badge { padding: 4px 10px; color: #3867e8; background: #eaf0ff; border-radius: 9999px; font: 9px 'Cascadia Mono', Consolas, monospace; }
.quick-panel { padding-bottom: 15px; }
.quick-list { display: grid; }
.quick-list a { display: grid; grid-template-columns: 36px minmax(0, 1fr) 18px; align-items: center; gap: 11px; min-height: 65px; padding: 10px 0; color: #18243a; border-bottom: 1px solid #f0f3f7; }
.quick-list a:last-child { border-bottom: 0; }
.quick-list a:hover { text-decoration: none; }
.quick-list a:hover .quick-list__copy strong { color: #3867e8; }
.quick-list__icon { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 10px; }
.quick-list__icon.blue { color: #3867e8; background: #edf2ff; }
.quick-list__icon.purple { color: #8264d8; background: #f2edff; }
.quick-list__icon.green { color: #159b72; background: #eaf8f2; }
.quick-list__icon.orange { color: #c58225; background: #fff5e8; }
.quick-list__copy strong, .quick-list__copy small { display: block; }
.quick-list__copy strong { font-size: 13px; font-weight: 600; }
.quick-list__copy small { margin-top: 4px; overflow: hidden; color: #96a1b2; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.quick-list a > .el-icon { color: #aab2c0; font-size: 14px; }
.secondary-section { margin-top: 4px; }
.metrics-grid--secondary { margin-bottom: 0; }
@media (max-width: 900px) { .dashboard-hero { align-items: flex-start; flex-direction: column; } .dashboard-hero__actions { width: 100%; justify-content: flex-start; } }
@media (max-width: 720px) { .dashboard-hero__actions { align-items: stretch; flex-direction: column; } .trend-panel, .quick-panel { min-height: 0; } .section-heading span { display: none; } }
</style>
