<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import http, { errorMessage } from '../../api/http'
import type { Lab, LabAvailability, ResourceStatus } from '../../types'
import { statusLabels, today } from '../../utils'
import EmptyState from '../../components/EmptyState.vue'

const labs = ref<Lab[]>([])
const selectedLabId = ref<number | null>(null)
const availability = ref<LabAvailability | null>(null)
const labForm = reactive({ code: '', name: '', location: '', capacity: 1 })
const terminalForm = reactive({ labId: null as number | null, deviceName: '', model: '', terminalCode: '', initialSecret: '' })
const loading = ref(false)
const error = ref('')
const success = ref('')
const statuses: ResourceStatus[] = ['ACTIVE', 'MAINTENANCE', 'DISABLED']

async function loadLabs() {
  error.value = ''
  try {
    labs.value = await http.get<Lab[]>('/api/labs')
    if (!selectedLabId.value && labs.value.length) selectedLabId.value = labs.value[0].labId
    if (!terminalForm.labId && labs.value.length) terminalForm.labId = labs.value[0].labId
    if (selectedLabId.value) await loadSeats()
  } catch (cause) { error.value = errorMessage(cause) }
}
async function loadSeats() {
  if (!selectedLabId.value) return
  loading.value = true; error.value = ''
  try { availability.value = await http.get<LabAvailability>(`/api/labs/${selectedLabId.value}/availability`, { params: { date: today() } }) } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
async function createLab() {
  error.value = ''; success.value = ''
  if (!labForm.code.trim() || !labForm.name.trim() || !labForm.location.trim() || labForm.capacity < 1) { error.value = '请完整填写实验室编码、名称、位置和有效容量'; return }
  loading.value = true
  try {
    const created = await http.post<Lab>('/api/admin/labs', { code: labForm.code.trim(), name: labForm.name.trim(), location: labForm.location.trim(), capacity: Number(labForm.capacity) })
    success.value = `实验室 ${created.name} 已创建`
    labForm.code = ''; labForm.name = ''; labForm.location = ''; labForm.capacity = 1
    selectedLabId.value = created.labId
    await loadLabs()
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
async function updateLabStatus(lab: Lab) {
  error.value = ''; success.value = ''
  try { await http.patch(`/api/admin/labs/${lab.labId}/status`, { status: lab.status }); success.value = `${lab.name} 状态已更新`; await loadLabs() } catch (cause) { error.value = errorMessage(cause); await loadLabs() }
}
async function updateSeatStatus(seatId: number, status: ResourceStatus) {
  error.value = ''; success.value = ''
  try { await http.patch(`/api/admin/seats/${seatId}/status`, { status }); success.value = '工位状态已更新'; await loadSeats() } catch (cause) { error.value = errorMessage(cause); await loadSeats() }
}
async function createTerminal() {
  error.value = ''; success.value = ''
  if (!terminalForm.labId || !terminalForm.deviceName.trim() || !terminalForm.terminalCode.trim() || terminalForm.initialSecret.length < 8) { error.value = '请填写终端实验室、名称、编码和至少8位初始秘密'; return }
  loading.value = true
  try {
    const created = await http.post<{ terminalCode: string }>('/api/admin/terminals', { labId: Number(terminalForm.labId), deviceName: terminalForm.deviceName.trim(), model: terminalForm.model.trim() || null, terminalCode: terminalForm.terminalCode.trim(), initialSecret: terminalForm.initialSecret })
    success.value = `终端 ${created.terminalCode} 已创建。秘密不会回显。`
    terminalForm.deviceName = ''; terminalForm.model = ''; terminalForm.terminalCode = ''; terminalForm.initialSecret = ''
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false; terminalForm.initialSecret = '' }
}
onMounted(loadLabs)
</script>

<template>
  <div class="page">
    <header class="page-header"><div><span class="eyebrow">系统管理 · 资源</span><h1>实验室、工位与终端</h1><p>所有创建和状态修改直接调用管理接口；终端秘密只提交、不展示。</p></div></header>
    <div v-if="success" class="success-banner" role="status">{{ success }}</div><div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <section class="panel"><div class="panel__header"><h2>实验室资源</h2></div><EmptyState v-if="!error && !labs.length" title="暂无实验室" /><div v-else class="table-wrap"><table class="data-table"><thead><tr><th>编码</th><th>名称</th><th>位置</th><th>容量</th><th>状态</th></tr></thead><tbody><tr v-for="lab in labs" :key="lab.labId"><td class="mono">{{ lab.code }}</td><td>{{ lab.name }}</td><td>{{ lab.location }}</td><td class="mono">{{ lab.capacity }}</td><td><select v-model="lab.status" class="status-select" :aria-label="`更新${lab.name}状态`" @change="updateLabStatus(lab)"><option v-for="status in statuses" :key="status" :value="status">{{ statusLabels[status] }}</option></select></td></tr></tbody></table></div></section>
    <div class="admin-grid">
      <section class="panel"><div class="panel__header"><h2>创建实验室</h2></div><form class="admin-form" @submit.prevent="createLab"><label class="field"><span>实验室编码</span><input v-model="labForm.code" maxlength="32" placeholder="如 LAB-C"></label><label class="field"><span>名称</span><input v-model="labForm.name" maxlength="100"></label><label class="field"><span>位置</span><input v-model="labForm.location" maxlength="200"></label><label class="field"><span>容量</span><input v-model.number="labForm.capacity" type="number" min="1"></label><button class="button button--primary" type="submit" :disabled="loading">创建实验室</button></form></section>
      <section class="panel"><div class="panel__header"><h2>创建签到终端</h2></div><form class="admin-form" autocomplete="off" @submit.prevent="createTerminal"><label class="field"><span>所属实验室</span><select v-model.number="terminalForm.labId"><option v-for="lab in labs" :key="lab.labId" :value="lab.labId">{{ lab.name }}</option></select></label><label class="field"><span>设备名称</span><input v-model="terminalForm.deviceName" maxlength="100"></label><label class="field"><span>型号（选填）</span><input v-model="terminalForm.model" maxlength="100"></label><label class="field"><span>终端编码</span><input v-model="terminalForm.terminalCode" maxlength="64" autocomplete="off"></label><label class="field"><span>初始秘密</span><input v-model="terminalForm.initialSecret" type="password" minlength="8" maxlength="72" autocomplete="new-password"><small>提交后清空，不在任何列表或结果中回显。</small></label><button class="button button--primary" type="submit" :disabled="loading">创建终端</button></form></section>
    </div>
    <section class="panel"><div class="panel__header"><div><h2>工位状态</h2><span class="panel__subtitle">从所选实验室今日availability读取实际工位</span></div><select v-model.number="selectedLabId" class="lab-picker" @change="loadSeats"><option v-for="lab in labs" :key="lab.labId" :value="lab.labId">{{ lab.name }}</option></select></div><div v-if="loading" class="loading-line">正在读取工位</div><EmptyState v-else-if="!error && !availability?.seats.length" title="该实验室暂无工位" /><div v-else-if="availability" class="table-wrap"><table class="data-table"><thead><tr><th>工位</th><th>区域</th><th>序号</th><th>配置</th><th>靠窗</th><th>状态</th></tr></thead><tbody><tr v-for="seat in availability.seats" :key="seat.seatId"><td class="mono">{{ seat.seatCode }}</td><td>{{ seat.zoneCode }}</td><td class="mono">{{ seat.seatIndex }}</td><td>{{ seat.specLevel }}</td><td>{{ seat.nearWindow ? '是' : '否' }}</td><td><select :value="seat.status" class="status-select" @change="updateSeatStatus(seat.seatId, ($event.target as HTMLSelectElement).value as ResourceStatus)"><option v-for="status in statuses" :key="status" :value="status">{{ statusLabels[status] }}</option></select></td></tr></tbody></table></div></section>
  </div>
</template>

<style scoped>
.admin-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.admin-form { display: grid; gap: 14px; max-width: 520px; }.admin-form small { color: var(--muted); }.status-select, .lab-picker { min-height: 40px; padding: 8px 10px; color: var(--ink); background: var(--paper); border: 1px solid var(--line); border-radius: 6px; outline: none; font-size: 14px; }.status-select:focus, .lab-picker:focus { border-color: var(--primary); box-shadow: 0 0 0 3px rgb(31 107 87 / 16%); }
@media (max-width: 900px) { .admin-grid { grid-template-columns: 1fr; } }
</style>
