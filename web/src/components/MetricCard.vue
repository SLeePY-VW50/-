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
  accent?: 'pine' | 'ink' | 'amber'
}>()
</script>

<template>
  <article class="metric-card" :class="[`metric-card--${tone || 'ink'}`, { 'metric-card--compact': compact }]">
    <div class="metric-card__top"><span class="metric-card__label">{{ label }}</span><span class="metric-card__icon" :class="accent || 'pine'"><el-icon><component :is="icon || DataLine" /></el-icon></span></div>
    <strong class="metric-card__value">{{ value }}</strong>
    <span v-if="hint" class="metric-card__hint">{{ hint }}</span>
  </article>
</template>

<style scoped>
.metric-card { min-width: 0; min-height: 124px; padding: 16px 16px 14px; background: var(--paper); border: 1px solid var(--line); border-radius: 8px; box-shadow: none; }
.metric-card__top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.metric-card__label, .metric-card__hint { display: block; color: var(--muted); font-size: 12px; line-height: 1.5; }
.metric-card__label { color: var(--body); font-weight: 600; }
.metric-card__icon { display: grid; place-items: center; flex: 0 0 auto; width: 32px; height: 32px; border-radius: 6px; font-size: 16px; }
.metric-card__icon.pine { color: var(--primary); background: var(--primary-soft); }
.metric-card__icon.ink { color: var(--ink); background: #efece6; }
.metric-card__icon.amber { color: #8a5a12; background: #fff4e4; }
.metric-card__value { display: block; margin: 12px 0 4px; color: var(--ink); font-family: var(--mono); font-size: 28px; font-weight: 700; line-height: 1.1; letter-spacing: -.04em; font-variant-numeric: tabular-nums; }
.metric-card--amber .metric-card__value { color: #8a5a12; }
.metric-card--red .metric-card__value { color: #9a3a30; }
.metric-card--green .metric-card__value { color: var(--primary); }
.metric-card__hint { color: var(--muted); font-size: 12px; }
.metric-card--compact { min-height: 108px; padding: 14px; }
.metric-card--compact .metric-card__icon { width: 28px; height: 28px; font-size: 14px; }
.metric-card--compact .metric-card__value { margin-block: 8px 4px; font-size: 22px; }
</style>
