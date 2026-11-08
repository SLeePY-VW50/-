<script setup lang="ts">
import type { Component } from 'vue'
import { DataLine } from '@element-plus/icons-vue'

defineProps<{
  label: string
  value: string | number
  hint?: string
  tone?: 'green' | 'amber' | 'red' | 'ink'
  compact?: boolean
  icon?: Component
  accent?: 'blue' | 'purple' | 'green' | 'orange'
}>()
</script>

<template>
  <article class="metric-card" :class="[`metric-card--${tone || 'ink'}`, { 'metric-card--compact': compact }]">
    <div class="metric-card__top"><span class="metric-card__label">{{ label }}</span><span class="metric-card__icon" :class="accent || 'blue'"><el-icon><component :is="icon || DataLine" /></el-icon></span></div>
    <strong class="metric-card__value">{{ value }}</strong>
    <span v-if="hint" class="metric-card__hint">{{ hint }}</span>
  </article>
</template>

<style scoped>
.metric-card { min-width: 0; min-height: 136px; padding: 19px 20px; background: #fff; border: 1px solid var(--line); border-radius: 14px; box-shadow: var(--shadow); }
.metric-card__top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.metric-card__label, .metric-card__hint { display: block; color: #77849a; font-size: 12px; line-height: 1.5; }
.metric-card__label { font-weight: 500; }
.metric-card__icon { display: grid; place-items: center; flex: 0 0 auto; width: 34px; height: 34px; border-radius: 10px; font-size: 17px; }
.metric-card__icon.blue { color: #3867e8; background: #edf2ff; }
.metric-card__icon.purple { color: #8264d8; background: #f2edff; }
.metric-card__icon.green { color: #159b72; background: #eaf8f2; }
.metric-card__icon.orange { color: #c58225; background: #fff5e8; }
.metric-card__value { display: block; margin: 10px 0 6px; color: #18243a; font-family: 'Cascadia Mono', Consolas, monospace; font-size: 29px; font-weight: 700; line-height: 1.1; letter-spacing: -.04em; font-variant-numeric: tabular-nums; }
.metric-card__hint { color: #9aa5b5; font-size: 11px; }
.metric-card--compact { min-height: 110px; padding: 16px 18px; box-shadow: none; }
.metric-card--compact .metric-card__icon { width: 29px; height: 29px; font-size: 14px; }
.metric-card--compact .metric-card__value { margin-block: 8px 5px; font-size: 24px; }
@media (max-width: 460px) { .metric-card { min-height: 124px; } .metric-card--compact { min-height: 108px; } }
</style>
