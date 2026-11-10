<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { echarts, type EChartsOption, type EChartsType } from '../charts'
import { chartAxis, chartLegend, chartPalette, chartSplit, chartTooltip } from '../chartTheme'
import http, { errorMessage } from '../api/http'
import type { HeatmapDay, Lab } from '../types'
import { addDays, percent, today } from '../utils'
import UsageHeatmap from '../components/UsageHeatmap.vue'
import EmptyState from '../components/EmptyState.vue'

const labs = ref<Lab[]>([])
const labId = ref<number | null>(null)
const start = ref(addDays(today(), -6))
const end = ref(today())
const days = ref<HeatmapDay[]>([])
const mode = ref<'planned' | 'actual'>('planned')
const loading = ref(false)
const downloading = ref(false)
const error = ref('')
const trendElement = ref<HTMLDivElement>()
let trendChart: EChartsType | null = null

async function loadLabs() {
  try {
    labs.value = await http.get<Lab[]>('/api/labs')
    if (labs.value.length) { labId.value = labs.value[0].labId; await load() }
  } catch (cause) { error.value = errorMessage(cause) }
}
async function load() {
  if (!labId.value) return
  if (start.value > end.value) { error.value = '开始日期不能晚于结束日期'; return }
  loading.value = true; error.value = ''
  try {
    days.value = await http.get<HeatmapDay[]>('/api/statistics/heatmap', { params: { labId: labId.value, start: start.value, end: end.value } })
    loading.value = false
    await nextTick(); renderTrend()
  } catch (cause) { error.value = errorMessage(cause); days.value = [] } finally { loading.value = false }
}
function dailyAverage(day: HeatmapDay, key: 'plannedRatio' | 'actualRatio') {
  if (!day.slots.length) return 0
  return day.slots.reduce((sum, slot) => sum + slot[key], 0) / day.slots.length
}
function renderTrend() {
  if (!trendElement.value) return
  trendChart ||= echarts.init(trendElement.value)
  const option: EChartsOption = {
    animation: false, color: [chartPalette.pine, chartPalette.ink], tooltip: { ...chartTooltip, trigger: 'axis', valueFormatter: (value) => percent(Number(value)) }, legend: chartLegend, grid: { left: 52, right: 16, top: 40, bottom: 32 },
    xAxis: { type: 'category', data: days.value.map((day) => day.date), ...chartAxis },
    yAxis: { type: 'value', max: 1, axisLabel: { color: chartPalette.muted, fontSize: 11, fontFamily: 'ui-monospace, Consolas, monospace', formatter: (value: number) => `${Math.round(value * 100)}%` }, splitLine: chartSplit },
    series: [
      { name: '计划利用率', type: 'line', data: days.value.map((day) => dailyAverage(day, 'plannedRatio')), connectNulls: false, smooth: false, symbol: 'circle', symbolSize: 6, lineStyle: { width: 1.5, color: chartPalette.pine }, itemStyle: { color: chartPalette.pine } },
      { name: '实际利用率', type: 'line', data: days.value.map((day) => dailyAverage(day, 'actualRatio')), connectNulls: false, smooth: false, symbol: 'circle', symbolSize: 6, lineStyle: { width: 1.5, color: chartPalette.ink }, itemStyle: { color: chartPalette.ink } },
    ],
  }
  trendChart.setOption(option, true)
}
async function downloadCsv() {
  if (!labId.value || start.value > end.value) return
  downloading.value = true; error.value = ''
  try {
    const blob = await http.get<Blob>('/api/statistics/reservations.csv', { params: { labId: labId.value, start: start.value, end: end.value }, responseType: 'blob' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `reservations-${start.value}-${end.value}.csv`; anchor.click()
    URL.revokeObjectURL(url)
  } catch (cause) { error.value = errorMessage(cause) } finally { downloading.value = false }
}
const resize = () => trendChart?.resize()
onMounted(() => { loadLabs(); window.addEventListener('resize', resize) })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); trendChart?.dispose() })
</script>

<template>
  <div class="page">
    <header class="page-header"><div><span class="eyebrow">计划与实际对照</span><h1>实验室使用统计</h1><p>48时间片热力图与趋势完全由统计数据生成，可切换计划和实际口径。</p></div><button class="button" type="button" :disabled="!labId || downloading" @click="downloadCsv">{{ downloading ? '正在导出…' : '下载预约CSV' }}</button></header>
    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <section class="panel"><div class="toolbar"><label class="field"><span>实验室</span><select v-model.number="labId"><option v-for="lab in labs" :key="lab.labId" :value="lab.labId">{{ lab.name }}</option></select></label><label class="field"><span>开始日期</span><input v-model="start" type="date"></label><label class="field"><span>结束日期</span><input v-model="end" type="date"></label><button class="button button--primary" type="button" @click="load">查询统计</button></div>
      <div class="statistics-switch" role="group" aria-label="热力图口径"><button type="button" :class="{ active: mode === 'planned' }" :aria-pressed="mode === 'planned'" @click="mode = 'planned'">计划利用率</button><button type="button" :class="{ active: mode === 'actual' }" :aria-pressed="mode === 'actual'" @click="mode = 'actual'">实际利用率</button></div>
      <div v-if="loading" class="loading-line">正在计算热力图</div><EmptyState v-else-if="!error && (!days.length || days.every((day) => !day.slots.length))" title="该范围内没有热力图数据" description="调整实验室或日期范围；空数据不会生成模拟色块。" /><UsageHeatmap v-else :days="days" :mode="mode" />
    </section>
    <section v-if="days.length" class="panel"><div class="panel__header"><div><h2>日期趋势</h2><span class="panel__subtitle">每日48格比率的算术平均，空日按0显示</span></div></div><div ref="trendElement" class="chart" /></section>
  </div>
</template>

<style scoped>
.statistics-switch { display: inline-flex; gap: 4px; margin-bottom: 16px; padding: 4px; background: #f7f4ee; border: 1px solid var(--line); border-radius: 8px; }
.statistics-switch button { min-height: 40px; padding: 8px 14px; color: var(--body); background: transparent; border: 0; border-radius: 6px; cursor: pointer; font-size: 14px; font-weight: 500; }
.statistics-switch button.active { color: var(--primary); background: var(--paper); font-weight: 600; }
.statistics-switch button:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
</style>
