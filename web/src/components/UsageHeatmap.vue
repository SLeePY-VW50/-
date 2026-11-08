<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import type { HeatmapDay } from '../types'

const props = defineProps<{ days: HeatmapDay[]; mode: 'planned' | 'actual' }>()
const container = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

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
      formatter: (params: unknown) => {
        const value = (params as { value: [number, number, number] }).value
        const hour = String(Math.floor(value[0] / 2)).padStart(2, '0')
        return `${dates[value[1]]}<br/>${hour}:${value[0] % 2 ? '30' : '00'} · ${(value[2] * 100).toFixed(1)}%`
      },
    },
    xAxis: { type: 'category', data: Array.from({ length: 48 }, (_, index) => `${String(Math.floor(index / 2)).padStart(2, '0')}:${index % 2 ? '30' : '00'}`), axisLabel: { color: '#7b8798', interval: 5, rotate: 45, fontFamily: 'Consolas', fontSize: 10 }, splitArea: { show: true } },
    yAxis: { type: 'category', data: dates, axisLabel: { color: '#7b8798', fontFamily: 'Consolas', fontSize: 11 } },
    visualMap: { min: 0, max: 1, calculable: false, orient: 'horizontal', left: 'center', bottom: 4, text: ['100%', '0'], textStyle: { color: '#77849a', fontSize: 10 }, inRange: { color: ['#ffffff', '#eaf0ff', '#9db5ef', '#3867e8'] } },
    series: [{ type: 'heatmap', data: values, itemStyle: { borderColor: '#fff', borderWidth: 1 }, emphasis: { itemStyle: { borderColor: '#b87518', borderWidth: 2 } } }],
  }
  chart.setOption(option, true)
}
const resize = () => chart?.resize()
onMounted(async () => { await nextTick(); render(); window.addEventListener('resize', resize) })
watch(() => [props.days, props.mode], async () => { await nextTick(); render() }, { deep: true })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose(); chart = null })
</script>

<template><div ref="container" class="chart usage-heatmap" role="img" aria-label="实验室48时间片使用率热力图" /></template>
