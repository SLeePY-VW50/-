<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SlotAvailability } from '../types'

export interface SlotRange { start: number; end: number }

const props = withDefaults(defineProps<{
  slots: SlotAvailability[]
  modelValue?: SlotRange | null
  mode?: 'ROOM' | 'SEAT'
  disabled?: boolean
  openTime?: string
  closeTime?: string
}>(), { modelValue: null, mode: 'ROOM', disabled: false, openTime: '00:00', closeTime: '24:00' })

const emit = defineEmits<{ 'update:modelValue': [value: SlotRange | null] }>()
const conflict = ref<number[]>([])
const message = ref('')
const buttons = ref<HTMLButtonElement[]>([])

const minute = (value: string, end = false) => {
  if (end && value.startsWith('24:00')) return 1440
  const [hours, minutes] = value.split(':').map(Number)
  return hours * 60 + minutes
}

const openStart = computed(() => minute(props.openTime))
const openEnd = computed(() => minute(props.closeTime, true))
const bandMeta = [
  { name: '凌晨', range: '00–06' },
  { name: '上午', range: '06–12' },
  { name: '下午', range: '12–18' },
  { name: '晚间', range: '18–24' },
]
const bands = computed(() => {
  const rows = Math.ceil(props.slots.length / 12)
  return Array.from({ length: rows }, (_, row) => ({
    ...(bandMeta[row] || { name: '', range: '' }),
    slots: props.slots.slice(row * 12, row * 12 + 12),
  }))
})

function isOpen(index: number) {
  return index * 30 >= openStart.value && (index + 1) * 30 <= openEnd.value
}

function isOccupied(slot: SlotAvailability) {
  return props.mode === 'ROOM' ? slot.roomOccupied : slot.availableSeatCount <= 0
}

function stateClass(slot: SlotAvailability) {
  const selected = props.modelValue && slot.index >= props.modelValue.start && slot.index <= props.modelValue.end
  return {
    'slot--closed': !isOpen(slot.index),
    'slot--occupied': isOccupied(slot),
    'slot--selected': selected,
    'slot--conflict': conflict.value.includes(slot.index),
    'slot--hour': slot.index % 2 === 0,
  }
}

function selectSlot(index: number) {
  conflict.value = []
  message.value = ''
  if (props.disabled || !isOpen(index)) {
    message.value = '该时段不在开放范围内'
    return
  }
  const start = props.modelValue?.start ?? index
  const from = Math.min(start, index)
  const to = Math.max(start, index)
  if (to - from + 1 > 8) {
    message.value = '连续选择最多8个时间片（4小时）'
    conflict.value = Array.from({ length: to - from + 1 }, (_, offset) => from + offset)
    return
  }
  const blocked = props.slots.filter((slot) => slot.index >= from && slot.index <= to && (!isOpen(slot.index) || isOccupied(slot)))
  if (blocked.length) {
    conflict.value = blocked.map((slot) => slot.index)
    message.value = '所选连续时段包含已占用时间片，请重新选择'
    return
  }
  emit('update:modelValue', { start: from, end: to })
}

function moveFocus(event: KeyboardEvent, index: number) {
  const offsets: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -12, ArrowDown: 12 }
  const offset = offsets[event.key]
  if (!offset) return
  event.preventDefault()
  const next = Math.max(0, Math.min(47, index + offset))
  buttons.value[next]?.focus()
}
</script>

<template>
  <div class="slot-grid-wrap">
    <div class="slot-legend" aria-label="时间片图例">
      <span><i class="legend-open" />开放</span><span><i class="legend-closed" />未开放</span><span><i class="legend-busy" />占用</span><span><i class="legend-selected" />选中</span><span><i class="legend-conflict" />冲突</span>
    </div>
    <div v-if="slots.length" class="slot-board" role="group" aria-label="48格预约时间尺，每格30分钟，方向键可移动">
      <div v-for="band in bands" :key="band.range || band.name" class="slot-band">
        <span class="slot-band__label" aria-hidden="true"><strong>{{ band.name }}</strong><small>{{ band.range }}</small></span>
        <div class="slot-band__grid">
          <button
            v-for="slot in band.slots"
            :key="slot.index"
            :ref="(element) => { if (element) buttons[slot.index] = element as HTMLButtonElement }"
            type="button"
            class="slot"
            :class="stateClass(slot)"
            :disabled="disabled"
            :aria-pressed="Boolean(modelValue && slot.index >= modelValue.start && slot.index <= modelValue.end)"
            :aria-label="`${slot.start}至${slot.end}，${!isOpen(slot.index) ? '未开放' : isOccupied(slot) ? '已占用' : '可预约'}${mode === 'SEAT' ? `，余${slot.availableSeatCount}个工位` : ''}`"
            :title="`${slot.start}–${slot.end} · ${mode === 'SEAT' ? `余${slot.availableSeatCount}工位` : slot.roomOccupied ? '整室占用' : '整室可用'}`"
            @click="selectSlot(slot.index)"
            @keydown="moveFocus($event, slot.index)"
          >
            <span class="slot__time">{{ slot.start }}</span>
            <span class="slot__count">{{ mode === 'SEAT' ? slot.availableSeatCount : slot.roomOccupied ? '占' : '空' }}</span>
            <span class="slot__bar" />
          </button>
        </div>
      </div>
    </div>
    <p v-if="message" class="slot-message" role="alert">{{ message }}</p>
    <p v-else class="slot-help">点击起止格选择连续1–8片；方向键移动焦点，每片30分钟。</p>
  </div>
</template>

<style scoped>
.slot-grid-wrap { min-width: 0; }
.slot-legend { display: flex; flex-wrap: wrap; gap: 14px; margin-bottom: 12px; color: var(--body); font-size: 12px; }
.slot-legend span { display: inline-flex; align-items: center; gap: 6px; }
.slot-legend i { width: 12px; height: 12px; border: 1px solid var(--line); border-radius: 2px; }
.legend-open { background: var(--paper); box-shadow: inset 0 -3px 0 var(--primary); }
.legend-closed { background: #efece6; }
.legend-busy { background: repeating-linear-gradient(135deg, #f3f0ea, #f3f0ea 3px, #e4e0d8 3px, #e4e0d8 6px); }
.legend-selected { background: var(--primary); border-color: var(--primary); }
.legend-conflict { background: var(--danger); border-color: var(--danger); }
.slot-board { display: grid; gap: 8px; overflow-x: auto; padding-bottom: 2px; }
.slot-band { display: grid; grid-template-columns: 44px minmax(560px, 1fr); gap: 8px; align-items: stretch; }
.slot-band__label { display: grid; align-content: center; }
.slot-band__label strong, .slot-band__label small { display: block; }
.slot-band__label strong { color: var(--ink); font-size: 12px; font-weight: 600; }
.slot-band__label small { color: var(--muted); font: 11px var(--mono); }
.slot-band__grid { display: grid; grid-template-columns: repeat(12, minmax(44px, 1fr)); gap: 4px; }
.slot { display: grid; grid-template-columns: 1fr auto; grid-template-rows: auto 3px; gap: 5px 4px; min-width: 0; min-height: 44px; padding: 6px 5px 5px; color: var(--body); background: var(--paper); border: 1px solid var(--line); border-radius: 6px; cursor: pointer; text-align: left; }
.slot:not(:disabled):hover { border-color: #9dbaae; }
.slot__time, .slot__count { font: 11px var(--mono); font-variant-numeric: tabular-nums; }
.slot--hour .slot__time { color: var(--ink); }
.slot__count { color: var(--primary); text-align: right; }
.slot__bar { grid-column: 1 / -1; background: var(--primary); border-radius: 1px; }
.slot--closed { color: var(--muted); background: #efece6; border-color: #e4e0d8; }
.slot--closed .slot__bar { background: #d5d0c6; }
.slot--closed .slot__count, .slot--closed .slot__time { color: var(--muted); }
.slot--occupied { color: var(--muted); background: repeating-linear-gradient(135deg, #f7f4ee, #f7f4ee 4px, #ece7df 4px, #ece7df 8px); }
.slot--occupied .slot__bar { background: #b7b1a6; }
.slot--occupied .slot__count { color: var(--muted); }
.slot--selected { color: var(--paper); background: var(--primary); border-color: var(--primary); }
.slot--selected .slot__bar { background: var(--paper); }
.slot--selected .slot__time, .slot--selected .slot__count { color: var(--paper); }
.slot--conflict { color: #fff; background: var(--danger); border-color: var(--danger); }
.slot--conflict .slot__bar { background: #fff; }
.slot--conflict .slot__time, .slot--conflict .slot__count { color: #fff; }
.slot:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
.slot:disabled { cursor: not-allowed; opacity: .55; }
.slot-message { margin: 12px 0 0; color: #9a3a30; font-size: 13px; }
.slot-help { margin: 12px 0 0; color: var(--muted); font-size: 12px; }
</style>
