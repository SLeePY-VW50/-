<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { SlotAvailability } from '../types'
import { DEFAULT_MAX_PERIODS } from '../periods'
import { today } from '../utils'

export interface SlotRange { start: number; end: number }

const props = withDefaults(defineProps<{
  slots: SlotAvailability[]
  modelValue?: SlotRange | null
  mode?: 'ROOM' | 'SEAT'
  disabled?: boolean
  date?: string
  maxPeriods?: number
}>(), { modelValue: null, mode: 'ROOM', disabled: false, date: '', maxPeriods: DEFAULT_MAX_PERIODS })

const emit = defineEmits<{ 'update:modelValue': [value: SlotRange | null] }>()
const conflict = ref<number[]>([])
const message = ref('')
const buttons = ref<HTMLButtonElement[]>([])

const sessionName: Record<string, string> = { MORNING: '上午', AFTERNOON: '下午', EVENING: '晚上' }
const sessionOrder = ['MORNING', 'AFTERNOON', 'EVENING']

function minute(value: string) {
  const [hours, minutes] = value.split(':').map(Number)
  return hours * 60 + minutes
}

const bands = computed(() => {
  const grouped = new Map<string, SlotAvailability[]>()
  for (const slot of props.slots) {
    const key = slot.session || 'MORNING'
    const items = grouped.get(key) ?? []
    items.push(slot)
    grouped.set(key, items)
  }
  return sessionOrder.filter((key) => grouped.has(key)).map((key) => {
    const slots = grouped.get(key) ?? []
    return {
      key,
      name: sessionName[key] || key,
      range: slots.length ? `${slots[0].start}–${slots[slots.length - 1].end}` : '',
      slots,
    }
  })
})

const now = ref({ period: -1, top: '0%', label: '' })
let clock: ReturnType<typeof setInterval> | undefined
function placeNow() {
  if (!props.date || props.date !== today()) {
    now.value = { period: -1, top: '0%', label: '' }
    return
  }
  const current = new Date()
  const mins = current.getHours() * 60 + current.getMinutes()
  const period = bands.value.findIndex((band) => {
    if (!band.slots.length) return false
    return mins >= minute(band.slots[0].start) && mins < minute(band.slots[band.slots.length - 1].end)
  })
  if (period < 0) {
    now.value = { period: -1, top: '0%', label: '' }
    return
  }
  const band = bands.value[period]
  const start = minute(band.slots[0].start)
  const end = minute(band.slots[band.slots.length - 1].end)
  const fraction = end <= start ? 0 : (mins - start) / (end - start)
  now.value = {
    period,
    top: `calc(48px + (100% - 48px) * ${fraction})`,
    label: `${String(current.getHours()).padStart(2, '0')}:${String(current.getMinutes()).padStart(2, '0')}`,
  }
}

function isOpen(slot: SlotAvailability) {
  return slot.bookable
}

function isOccupied(slot: SlotAvailability) {
  return props.mode === 'ROOM' ? slot.roomOccupied : slot.availableSeatCount <= 0
}

function stateClass(slot: SlotAvailability) {
  const selected = props.modelValue && slot.index >= props.modelValue.start && slot.index <= props.modelValue.end
  return {
    'cell--closed': !isOpen(slot),
    'cell--occupied': isOpen(slot) && isOccupied(slot),
    'cell--selected': selected,
    'cell--conflict': conflict.value.includes(slot.index),
  }
}

function selectSlot(index: number) {
  conflict.value = []
  message.value = ''
  const slot = props.slots.find((item) => item.index === index)
  if (props.disabled || !slot || !isOpen(slot)) {
    message.value = '该节次不在开放范围内'
    return
  }
  const start = props.modelValue?.start ?? index
  const from = Math.min(start, index)
  const to = Math.max(start, index)
  if (to - from + 1 > props.maxPeriods) {
    message.value = `连续选择最多${props.maxPeriods}节`
    conflict.value = Array.from({ length: to - from + 1 }, (_, offset) => from + offset)
    return
  }
  const blocked = props.slots.filter((item) => item.index >= from && item.index <= to && (!isOpen(item) || isOccupied(item)))
  if (blocked.length) {
    conflict.value = blocked.map((item) => item.index)
    message.value = '所选连续节次包含未开放或已占用的节，请重新选择'
    return
  }
  emit('update:modelValue', { start: from, end: to })
}

function moveFocus(event: KeyboardEvent, index: number) {
  const bandIndex = bands.value.findIndex((band) => band.slots.some((slot) => slot.index === index))
  const band = bands.value[bandIndex]
  if (!band) return
  const row = band.slots.findIndex((slot) => slot.index === index)
  const target = event.key === 'ArrowUp' || event.key === 'ArrowDown'
    ? band.slots[row + (event.key === 'ArrowUp' ? -1 : 1)]
    : event.key === 'ArrowLeft' || event.key === 'ArrowRight'
      ? bands.value[bandIndex + (event.key === 'ArrowLeft' ? -1 : 1)]?.slots[row]
      : undefined
  if (!target) return
  event.preventDefault()
  buttons.value[target.index]?.focus()
}
watch(() => props.date, placeNow)
watch(bands, placeNow)
watch(() => props.modelValue, (value) => {
  if (!value) {
    conflict.value = []
    message.value = ''
  }
})
onMounted(() => {
  placeNow()
  clock = setInterval(placeNow, 30_000)
})
onBeforeUnmount(() => { if (clock) clearInterval(clock) })
</script>

<template>
  <div class="slot-grid-wrap">
    <p v-if="message" class="slot-message" role="alert">{{ message }}</p>
    <div v-if="slots.length" class="periods" role="group" aria-label="按节次预约。上下键在同一时段内移动，左右键切换上午、下午、晚上">
      <div v-for="(band, bandIndex) in bands" :key="band.key" class="period">
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
            :aria-label="`第${slot.periodNo}节 ${slot.start}至${slot.end}，${!isOpen(slot) ? '未开放' : isOccupied(slot) ? '已占用' : '可预约'}${mode === 'SEAT' && isOpen(slot) ? `，余${slot.availableSeatCount}个工位` : ''}`"
            :title="`第${slot.periodNo}节 ${slot.start}–${slot.end} · ${!isOpen(slot) ? '未开放' : mode === 'SEAT' ? `余${slot.availableSeatCount}工位` : slot.roomOccupied ? '整室占用' : '整室可用'}`"
            @click="selectSlot(slot.index)"
            @keydown="moveFocus($event, slot.index)"
          >
            <span>第{{ slot.periodNo }}节 {{ slot.start }}</span>
            <span v-if="mode === 'SEAT' && isOpen(slot) && !isOccupied(slot)" class="cell__count">{{ slot.availableSeatCount }}</span>
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
