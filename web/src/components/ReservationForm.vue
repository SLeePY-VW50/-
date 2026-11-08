<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import http, { errorMessage } from '../api/http'
import { useDraftStore } from '../stores/draft'
import type { CreateReservationPayload, Lab, Reservation } from '../types'
import { addDays, today, timeOnly } from '../utils'
import type { SlotRange } from './SlotGrid.vue'

const props = defineProps<{
  labs: Lab[]
  initialLabId?: number | null
  initialDate?: string
  selectedRange?: SlotRange | null
}>()
const emit = defineEmits<{ submitted: [reservation: Reservation] }>()

const startOptions = Array.from({ length: 48 }, (_, index) => `${String(Math.floor(index / 2)).padStart(2, '0')}:${index % 2 ? '30' : '00'}`)
const endOptions = [...startOptions.slice(1), '24:00']
const form = reactive({
  allocationMode: 'FIXED' as 'FIXED' | 'SCHEDULABLE',
  labId: props.initialLabId ?? null as number | null,
  date: props.initialDate || addDays(today(), 1),
  startTime: '09:00',
  endTime: '10:00',
  windowStart: '08:00',
  windowEnd: '12:00',
  durationMinutes: 60,
  reservationMode: 'ROOM' as 'ROOM' | 'SEAT',
  attendeeCount: 1,
  seatCount: 1,
  purpose: '',
  zoneCode: '',
  nearWindow: '' as '' | 'true' | 'false',
  minSpecLevel: '',
})
const submitting = ref(false)
const error = ref('')
const success = ref('')
const draftStore = useDraftStore()

watch(() => form.attendeeCount, (count) => {
  if (form.reservationMode === 'SEAT') form.seatCount = Number(count) || 1
})
watch(() => form.reservationMode, (mode) => {
  if (mode === 'SEAT') form.seatCount = Number(form.attendeeCount) || 1
})
watch(() => props.selectedRange, (range) => {
  if (!range) return
  form.allocationMode = 'FIXED'
  form.startTime = startOptions[range.start]
  form.endTime = endOptions[range.end]
}, { deep: true })

onMounted(() => {
  const draft = draftStore.takeDraft()
  if (!draft) return
  form.allocationMode = 'FIXED'
  form.labId = draft.labId
  form.date = draft.date
  form.startTime = timeOnly(draft.startTime)
  form.endTime = timeOnly(draft.endTime, true)
  form.reservationMode = draft.reservationMode
  form.attendeeCount = draft.seatCount
  form.seatCount = draft.seatCount
  form.zoneCode = draft.preferences.zoneCode || ''
  form.nearWindow = draft.preferences.nearWindow == null ? '' : String(draft.preferences.nearWindow) as 'true' | 'false'
  form.minSpecLevel = draft.preferences.minSpecLevel || ''
})

function validate() {
  if (!form.labId || !form.date || !form.purpose.trim() || form.attendeeCount < 1) return '请完整填写实验室、日期、人数和用途'
  if (form.reservationMode === 'SEAT' && form.seatCount !== form.attendeeCount) return '工位预约的seatCount必须等于人数'
  if (form.allocationMode === 'FIXED') {
    const start = startOptions.indexOf(form.startTime)
    const end = endOptions.indexOf(form.endTime)
    if (start < 0 || end < 0 || end < start) return '结束时间必须晚于开始时间'
    if (end - start + 1 > 8) return '固定预约一次最多选择8个时间片'
  } else if (form.durationMinutes < 30 || form.durationMinutes > 240 || form.durationMinutes % 30 !== 0) {
    return '可调度时长必须为30到240分钟之间的30分钟倍数'
  }
  return ''
}

async function submit() {
  error.value = validate()
  success.value = ''
  if (error.value) return
  const fixed = form.allocationMode === 'FIXED'
  const payload: CreateReservationPayload = {
    allocationMode: form.allocationMode,
    labId: Number(form.labId),
    date: form.date,
    startTime: fixed ? form.startTime : null,
    endTime: fixed ? form.endTime : null,
    windowStart: fixed ? null : form.windowStart,
    windowEnd: fixed ? null : form.windowEnd,
    durationMinutes: fixed ? null : Number(form.durationMinutes),
    reservationMode: form.reservationMode,
    attendeeCount: Number(form.attendeeCount),
    seatCount: form.reservationMode === 'SEAT' ? Number(form.seatCount) : null,
    purpose: form.purpose.trim(),
    preferences: {
      zoneCode: form.zoneCode.trim() || null,
      nearWindow: form.nearWindow === '' ? null : form.nearWindow === 'true',
      minSpecLevel: form.minSpecLevel || null,
      allowedLabIds: [],
    },
  }
  submitting.value = true
  try {
    const reservation = await http.post<Reservation>('/api/reservations', payload)
    success.value = `预约 #${reservation.resvId} 已提交`
    emit('submitted', reservation)
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <form class="reservation-form" novalidate @submit.prevent="submit">
    <div class="mode-switch" role="group" aria-label="分配方式">
      <label :class="{ active: form.allocationMode === 'FIXED' }"><input v-model="form.allocationMode" data-testid="allocation-fixed" type="radio" value="FIXED">固定时段</label>
      <label :class="{ active: form.allocationMode === 'SCHEDULABLE' }"><input v-model="form.allocationMode" data-testid="allocation-schedulable" type="radio" value="SCHEDULABLE">可调度预约</label>
    </div>
    <p class="form-note">{{ form.allocationMode === 'FIXED' ? '固定预约立即锁定所选实验室与时段。' : '提交需求窗口，由排程算法确定实验室和正式时间。' }}</p>
    <div class="form-grid">
      <label class="field"><span>实验室</span><select v-model.number="form.labId" data-testid="lab" required><option :value="null" disabled>请选择实验室</option><option v-for="lab in labs" :key="lab.labId" :value="lab.labId">{{ lab.name }} · {{ lab.location }}</option></select></label>
      <label class="field"><span>使用日期</span><input v-model="form.date" data-testid="date" type="date" required></label>
    </div>
    <div v-if="form.allocationMode === 'FIXED'" class="form-grid">
      <label class="field"><span>开始时间</span><select v-model="form.startTime" data-testid="start-time"><option v-for="time in startOptions" :key="time">{{ time }}</option></select></label>
      <label class="field"><span>结束时间（支持24:00）</span><select v-model="form.endTime" data-testid="end-time"><option v-for="time in endOptions" :key="time">{{ time }}</option></select></label>
    </div>
    <div v-else class="form-grid form-grid--three">
      <label class="field"><span>窗口开始</span><select v-model="form.windowStart" data-testid="window-start"><option v-for="time in startOptions" :key="time">{{ time }}</option></select></label>
      <label class="field"><span>窗口结束</span><select v-model="form.windowEnd" data-testid="window-end"><option v-for="time in endOptions" :key="time">{{ time }}</option></select></label>
      <label class="field"><span>所需时长</span><select v-model.number="form.durationMinutes" data-testid="duration"><option v-for="minutes in 8" :key="minutes" :value="minutes * 30">{{ minutes * 30 }}分钟</option></select></label>
    </div>
    <div class="mode-switch mode-switch--small" role="group" aria-label="预约方式">
      <label :class="{ active: form.reservationMode === 'ROOM' }"><input v-model="form.reservationMode" data-testid="mode-room" type="radio" value="ROOM">整室 ROOM</label>
      <label :class="{ active: form.reservationMode === 'SEAT' }"><input v-model="form.reservationMode" data-testid="mode-seat" type="radio" value="SEAT">工位 SEAT</label>
    </div>
    <div class="form-grid form-grid--three">
      <label class="field"><span>使用人数</span><input v-model.number="form.attendeeCount" data-testid="attendee-count" type="number" min="1" required></label>
      <label v-if="form.reservationMode === 'SEAT'" class="field"><span>工位数（与人数一致）</span><input v-model.number="form.seatCount" data-testid="seat-count" type="number" min="1" readonly></label>
      <label class="field"><span>最低配置</span><select v-model="form.minSpecLevel" data-testid="spec"><option value="">不限</option><option value="STANDARD">STANDARD</option><option value="HIGH">HIGH</option><option value="GPU">GPU</option></select></label>
      <label class="field"><span>偏好区域</span><input v-model="form.zoneCode" data-testid="zone" placeholder="如 NORTH，可留空"></label>
      <label class="field"><span>靠窗偏好</span><select v-model="form.nearWindow"><option value="">不限</option><option value="true">靠窗</option><option value="false">不靠窗</option></select></label>
    </div>
    <label class="field"><span>使用用途</span><textarea v-model="form.purpose" data-testid="purpose" rows="3" maxlength="500" required placeholder="说明课程、项目或实验用途" /></label>
    <div v-if="error" class="form-alert form-alert--error" role="alert">{{ error }}</div>
    <div v-if="success" class="form-alert form-alert--success" role="status">{{ success }}</div>
    <div class="form-actions"><span>提交前请核对日期、时段和人数。</span><button class="button button--primary" type="submit" :disabled="submitting">{{ submitting ? '正在提交…' : '确认提交预约' }}</button></div>
  </form>
</template>

<style scoped>
.reservation-form { display: grid; gap: 19px; }
.mode-switch { display: flex; gap: 5px; padding: 5px; background: #f8f9fa; border: 1px solid #f3f4f6; border-radius: 9999px; }
.mode-switch label { flex: 1; padding: 10px 16px; color: var(--muted); border: 0; border-radius: 8px; cursor: pointer; text-align: center; font-size: 14px; font-weight: 500; transition: color .16s ease, background .16s ease; }
.mode-switch label.active { color: var(--primary); background: #fff; box-shadow: 0 1px 5px rgb(36 57 91 / 9%); font-weight: 600; }
.mode-switch input { position: absolute; opacity: 0; pointer-events: none; }
.mode-switch label:focus-within { outline: 3px solid rgb(56 103 232 / 18%); outline-offset: 2px; }
.mode-switch--small label { padding: 8px 12px; }
.form-note { margin: -10px 0 0; color: var(--muted); font-size: 12px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.form-grid--three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.field { display: grid; gap: 7px; min-width: 0; color: #53627a; font-size: 13px; }
.field input, .field select, .field textarea { width: 100%; min-height: 40px; box-sizing: border-box; padding: 10px 14px; color: var(--ink); background: #fff; border: 1px solid var(--line); border-radius: 8px; outline: none; font: 13px -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', sans-serif; transition: border-color .16s ease, box-shadow .16s ease; }
.field input:focus, .field select:focus, .field textarea:focus { border-color: var(--primary); box-shadow: 0 0 0 3px rgb(56 103 232 / 10%); }
.field input[readonly] { color: var(--muted); background: #f4f6fa; }
.field textarea { resize: vertical; }
.form-alert { padding: 11px 13px; border: 1px solid; border-radius: 9px; font-size: 13px; }
.form-alert--error { color: #ac3c4c; background: #fff3f5; border-color: #f0ccd2; }
.form-alert--success { color: #126e53; background: #eef9f4; border-color: #c9e9da; }
.form-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-top: 17px; color: var(--muted); border-top: 1px solid var(--line); font-size: 12px; }
@media (max-width: 720px) { .form-grid, .form-grid--three { grid-template-columns: 1fr; } .form-actions { align-items: stretch; flex-direction: column; } .mode-switch label { padding-inline: 8px; } }
</style>
