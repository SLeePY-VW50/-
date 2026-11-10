<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http, { errorMessage } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { Lab, LabAvailability, LabRules } from '../types'
import { addDays, statusLabels, timeOnly, today } from '../utils'
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
function onReserved() {
  showForm.value = false
  success.value = '预约已提交，可在预约记录中查看正式分配结果。'
  loadAvailability()
}
onMounted(loadLabs)
</script>

<template>
  <div class="page">
    <header class="page-header"><div><span class="eyebrow">实时占用 · 30 分钟一格</span><h1>实验室与工位时间尺</h1><p>选择日期与实验室，查看真实开放规则和每30分钟的占用情况。</p></div><button v-if="canReserve && labs.length" class="button button--primary" type="button" @click="showForm = true">新建预约</button></header>
    <div v-if="success" class="success-banner" role="status">{{ success }}</div>
    <div v-if="error" class="error-banner" role="alert">{{ error }} <button class="button button--small" @click="selectedLabId ? loadAvailability() : loadLabs()">重试</button></div>
    <section class="panel">
      <div class="toolbar">
        <label class="field"><span>实验室</span><select v-model.number="selectedLabId" @change="loadAvailability"><option v-for="lab in labs" :key="lab.labId" :value="lab.labId">{{ lab.code }} · {{ lab.name }}</option></select></label>
        <label class="field"><span>日期</span><input v-model="date" type="date" @change="loadAvailability"></label>
        <label class="field"><span>时间尺查看方式</span><select v-model="gridMode"><option value="ROOM">整室占用</option><option value="SEAT">工位余量</option></select></label>
        <span v-if="selectedLab" class="tag" :class="selectedLab.status === 'ACTIVE' ? 'tag--green' : 'tag--amber'">{{ statusLabels[selectedLab.status] }}</span>
      </div>
      <div v-if="loading" class="loading-line">正在读取开放规则与占用位图</div>
      <EmptyState v-else-if="!error && !labs.length" title="暂无实验室" description="实验室资源尚未配置。" />
      <template v-else-if="availability && selectedLab">
        <div class="lab-summary"><div><strong>{{ selectedLab.name }}</strong><span>{{ selectedLab.location }} · 容量 {{ selectedLab.capacity }}</span></div><div class="lab-summary__rule"><span>当日开放</span><strong class="mono">{{ dayRule ? `${openTime}–${closeTime}` : '未开放' }}</strong></div></div>
        <SlotGrid v-model="selection" :slots="availability.slots" :mode="gridMode" :disabled="availability.labStatus !== 'ACTIVE' || !dayRule" :open-time="openTime" :close-time="closeTime" />
        <div v-if="selection && canReserve" class="selection-action" role="status">
          <div class="selection-action__copy">
            <strong>已选时段，可直接预约</strong>
            <span>已选 <strong class="mono">{{ availability.slots[selection.start]?.start }}–{{ availability.slots[selection.end]?.end }}</strong>，共 {{ selection.end - selection.start + 1 }} 个时间片。</span>
          </div>
          <button class="button button--primary" type="button" @click="showForm = true">按此时段预约</button>
        </div>
      </template>
    </section>
    <div v-if="rules || availability" class="split-layout lab-details">
      <section class="panel"><div class="panel__header"><h2>开放与签到规则</h2></div><dl v-if="rules" class="detail-list"><dt>当日预约时长</dt><dd>{{ dayRule ? `${dayRule.minDurationMinutes}–${dayRule.maxDurationMinutes} 分钟` : '当日未配置开放规则' }}</dd><dt>提前预约范围</dt><dd>{{ dayRule ? `${dayRule.advanceMinDays}–${dayRule.advanceMaxDays} 天` : '—' }}</dd><dt>免费取消</dt><dd>开始前 {{ rules.cancellation.freeBeforeHours }} 小时</dd><dt>签到窗口</dt><dd>开始前 {{ rules.checkin.beforeMinutes }} 分钟，宽限 {{ rules.checkin.graceMinutes }} 分钟</dd></dl></section>
      <section class="panel"><div class="panel__header"><div><h2>工位状态</h2><span class="panel__subtitle">配置、区域与资源状态来自实时 availability</span></div></div><div v-if="availability?.seats.length" class="table-wrap"><table class="data-table"><thead><tr><th>工位</th><th>区域</th><th>配置</th><th>靠窗</th><th>状态</th></tr></thead><tbody><tr v-for="seat in availability.seats" :key="seat.seatId"><td class="mono">{{ seat.seatCode }}</td><td>{{ seat.zoneCode }}</td><td>{{ seat.specLevel }}</td><td>{{ seat.nearWindow ? '是' : '否' }}</td><td><span class="tag" :class="seat.status === 'ACTIVE' ? 'tag--green' : 'tag--amber'">{{ statusLabels[seat.status] }}</span></td></tr></tbody></table></div><EmptyState v-else title="该实验室无工位数据" /></section>
    </div>
    <el-dialog v-if="canReserve" v-model="showForm" title="新建预约" width="760px" destroy-on-close>
      <ReservationForm :labs="labs.filter((lab) => lab.status === 'ACTIVE')" :initial-lab-id="selectedLabId" :initial-date="date" :selected-range="selection" @submitted="onReserved" />
    </el-dialog>
  </div>
</template>

<style scoped>
.lab-summary { display: flex; justify-content: space-between; gap: 20px; margin-bottom: 16px; padding: 14px 16px; background: #f7f4ee; border: 1px solid var(--line); border-radius: 6px; }
.lab-summary strong, .lab-summary span { display: block; }
.lab-summary strong { font-size: 15px; }
.lab-summary span { margin-top: 5px; color: var(--muted); font-size: 12px; }
.lab-summary__rule { text-align: right; }
.lab-summary__rule strong { margin-top: 5px; color: var(--green); }
.selection-action { position: sticky; bottom: 16px; z-index: 5; display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 16px; padding: 12px 14px; color: var(--ink); background: var(--primary-soft); border: 1px solid #c9e3d8; border-radius: 6px; font-size: 13px; }
.selection-action__copy { display: grid; gap: 4px; min-width: 0; }
.selection-action__copy > strong { color: var(--primary); font-size: 13px; }
.selection-action__copy > span { color: var(--muted); font-size: 12px; }
.lab-details { grid-template-columns: minmax(260px, .65fr) minmax(0, 1.35fr); }
@media (max-width: 640px) { .lab-summary, .selection-action { align-items: stretch; flex-direction: column; } .lab-summary__rule { text-align: left; } .selection-action .button { width: 100%; } }
</style>
