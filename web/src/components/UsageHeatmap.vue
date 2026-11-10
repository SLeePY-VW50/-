<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { echarts, type EChartsOption, type EChartsType } from '../charts'
import { chartPalette, chartTooltip } from '../chartTheme'
import type { HeatmapDay } from '../types'
import { percent } from '../utils'

const props = defineProps<{ days: HeatmapDay[]; mode: 'planned' | 'actual' }>()
const container = ref<HTMLDivElement>()
let chart: EChartsType | null = null

const summary = computed(() => {
  const key = props.mode === 'planned' ? 'plannedRatio' : 'actualRatio'
  const values = props.days.flatMap((day) => day.slots.map((slot) => slot[key]))
  if (!values.length) return '当前范围没有可用热力数据。'
  const peak = Math.max(...values)
  const average = values.reduce((sum, value) => sum + value, 0) / values.length
  return `${props.mode === 'planned' ? '计划' : '实际'}利用率热力图，覆盖 ${props.days.length} 天、共 ${values.length} 个时间片；平均 ${percent(average)}，峰值 ${percent(peak)}。`
})

function render() {
  if (!container.value) return
  chart ||= echarts.init(container.value)
  const dates = props.days.map((day) => day.date)
  const values = props.days.flatMap((day, dayIndex) => day.slots.map((slot) => [
    slot.slot,
    dayIndex,
    props.mode === 'planned' ? slot.plannedRatio : slot.actualRatio,
  ]))
  const option: EChartsOption = {
    animation: false,
    grid: { left: 88, right: 28, top: 24, bottom: 72 },
    tooltip: {
      ...chartTooltip,
      formatter: (params: unknown) => {
        const value = (params as { value: [number, number, number] }).value
        const hour = String(Math.floor(value[0] / 2)).padStart(2, '0')
        return `${dates[value[1]]}<br/>${hour}:${value[0] % 2 ? '30' : '00'} · ${(value[2] * 100).toFixed(1)}%`
      },
    },
    xAxis: { type: 'category', data: Array.from({ length: 48 }, (_, index) => `${String(Math.floor(index / 2)).padStart(2, '0')}:${index % 2 ? '30' : '00'}`), axisLabel: { color: chartPalette.muted, interval: 5, rotate: 45, fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 10 }, splitArea: { show: false } },
    yAxis: { type: 'category', data: dates, axisLabel: { color: chartPalette.muted, fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 11 } },
    visualMap: { min: 0, max: 1, calculable: false, orient: 'horizontal', left: 'center', bottom: 4, text: ['100%', '0'], textStyle: { color: chartPalette.muted, fontSize: 11 }, inRange: { color: [chartPalette.paper, chartPalette.wash, chartPalette.mid, chartPalette.pine] } },
    series: [{ type: 'heatmap', data: values, itemStyle: { borderColor: chartPalette.paper, borderWidth: 1 }, emphasis: { itemStyle: { borderColor: chartPalette.amber, borderWidth: 2 } } }],
  }
  chart.setOption(option, true)
}
const resize = () => chart?.resize()
onMounted(async () => { await nextTick(); render(); window.addEventListener('resize', resize) })
watch(() => [props.days, props.mode], async () => { await nextTick(); render() }, { deep: true })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose(); chart = null })
</script>

<template>
  <div>
    <p class="sr-only">{{ summary }}</p>
    <div ref="container" class="chart usage-heatmap" role="img" :aria-label="summary" />
  </div>
</template>
