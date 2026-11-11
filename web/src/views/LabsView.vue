<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http, { errorMessage } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { Lab, LabAvailability, LabRules } from '../types'
import { addDays, seatLabel, statusLabels, timeOnly, today } from '../utils'
import SlotGrid, { type SlotRange } from '../components/SlotGrid.vue'
import ReservationForm from '../components/ReservationForm.vue'
import EmptyState from '../components/EmptyState.vue'

const auth = useAuthStore()
const route = useRoute()
const labs = ref<Lab[]>([])
const selectedLabId = ref<number | null>(null)
const date = ref(addDays(today(), 1))
const rules = ref<LabRules | null>(null)
const availability = ref<LabAvailability | null>(null)
const loading = ref(false)
const error = ref('')
const selection = ref<SlotRange | null>(null)
const showForm = ref(false)
const success = ref('')
const gridMode = ref<'ROOM' | 'SEAT'>('ROOM')
const canReserve = computed(() => auth.roles.includes('STUDENT') || auth.roles.includes('TEACHER'))
const selectedLab = computed(() => labs.value.find((lab) => lab.labId === selectedLabId.value) || null)
const weekdayNames = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const headLabel = computed(() => {
  const value = new Date(`${date.value}T00:00:00`)
  return `${value.getMonth() + 1} 月 ${value.getDate()} 日 ${weekdayNames[value.getDay()]}`
})
const monthLabel = computed(() => {
  const value = new Date(`${date.value}T00:00:00`)
  return `${value.getFullYear()} 年 ${value.getMonth() + 1} 月`
})
const calendar = computed(() => {
  const value = new Date(`${date.value}T00:00:00`)
  const year = value.getFullYear()
  const month = value.getMonth()
  const pad = (new Date(year, month, 1).getDay() + 6) % 7
  const count = new Date(year, month + 1, 0).getDate()
  const cells: Array<{ day: number | null; iso: string }> = Array.from({ length: pad }, () => ({ day: null, iso: '' }))
  for (let day = 1; day <= count; day += 1) {
    const iso = `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
    cells.push({ day, iso })
  }
  return cells
})
const rangeText = computed(() => {
  if (!selection.value || !availability.value) return ''
  const start = availability.value.slots[selection.value.start]?.start
  const end = availability.value.slots[selection.value.end]?.end
  const minutes = (selection.value.end - selection.value.start + 1) * 30
  return `${start}–${end}，${minutes} 分钟`
})
const dayRule = computed(() => {
  if (!rules.value) return null
  const day = new Date(`${date.value}T00:00:00`).getDay() || 7
  return rules.value.weeklyRules.find((rule) => rule.dayOfWeek === day) || null
})
const openTime = computed(() => timeOnly(dayRule.value?.openTime || '00:00'))
const closeTime = computed(() => timeOnly(dayRule.value?.closeTime || '24:00', true))

async function loadLabs() {
  error.value = ''
  try {
    labs.value = await http.get<Lab[]>('/api/labs')
    if (!labs.value.length) return
    selectedLabId.value = labs.value[0].labId
    await loadAvailability()
    if (canReserve.value && route.query.draft === '1') showForm.value = true
  } catch (cause) { error.value = errorMessage(cause) }
}
async function loadAvailability() {
  if (!selectedLabId.value) return
  loading.value = true; error.value = ''; selection.value = null
  try {
    const [nextRules, nextAvailability] = await Promise.all([
      http.get<LabRules>(`/api/labs/${selectedLabId.value}/rules`),
      http.get<LabAvailability>(`/api/labs/${selectedLabId.value}/availability`, { params: { date: date.value } }),
    ])
    rules.value = nextRules
    availability.value = nextAvailability
  } catch (cause) { error.value = errorMessage(cause); availability.value = null; rules.value = null } finally { loading.value = false }
}
function pickDate(iso: string) {
  if (!iso || iso === date.value) return
  date.value = iso
  loadAvailability()
}
function shiftMonth(delta: number) {
  const current = new Date(`${date.value}T00:00:00`)
  const next = new Date(current.getFullYear(), current.getMonth() + delta, 1)
  const last = new Date(next.getFullYear(), next.getMonth() + 1, 0).getDate()
  next.setDate(Math.min(current.getDate(), last))
  const iso = `${next.getFullYear()}-${String(next.getMonth() + 1).padStart(2, '0')}-${String(next.getDate()).padStart(2, '0')}`
  date.value = iso
  loadAvailability()
}
function onReserved() {
  showForm.value = false
  success.value = '预约已提交，可在预约记录中查看正式分配结果。'
  loadAvailability()
}
onMounted(loadLabs)
</script>

<template>
  <div class="ruler">
    <aside class="rail">
      <div v-if="selectedLab">
        <h1>{{ selectedLab.name }}</h1>
        <div class="sub">{{ selectedLab.location }}<br>容量 {{ selectedLab.capacity }}，状态{{ statusLabels[selectedLab.status] }}<br>开放 {{ dayRule ? `${openTime}–${closeTime}` : '当日未开放' }}</div>
      </div>
      <h1 v-else>时间尺</h1>
      <label v-if="labs.length > 1" class="field"><span>实验室</span><select v-model.number="selectedLabId" @change="loadAvailability"><option v-for="lab in labs" :key="lab.labId" :value="lab.labId">{{ lab.code }} · {{ lab.name }}</option></select></label>
      <div>
        <div class="month">
          <span class="sub">{{ monthLabel }}</span>
          <span class="month__nav">
            <button type="button" aria-label="上个月" @click="shiftMonth(-1)">上月</button>
            <button type="button" aria-label="下个月" @click="shiftMonth(1)">下月</button>
          </span>
        </div>
        <div class="cal" role="group" aria-label="选择日期">
          <span v-for="name in ['一', '二', '三', '四', '五', '六', '日']" :key="name" class="wd">{{ name }}</span>
          <button v-for="(cell, index) in calendar" :key="`${cell.iso || 'pad'}-${index}`" type="button" :class="{ on: cell.iso === date, other: !cell.day }" :disabled="!cell.day" @click="pickDate(cell.iso)">{{ cell.day || '' }}</button>
        </div>
      </div>
      <div class="seg" role="group" aria-label="查看方式">
        <button type="button" :aria-pressed="gridMode === 'ROOM'" @click="gridMode = 'ROOM'">整室</button>
        <button type="button" :aria-pressed="gridMode === 'SEAT'" @click="gridMode = 'SEAT'">工位</button>
      </div>
      <div v-if="gridMode === 'SEAT' && availability?.seats.length" class="seats">
        <div class="seat-row">
          <span v-for="seat in availability.seats" :key="seat.seatId" class="seat" :title="`${seat.seatCode} · ${seat.zoneCode} · ${seat.specLevel} · ${seat.nearWindow ? '靠窗' : '不靠窗'} · ${statusLabels[seat.status]}`">{{ seatLabel(seat.seatCode, selectedLab?.code) }}</span>
        </div>
        <p class="seat-note">工位余量显示在开放格右侧。具体工位在提交后分配，这里只供查看。</p>
      </div>
      <p v-else-if="gridMode === 'SEAT' && availability" class="seat-note">该实验室无工位数据。</p>
      <div v-if="rules" class="rules">
        <p>{{ dayRule ? `当日预约时长 ${dayRule.minDurationMinutes}–${dayRule.maxDurationMinutes} 分钟。` : '当日未配置开放规则。' }}</p>
        <p v-if="dayRule">提前 {{ dayRule.advanceMinDays }}–{{ dayRule.advanceMaxDays }} 天。</p>
        <p>开始前 {{ rules.cancellation.freeBeforeHours }} 小时可免费取消。</p>
        <p>签到窗口开始前 {{ rules.checkin.beforeMinutes }} 分钟，宽限 {{ rules.checkin.graceMinutes }} 分钟。</p>
      </div>
      <div v-if="canReserve && labs.length">
        <div class="range" :class="{ active: Boolean(selection) }">{{ selection ? rangeText : '在开放格上点选一段连续时段' }}</div>
        <button class="go" type="button" @click="showForm = true">{{ selection ? '提交预约' : '填写预约' }}</button>
      </div>
    </aside>
    <section class="ruler-main">
      <div class="main-head"><b>{{ headLabel }}</b><span>每格 30 分钟</span></div>
      <div v-if="success" class="success-banner ruler-banner" role="status">{{ success }}</div>
      <div v-if="error" class="error-banner ruler-banner" role="alert">{{ error }} <button class="button button--small" @click="selectedLabId ? loadAvailability() : loadLabs()">重试</button></div>
      <div v-if="loading" class="loading-line">正在读取开放规则与占用位图</div>
      <EmptyState v-else-if="!error && !labs.length" title="暂无实验室" description="实验室资源尚未配置。" />
      <SlotGrid v-else-if="availability && selectedLab" v-model="selection" :slots="availability.slots" :mode="gridMode" :date="date" :disabled="availability.labStatus !== 'ACTIVE' || !dayRule" :open-time="openTime" :close-time="closeTime" />
    </section>
    <el-dialog v-if="canReserve" v-model="showForm" title="新建预约" width="760px" destroy-on-close>
      <ReservationForm :labs="labs.filter((lab) => lab.status === 'ACTIVE')" :initial-lab-id="selectedLabId" :initial-date="date" :selected-range="selection" @submitted="onReserved" />
    </el-dialog>
  </div>
</template>

<style scoped>
.ruler { display: grid; grid-template-columns: 280px minmax(0, 1fr); height: calc(100dvh - 48px); min-height: 0; background: var(--paper); }
.rail { display: flex; flex-direction: column; gap: 24px; min-height: 0; padding: 24px 20px; overflow: auto; background: var(--paper); border-right: 1px solid var(--line); }
.rail h1 { margin: 0; font-size: 18px; font-weight: 600; line-height: 1.3; }
.sub { margin-top: 4px; color: var(--muted); font-size: 13px; line-height: 1.5; }
.month { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.month__nav { display: flex; gap: 8px; }
.month__nav button, .hints { appearance: none; padding: 0; color: var(--muted); background: none; border: 0; border-radius: 0; cursor: pointer; font-size: 12px; }
.cal { display: grid; grid-template-columns: repeat(7, 1fr); gap: 2px; text-align: center; }
.wd { padding: 4px 0; color: var(--faint); font-size: 12px; }
.cal button { appearance: none; display: flex; align-items: center; justify-content: center; height: 28px; color: var(--ink); background: none; border: 0; border-radius: 0; cursor: pointer; font-size: 12px; }
.cal button.on { background: var(--ink); color: #fff; }
.cal button.other { color: transparent; cursor: default; }
.seg { display: flex; flex-shrink: 0; border: 1px solid var(--line); }
.seg button { appearance: none; flex: 1; height: 36px; color: var(--muted); background: var(--paper); border: 0; border-radius: 0; cursor: pointer; font-size: 14px; }
.seg button[aria-pressed="true"] { background: var(--ink); color: #fff; }
.seats { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.seat-row { display: flex; flex-wrap: wrap; gap: 4px; min-width: 0; max-width: 100%; }
.seat { display: flex; align-items: center; justify-content: center; box-sizing: border-box; max-width: 100%; min-width: 32px; min-height: 28px; padding: 4px 6px; color: var(--muted); background: var(--paper); border: 1px solid var(--line); border-radius: 0; font-family: var(--mono); font-size: 12px; font-variant-numeric: tabular-nums; line-height: 1.2; text-align: center; overflow-wrap: anywhere; }
.seat-note, .rules { margin: 0; color: var(--muted); font-size: 14px; line-height: 1.6; }
.seat-note { font-size: 12px; }
.rules p { margin: 0; }
.rules p + p { margin-top: 4px; }
.range { min-height: 24px; color: var(--muted); font-size: 14px; }
.range.active { color: var(--ink); font-weight: 500; }
.go { appearance: none; width: 100%; height: 44px; margin-top: 12px; color: #fff; background: var(--ink); border: 0; border-radius: 0; cursor: pointer; font-size: 15px; font-weight: 500; }
.ruler-main { display: flex; flex-direction: column; min-width: 0; min-height: 0; background: var(--paper); }
.main-head { display: flex; align-items: center; justify-content: space-between; height: 48px; flex: 0 0 auto; padding: 0 24px; border-bottom: 1px solid var(--line); color: var(--muted); font-size: 14px; }
.main-head b { color: var(--ink); font-weight: 500; }
.ruler-banner { margin: 0; border-left: 0; border-right: 0; }
@media (max-width: 800px) {
  .ruler { grid-template-columns: 1fr; height: auto; min-height: calc(100dvh - 48px); }
  .rail { max-height: 280px; border-right: 0; border-bottom: 1px solid var(--line); }
  .ruler-main { min-height: 70vh; }
}
</style>
