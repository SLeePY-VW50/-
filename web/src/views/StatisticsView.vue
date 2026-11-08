<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
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
let trendChart: echarts.ECharts | null = null

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
    animation: false, color: ['#3867e8', '#159b72'], tooltip: { trigger: 'axis', valueFormatter: (value) => percent(Number(value)), backgroundColor: '#fff', borderColor: '#e8edf4', textStyle: { color: '#18243a', fontSize: 12 }, padding: 12 }, legend: { top: 0, right: 0, icon: 'roundRect', itemWidth: 10, itemHeight: 10, textStyle: { color: '#77849a', fontSize: 11 } }, grid: { left: 58, right: 24, top: 44, bottom: 38 },
    xAxis: { type: 'category', data: days.value.map((day) => day.date), axisLine: { lineStyle: { color: '#e8edf4' } }, axisTick: { show: false }, axisLabel: { color: '#77849a', fontFamily: 'Consolas', fontSize: 11, margin: 14 } },
    yAxis: { type: 'value', max: 1, axisLabel: { color: '#898989', fontSize: 11, formatter: (value: number) => `${value * 100}%` }, splitLine: { lineStyle: { color: '#f3f4f6', type: 'dashed' } } },
    series: [
      { name: '计划利用率', type: 'line', data: days.value.map((day) => dailyAverage(day, 'plannedRatio')), connectNulls: false, smooth: .25, symbol: 'circle', symbolSize: 7, lineStyle: { width: 2.5 } },
      { name: '实际利用率', type: 'line', data: days.value.map((day) => dailyAverage(day, 'actualRatio')), connectNulls: false, smooth: .25, symbol: 'circle', symbolSize: 7, lineStyle: { width: 2.5 } },
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
    <header class="page-header"><div><span class="eyebrow">PLANNED / ACTUAL</span><h1>实验室使用统计</h1><p>48时间片热力图与趋势完全由统计数据生成，可切换计划和实际口径。</p></div><button class="button" :disabled="!labId || downloading" @click="downloadCsv">{{ downloading ? '正在导出…' : '下载预约CSV' }}</button></header>
    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <section class="panel"><div class="toolbar"><label class="field"><span>实验室</span><select v-model.number="labId"><option v-for="lab in labs" :key="lab.labId" :value="lab.labId">{{ lab.name }}</option></select></label><label class="field"><span>开始日期</span><input v-model="start" type="date"></label><label class="field"><span>结束日期</span><input v-model="end" type="date"></label><button class="button button--primary" @click="load">查询统计</button></div>
      <div class="statistics-switch" role="group" aria-label="热力图口径"><button :class="{ active: mode === 'planned' }" @click="mode = 'planned'">计划利用率</button><button :class="{ active: mode === 'actual' }" @click="mode = 'actual'">实际利用率</button></div>
      <div v-if="loading" class="loading-line">正在计算热力图</div><EmptyState v-else-if="!days.length || days.every((day) => !day.slots.length)" title="该范围内没有热力图数据" description="调整实验室或日期范围；空数据不会生成模拟色块。" /><UsageHeatmap v-else :days="days" :mode="mode" />
    </section>
    <section v-if="days.length" class="panel"><div class="panel__header"><div><h2>日期趋势</h2><span class="panel__subtitle">每日48格比率的算术平均，空日按0显示</span></div></div><div ref="trendElement" class="chart" /></section>
  </div>
</template>

<style scoped>
.statistics-switch { display: inline-flex; gap: 4px; margin-bottom: 17px; padding: 5px; background: #f8f9fa; border: 1px solid #f3f4f6; border-radius: 9999px; }
.statistics-switch button { padding: 8px 14px; color: var(--muted); background: transparent; border: 0; border-radius: 8px; cursor: pointer; font-size: 13px; font-weight: 500; }
.statistics-switch button.active { color: var(--primary); background: #fff; box-shadow: 0 1px 5px rgb(36 57 91 / 9%); font-weight: 600; }
</style>
