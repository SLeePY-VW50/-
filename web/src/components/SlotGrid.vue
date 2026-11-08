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
      <span><i class="legend-open" />开放</span><span><i class="legend-busy" />占用</span><span><i class="legend-selected" />选中</span><span><i class="legend-conflict" />冲突</span>
    </div>
    <div v-if="slots.length" class="slot-grid" role="group" aria-label="48格预约时间尺，每格30分钟">
      <button
        v-for="slot in slots"
        :key="slot.index"
        :ref="(element) => { if (element) buttons[slot.index] = element as HTMLButtonElement }"
        type="button"
        class="slot"
        :class="stateClass(slot)"
        :disabled="disabled"
        :aria-pressed="Boolean(modelValue && slot.index >= modelValue.start && slot.index <= modelValue.end)"
        :aria-label="`${slot.start}至${slot.end}，${!isOpen(slot.index) ? '未开放' : isOccupied(slot) ? '已占用' : '可预约'}`"
        :title="`${slot.start}–${slot.end} · ${mode === 'SEAT' ? `余${slot.availableSeatCount}工位` : slot.roomOccupied ? '整室占用' : '整室可用'}`"
        @click="selectSlot(slot.index)"
        @keydown="moveFocus($event, slot.index)"
      >
        <span class="slot__time">{{ slot.start }}</span>
        <span class="slot__bar" />
        <span class="slot__count">{{ mode === 'SEAT' ? slot.availableSeatCount : slot.roomOccupied ? '占' : '空' }}</span>
      </button>
    </div>
    <p v-if="message" class="slot-message" role="alert">{{ message }}</p>
    <p v-else class="slot-help">点击起止格选择连续1–8片；方向键移动焦点，每片30分钟。</p>
  </div>
</template>

<style scoped>
.slot-grid-wrap { min-width: 0; }
.slot-legend { display: flex; flex-wrap: wrap; gap: 17px; margin-bottom: 15px; color: var(--muted); font-size: 11px; }
.slot-legend span { display: inline-flex; align-items: center; gap: 6px; }
.slot-legend i { width: 11px; height: 11px; border: 1px solid #d5dbe6; border-radius: 3px; }
.legend-open { background: #fff; }
.legend-busy { background: #e3e7ed; }
.legend-selected { background: var(--primary); border-color: var(--primary) !important; }
.legend-conflict { background: var(--danger); border-color: var(--danger) !important; }
.slot-grid { display: grid; grid-template-columns: repeat(12, minmax(50px, 1fr)); gap: 6px; }
.slot { display: grid; grid-template-columns: 1fr auto; grid-template-rows: auto 5px; gap: 7px; min-width: 0; height: 55px; padding: 7px 6px; color: #465366; background: #fff; border: 1px solid #d8dfe9; border-radius: 7px; cursor: pointer; text-align: left; transition: transform .14s ease, border-color .14s ease, box-shadow .14s ease; }
.slot:not(:disabled):hover { z-index: 1; border-color: #9d9be2; box-shadow: 0 4px 12px rgb(57 57 120 / 10%); transform: translateY(-2px); }
.slot__time, .slot__count { font: 10px 'Cascadia Mono', Consolas, monospace; }
.slot__count { color: var(--green); text-align: right; }
.slot__bar { grid-column: 1 / -1; background: var(--green); border-radius: 2px; opacity: .55; }
.slot--closed { color: #a0a9b5; background: #f4f6f8; border-color: #e8ebf0; }
.slot--closed .slot__bar { background: #c5cdd7; }
.slot--closed .slot__count { color: #a0a9b5; }
.slot--occupied { color: #8993a1; background: repeating-linear-gradient(135deg, #eceff3, #eceff3 5px, #e7eaf0 5px, #e7eaf0 10px); border-color: #dfe3e9; }
.slot--occupied .slot__bar { background: #98a2b0; }
.slot--occupied .slot__count { color: #8993a1; }
.slot--selected { color: #fff; background: var(--primary); border-color: var(--primary); }
.slot--selected .slot__bar { background: #fff; opacity: .85; }
.slot--selected .slot__count { color: #fff; }
.slot--conflict { color: #fff; background: var(--danger); border-color: var(--danger); }
.slot--conflict .slot__bar, .slot--conflict .slot__count { background: #fff; color: #fff; }
.slot:focus-visible { outline: 3px solid rgb(56 103 232 / 20%); outline-offset: 2px; }
.slot:disabled { cursor: not-allowed; opacity: .62; }
.slot-message { margin: 12px 0 0; color: var(--danger); font-size: 12px; }
.slot-help { margin: 12px 0 0; color: var(--muted); font-size: 11px; }
@media (max-width: 1100px) { .slot-grid { grid-template-columns: repeat(8, minmax(50px, 1fr)); } }
@media (max-width: 640px) { .slot-grid { grid-template-columns: repeat(6, minmax(42px, 1fr)); gap: 5px; } .slot { height: 49px; padding: 6px 4px; } .slot__time, .slot__count { font-size: 9px; } }
</style>
