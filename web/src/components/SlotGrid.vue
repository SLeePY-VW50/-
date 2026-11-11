<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { SlotAvailability } from '../types'
import { today } from '../utils'

export interface SlotRange { start: number; end: number }

const props = withDefaults(defineProps<{
  slots: SlotAvailability[]
  modelValue?: SlotRange | null
  mode?: 'ROOM' | 'SEAT'
  disabled?: boolean
  openTime?: string
  closeTime?: string
  date?: string
}>(), { modelValue: null, mode: 'ROOM', disabled: false, openTime: '00:00', closeTime: '24:00', date: '' })

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
  { name: '凌晨', range: '00:00–06:00' },
  { name: '上午', range: '06:00–12:00' },
  { name: '下午', range: '12:00–18:00' },
  { name: '晚间', range: '18:00–24:00' },
]
const now = ref({ period: -1, top: '0%', label: '' })
let clock: ReturnType<typeof setInterval> | undefined
function placeNow() {
  if (!props.date || props.date !== today()) {
    now.value = { period: -1, top: '0%', label: '' }
    return
  }
  const current = new Date()
  const mins = current.getHours() * 60 + current.getMinutes()
  const period = Math.min(3, Math.floor(mins / 360))
  const fraction = (mins % 360) / 360
  now.value = {
    period,
    top: `calc(48px + (100% - 48px) * ${fraction})`,
    label: `${String(current.getHours()).padStart(2, '0')}:${String(current.getMinutes()).padStart(2, '0')}`,
  }
}
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
    'cell--closed': !isOpen(slot.index),
    'cell--occupied': isOpen(slot.index) && isOccupied(slot),
    'cell--selected': selected,
    'cell--conflict': conflict.value.includes(slot.index),
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
  const offsets: Record<string, number> = { ArrowUp: -1, ArrowDown: 1, ArrowLeft: -12, ArrowRight: 12 }
  const offset = offsets[event.key]
  if (!offset) return
  event.preventDefault()
  const next = Math.max(0, Math.min(props.slots.length - 1, index + offset))
  buttons.value[next]?.focus()
}
watch(() => props.date, placeNow)
onMounted(() => {
  placeNow()
  clock = setInterval(placeNow, 30_000)
})
onBeforeUnmount(() => { if (clock) clearInterval(clock) })
</script>

<template>
  <div class="slot-grid-wrap">
    <p v-if="message" class="slot-message" role="alert">{{ message }}</p>
    <div v-if="slots.length" class="periods" role="group" aria-label="48格预约时间尺，每格30分钟。上下键在同一时段内移动，左右键切换凌晨、上午、下午、晚间">
      <div v-for="(band, bandIndex) in bands" :key="band.range || band.name" class="period">
        <div class="period-h"><b>{{ band.name }}</b>{{ band.range }}</div>
        <div class="slots">
          <button
            v-for="slot in band.slots"
            :key="slot.index"
            :ref="(element) => { if (element) buttons[slot.index] = element as HTMLButtonElement }"
            type="button"
            class="cell"
            :class="stateClass(slot)"
            :disabled="disabled"
            :aria-pressed="Boolean(modelValue && slot.index >= modelValue.start && slot.index <= modelValue.end)"
            :aria-label="`${slot.start}至${slot.end}，${!isOpen(slot.index) ? '未开放' : isOccupied(slot) ? '已占用' : '可预约'}${mode === 'SEAT' && isOpen(slot.index) ? `，余${slot.availableSeatCount}个工位` : ''}`"
            :title="`${slot.start}–${slot.end} · ${!isOpen(slot.index) ? '未开放' : mode === 'SEAT' ? `余${slot.availableSeatCount}工位` : slot.roomOccupied ? '整室占用' : '整室可用'}`"
            @click="selectSlot(slot.index)"
            @keydown="moveFocus($event, slot.index)"
          >
            <span v-if="isOpen(slot.index)">{{ slot.start }}</span>
            <span v-if="mode === 'SEAT' && isOpen(slot.index) && !isOccupied(slot)" class="cell__count">{{ slot.availableSeatCount }}</span>
          </button>
        </div>
        <div v-if="now.period === bandIndex" class="now-line" :style="{ top: now.top }"><span>{{ now.label }}</span></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.slot-grid-wrap { display: flex; flex-direction: column; flex: 1; min-width: 0; min-height: 0; }
.slot-message { margin: 0; padding: 8px 24px; color: var(--ink); background: var(--paper); border-bottom: 1px solid var(--line); font-size: 13px; }
</style>
