<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import http, { errorMessage } from '../api/http'
import { useDraftStore } from '../stores/draft'
import type { CreateReservationPayload, Lab, Reservation } from '../types'
import { addDays, today, timeOnly } from '../utils'
import type { SlotRange } from './SlotGrid.vue'

type FieldKey = 'labId' | 'date' | 'purpose' | 'attendeeCount' | 'startTime' | 'endTime' | 'windowStart' | 'windowEnd' | 'durationMinutes' | 'seatCount'

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
const fieldErrors = reactive<Partial<Record<FieldKey, string>>>({})
const draftStore = useDraftStore()
const fieldRefs = reactive<Partial<Record<FieldKey, HTMLElement | null>>>({})

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
  clearFieldError('startTime')
  clearFieldError('endTime')
}, { deep: true, immediate: true })

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

function clearFieldError(field: FieldKey) {
  if (fieldErrors[field]) delete fieldErrors[field]
}
function clearAllFieldErrors() {
  for (const key of Object.keys(fieldErrors) as FieldKey[]) delete fieldErrors[key]
}
function setFieldRef(field: FieldKey, element: unknown) {
  fieldRefs[field] = (element as HTMLElement | null) || null
}
function validate() {
  clearAllFieldErrors()
  if (!form.labId) fieldErrors.labId = '请选择实验室'
  if (!form.date) fieldErrors.date = '请选择使用日期'
  if (!form.purpose.trim()) fieldErrors.purpose = '请填写使用用途'
  else if (form.purpose.trim().length < 2) fieldErrors.purpose = '用途至少填写 2 个字'
  if (!form.attendeeCount || form.attendeeCount < 1) fieldErrors.attendeeCount = '使用人数至少为 1'
  if (form.reservationMode === 'SEAT' && form.seatCount !== form.attendeeCount) {
    fieldErrors.seatCount = '工位数必须与人数一致'
  }
  if (form.allocationMode === 'FIXED') {
    const start = startOptions.indexOf(form.startTime)
    const end = endOptions.indexOf(form.endTime)
    if (start < 0) fieldErrors.startTime = '请选择开始时间'
    if (end < 0) fieldErrors.endTime = '请选择结束时间'
    else if (start >= 0 && end < start) fieldErrors.endTime = '结束时间必须晚于开始时间'
    else if (start >= 0 && end - start + 1 > 8) fieldErrors.endTime = '固定预约一次最多选择 8 个时间片（4 小时）'
  } else {
    const windowStart = startOptions.indexOf(form.windowStart)
    const windowEnd = endOptions.indexOf(form.windowEnd)
    if (windowStart < 0) fieldErrors.windowStart = '请选择窗口开始时间'
    if (windowEnd < 0) fieldErrors.windowEnd = '请选择窗口结束时间'
    else if (windowStart >= 0 && windowEnd < windowStart) fieldErrors.windowEnd = '窗口结束必须晚于窗口开始'
    if (form.durationMinutes < 30 || form.durationMinutes > 240 || form.durationMinutes % 30 !== 0) {
      fieldErrors.durationMinutes = '时长须为 30–240 分钟，且为 30 分钟倍数'
    } else if (windowStart >= 0 && windowEnd >= windowStart) {
      const windowSlots = windowEnd - windowStart + 1
      const neededSlots = form.durationMinutes / 30
      if (neededSlots > windowSlots) fieldErrors.durationMinutes = '所需时长不能超过可调度窗口'
    }
  }
  const messages = Object.values(fieldErrors).filter(Boolean)
  return messages[0] || ''
}

async function focusFirstInvalid() {
  await nextTick()
  const order: FieldKey[] = ['labId', 'date', 'startTime', 'endTime', 'windowStart', 'windowEnd', 'durationMinutes', 'attendeeCount', 'seatCount', 'purpose']
  const first = order.find((field) => fieldErrors[field])
  const target = first ? fieldRefs[first] : null
  if (target && 'focus' in target) (target as HTMLElement).focus()
}

async function submit() {
  error.value = validate()
  success.value = ''
  if (error.value) {
    await focusFirstInvalid()
    return
  }
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
      <label class="field" :class="{ 'field--invalid': fieldErrors.labId }"><span>实验室</span><select :ref="(el) => setFieldRef('labId', el)" v-model.number="form.labId" data-testid="lab" required :aria-invalid="Boolean(fieldErrors.labId)" :aria-describedby="fieldErrors.labId ? 'error-labId' : undefined" @change="clearFieldError('labId')"><option :value="null" disabled>请选择实验室</option><option v-for="lab in labs" :key="lab.labId" :value="lab.labId">{{ lab.name }} · {{ lab.location }}</option></select><span v-if="fieldErrors.labId" id="error-labId" class="field-error">{{ fieldErrors.labId }}</span></label>
      <label class="field" :class="{ 'field--invalid': fieldErrors.date }"><span>使用日期</span><input :ref="(el) => setFieldRef('date', el)" v-model="form.date" data-testid="date" type="date" required :aria-invalid="Boolean(fieldErrors.date)" :aria-describedby="fieldErrors.date ? 'error-date' : undefined" @change="clearFieldError('date')"><span v-if="fieldErrors.date" id="error-date" class="field-error">{{ fieldErrors.date }}</span></label>
    </div>
    <div v-if="form.allocationMode === 'FIXED'" class="form-grid">
      <label class="field" :class="{ 'field--invalid': fieldErrors.startTime }"><span>开始时间</span><select :ref="(el) => setFieldRef('startTime', el)" v-model="form.startTime" data-testid="start-time" :aria-invalid="Boolean(fieldErrors.startTime)" :aria-describedby="fieldErrors.startTime ? 'error-startTime' : undefined" @change="clearFieldError('startTime')"><option v-for="time in startOptions" :key="time">{{ time }}</option></select><span v-if="fieldErrors.startTime" id="error-startTime" class="field-error">{{ fieldErrors.startTime }}</span></label>
      <label class="field" :class="{ 'field--invalid': fieldErrors.endTime }"><span>结束时间（支持 24:00）</span><select :ref="(el) => setFieldRef('endTime', el)" v-model="form.endTime" data-testid="end-time" :aria-invalid="Boolean(fieldErrors.endTime)" :aria-describedby="fieldErrors.endTime ? 'error-endTime' : undefined" @change="clearFieldError('endTime')"><option v-for="time in endOptions" :key="time">{{ time }}</option></select><span v-if="fieldErrors.endTime" id="error-endTime" class="field-error">{{ fieldErrors.endTime }}</span></label>
    </div>
    <div v-else class="form-grid form-grid--three">
      <label class="field" :class="{ 'field--invalid': fieldErrors.windowStart }"><span>窗口开始</span><select :ref="(el) => setFieldRef('windowStart', el)" v-model="form.windowStart" data-testid="window-start" :aria-invalid="Boolean(fieldErrors.windowStart)" :aria-describedby="fieldErrors.windowStart ? 'error-windowStart' : undefined" @change="clearFieldError('windowStart')"><option v-for="time in startOptions" :key="time">{{ time }}</option></select><span v-if="fieldErrors.windowStart" id="error-windowStart" class="field-error">{{ fieldErrors.windowStart }}</span></label>
      <label class="field" :class="{ 'field--invalid': fieldErrors.windowEnd }"><span>窗口结束</span><select :ref="(el) => setFieldRef('windowEnd', el)" v-model="form.windowEnd" data-testid="window-end" :aria-invalid="Boolean(fieldErrors.windowEnd)" :aria-describedby="fieldErrors.windowEnd ? 'error-windowEnd' : undefined" @change="clearFieldError('windowEnd')"><option v-for="time in endOptions" :key="time">{{ time }}</option></select><span v-if="fieldErrors.windowEnd" id="error-windowEnd" class="field-error">{{ fieldErrors.windowEnd }}</span></label>
      <label class="field" :class="{ 'field--invalid': fieldErrors.durationMinutes }"><span>所需时长</span><select :ref="(el) => setFieldRef('durationMinutes', el)" v-model.number="form.durationMinutes" data-testid="duration" :aria-invalid="Boolean(fieldErrors.durationMinutes)" :aria-describedby="fieldErrors.durationMinutes ? 'error-durationMinutes' : undefined" @change="clearFieldError('durationMinutes')"><option v-for="minutes in 8" :key="minutes" :value="minutes * 30">{{ minutes * 30 }} 分钟</option></select><span v-if="fieldErrors.durationMinutes" id="error-durationMinutes" class="field-error">{{ fieldErrors.durationMinutes }}</span></label>
    </div>
    <div class="mode-switch mode-switch--small" role="group" aria-label="预约方式">
      <label :class="{ active: form.reservationMode === 'ROOM' }"><input v-model="form.reservationMode" data-testid="mode-room" type="radio" value="ROOM">整室预约</label>
      <label :class="{ active: form.reservationMode === 'SEAT' }"><input v-model="form.reservationMode" data-testid="mode-seat" type="radio" value="SEAT">工位预约</label>
    </div>
    <div class="form-grid form-grid--three">
      <label class="field" :class="{ 'field--invalid': fieldErrors.attendeeCount }"><span>使用人数</span><input :ref="(el) => setFieldRef('attendeeCount', el)" v-model.number="form.attendeeCount" data-testid="attendee-count" type="number" min="1" required :aria-invalid="Boolean(fieldErrors.attendeeCount)" :aria-describedby="fieldErrors.attendeeCount ? 'error-attendeeCount' : undefined" @input="clearFieldError('attendeeCount')"><span v-if="fieldErrors.attendeeCount" id="error-attendeeCount" class="field-error">{{ fieldErrors.attendeeCount }}</span></label>
      <label v-if="form.reservationMode === 'SEAT'" class="field" :class="{ 'field--invalid': fieldErrors.seatCount }"><span>工位数（与人数一致）</span><input :ref="(el) => setFieldRef('seatCount', el)" v-model.number="form.seatCount" data-testid="seat-count" type="number" min="1" readonly :aria-invalid="Boolean(fieldErrors.seatCount)" :aria-describedby="fieldErrors.seatCount ? 'error-seatCount' : undefined"><span v-if="fieldErrors.seatCount" id="error-seatCount" class="field-error">{{ fieldErrors.seatCount }}</span></label>
      <label class="field"><span>最低配置</span><select v-model="form.minSpecLevel" data-testid="spec"><option value="">不限</option><option value="STANDARD">标准</option><option value="HIGH">高配</option><option value="GPU">GPU</option></select></label>
      <label class="field"><span>偏好区域</span><input v-model="form.zoneCode" data-testid="zone" placeholder="如 NORTH，可留空"></label>
      <label class="field"><span>靠窗偏好</span><select v-model="form.nearWindow"><option value="">不限</option><option value="true">靠窗</option><option value="false">不靠窗</option></select></label>
    </div>
    <label class="field" :class="{ 'field--invalid': fieldErrors.purpose }"><span>使用用途</span><textarea :ref="(el) => setFieldRef('purpose', el)" v-model="form.purpose" data-testid="purpose" rows="3" maxlength="500" required placeholder="说明课程、项目或实验用途" :aria-invalid="Boolean(fieldErrors.purpose)" :aria-describedby="fieldErrors.purpose ? 'error-purpose' : undefined" @input="clearFieldError('purpose')" /><span v-if="fieldErrors.purpose" id="error-purpose" class="field-error">{{ fieldErrors.purpose }}</span></label>
    <div v-if="error" class="form-alert form-alert--error" role="alert">{{ error }}</div>
    <div v-if="success" class="form-alert form-alert--success" role="status">{{ success }}</div>
    <div class="form-actions"><span>提交前请核对日期、时段和人数。</span><button class="button button--primary" type="submit" :disabled="submitting">{{ submitting ? '正在提交…' : '确认提交预约' }}</button></div>
  </form>
</template>

<style scoped>
.reservation-form { display: grid; gap: 19px; }
.mode-switch { display: flex; gap: 4px; padding: 4px; background: #f7f4ee; border: 1px solid var(--line); border-radius: 8px; }
.mode-switch label { flex: 1; min-height: 40px; padding: 8px 12px; color: var(--body); border: 0; border-radius: 6px; cursor: pointer; text-align: center; font-size: 14px; font-weight: 500; }
.mode-switch label.active { color: var(--primary); background: var(--paper); font-weight: 600; }
.mode-switch input { position: absolute; opacity: 0; pointer-events: none; }
.mode-switch label:focus-within { outline: 2px solid var(--primary); outline-offset: 2px; }
.mode-switch--small label { padding: 8px 12px; }
.form-note { margin: -10px 0 0; color: var(--muted); font-size: 12px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.form-grid--three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.field { display: grid; gap: 6px; min-width: 0; color: var(--body); font-size: 13px; }
.field input, .field select, .field textarea { width: 100%; min-height: 40px; box-sizing: border-box; padding: 8px 12px; color: var(--ink); background: var(--paper); border: 1px solid var(--line); border-radius: 6px; outline: none; font: 14px -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', sans-serif; }
.field input:focus, .field select:focus, .field textarea:focus { border-color: var(--primary); box-shadow: 0 0 0 3px rgb(31 107 87 / 16%); }
.field--invalid input, .field--invalid select, .field--invalid textarea { border-color: #e4b7b1; background: #fdf6f5; }
.field--invalid input:focus, .field--invalid select:focus, .field--invalid textarea:focus { border-color: var(--danger); box-shadow: 0 0 0 3px rgb(196 71 58 / 16%); }
.field input[readonly] { color: var(--muted); background: #f3f0ea; }
.field textarea { resize: vertical; }
.field-error { color: #9a3a30; font-size: 12px; line-height: 1.4; }
.form-alert { padding: 11px 12px; border: 1px solid; border-radius: 6px; font-size: 13px; }
.form-alert--error { color: #9a3a30; background: #fdf6f5; border-color: #efd0cb; }
.form-alert--success { color: #185546; background: var(--primary-soft); border-color: #c9e3d8; }
.form-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-top: 17px; color: var(--muted); border-top: 1px solid var(--line); font-size: 12px; }
@media (max-width: 720px) { .form-grid, .form-grid--three { grid-template-columns: 1fr; } .form-actions { align-items: stretch; flex-direction: column; } .mode-switch label { padding-inline: 8px; } }
</style>
