<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import http, { errorMessage } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { Lab, Page, Reservation } from '../types'
import { addDays, formatDateTime, reservationTime, statusLabels, timeOnly, today } from '../utils'
import EmptyState from '../components/EmptyState.vue'

const emptyPage = <T,>(): Page<T> => ({ items: [], page: 0, size: 10, total: 0 })
const auth = useAuthStore()
const isAdmin = computed(() => auth.roles.includes('LAB_ADMIN') || auth.roles.includes('SYS_ADMIN'))
const isTeacher = computed(() => auth.roles.includes('TEACHER'))
const canWrite = computed(() => auth.roles.includes('STUDENT') || auth.roles.includes('TEACHER'))
const result = ref<Page<Reservation>>(emptyPage())
const labs = ref<Lab[]>([])
const filters = reactive({ labId: null as number | null, date: '', status: '' })
const page = ref(1)
const loading = ref(false)
const error = ref('')
const success = ref('')
const detail = ref<Reservation | null>(null)
const detailVisible = ref(false)
const cancelVisible = ref(false)
const cancelTarget = ref<Reservation | null>(null)
const cancelReason = ref('')
const actionLoading = ref(false)
const batchVisible = ref(false)
const batchError = ref('')
const batch = reactive({ courseName: '', labId: null as number | null, attendeeCount: 1, purpose: '', items: [{ date: addDays(today(), 1), startTime: '09:00', endTime: '11:00' }] })
const startOptions = Array.from({ length: 48 }, (_, index) => `${String(Math.floor(index / 2)).padStart(2, '0')}:${index % 2 ? '30' : '00'}`)
const endOptions = [...startOptions.slice(1), '24:00']
const statuses = ['PENDING_SCHEDULE', 'APPROVED', 'IN_USE', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'EXPIRED', 'VIOLATION_TERMINATED']

async function load() {
  loading.value = true; error.value = ''
  try {
    const params: Record<string, unknown> = { page: page.value - 1, size: 10, status: filters.status || undefined }
    let url = '/api/reservations/my'
    if (isAdmin.value) {
      url = '/api/reservations'
      params.labId = filters.labId || undefined
      params.date = filters.date || undefined
    }
    result.value = await http.get<Page<Reservation>>(url, { params })
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
function search() { page.value = 1; load() }
async function openDetail(item: Reservation) {
  detailVisible.value = true; detail.value = null; error.value = ''
  try { detail.value = await http.get<Reservation>(`/api/reservations/${item.resvId}`) } catch (cause) { error.value = errorMessage(cause); detailVisible.value = false }
}
function canCancel(item: Reservation) { return canWrite.value && ['PENDING_SCHEDULE', 'APPROVED'].includes(item.status) }
function askCancel(item: Reservation) { cancelTarget.value = item; cancelReason.value = ''; cancelVisible.value = true }
async function cancel() {
  if (!cancelTarget.value) return
  actionLoading.value = true; error.value = ''
  try {
    await http.post(`/api/reservations/${cancelTarget.value.resvId}/cancel`, { reason: cancelReason.value.trim() || null })
    success.value = `预约 #${cancelTarget.value.resvId} 已取消`
    cancelVisible.value = false; detailVisible.value = false
    await load()
  } catch (cause) { error.value = errorMessage(cause) } finally { actionLoading.value = false }
}
function openBatch() {
  batchError.value = ''; batchVisible.value = true
  if (!batch.labId && labs.value.length) batch.labId = labs.value[0].labId
}
function addBatchItem() { if (batch.items.length < 14) batch.items.push({ date: addDays(today(), 1), startTime: '09:00', endTime: '11:00' }) }
function removeBatchItem(index: number) { if (batch.items.length > 1) batch.items.splice(index, 1) }
async function submitBatch() {
  batchError.value = ''
  if (!batch.courseName.trim() || !batch.labId || batch.attendeeCount < 1 || !batch.purpose.trim() || batch.items.some((item) => !item.date || !item.startTime || !item.endTime)) {
    batchError.value = '请完整填写课程、实验室、人数、用途和每条时段'; return
  }
  actionLoading.value = true
  try {
    const created = await http.post<{ batchNo: string }>('/api/reservations/batches', {
      courseName: batch.courseName.trim(), labId: Number(batch.labId), attendeeCount: Number(batch.attendeeCount), purpose: batch.purpose.trim(), items: batch.items,
    })
    batchVisible.value = false; success.value = `批次 ${created.batchNo} 已提交`
    await load()
  } catch (cause) { batchError.value = errorMessage(cause) } finally { actionLoading.value = false }
}
onMounted(async () => {
  await load()
  if (isAdmin.value || isTeacher.value) {
    try { labs.value = await http.get<Lab[]>('/api/labs') } catch (cause) { error.value = errorMessage(cause) }
  }
})
</script>

<template>
  <div class="page">
    <header class="page-header"><div><span class="eyebrow">预约台账</span><h1>{{ isAdmin ? '预约管理查询' : '我的预约' }}</h1><p>{{ isAdmin ? '管理员仅查询预约、正式时段与座位，不提供写操作。' : '查看排程结果与正式座位，或取消仍可撤销的预约。' }}</p></div><button v-if="isTeacher" class="button button--primary" type="button" @click="openBatch">新建课程批次</button></header>
    <div v-if="success" class="success-banner" role="status">{{ success }}</div><div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <section class="panel">
      <div class="toolbar">
        <label v-if="isAdmin" class="field"><span>实验室</span><select v-model.number="filters.labId"><option :value="null">全部实验室</option><option v-for="lab in labs" :key="lab.labId" :value="lab.labId">{{ lab.name }}</option></select></label>
        <label v-if="isAdmin" class="field"><span>日期</span><input v-model="filters.date" type="date"></label>
        <label class="field"><span>状态</span><select v-model="filters.status"><option value="">全部状态</option><option v-for="status in statuses" :key="status" :value="status">{{ statusLabels[status] }}</option></select></label>
        <button class="button" type="button" @click="search">查询</button>
      </div>
      <div v-if="loading" class="loading-line" aria-busy="true">正在查询预约记录</div>
      <EmptyState v-else-if="!error && !result.items.length" title="没有符合条件的预约" description="调整筛选条件，或从实验室页面新建预约。" />
      <div v-else class="table-wrap"><table class="data-table"><thead><tr><th>编号 / 批次</th><th>实验室</th><th>日期与时段</th><th>方式</th><th>人数 / 工位</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in result.items" :key="item.resvId"><td><strong class="mono">#{{ item.resvId }}</strong><small v-if="item.batchNo" class="mono muted batch-no">{{ item.batchNo }}</small></td><td>{{ item.labName || '待排程分配' }}</td><td class="mono small">{{ reservationTime(item) }}</td><td>{{ statusLabels[item.allocationMode] }} · {{ statusLabels[item.reservationMode] }}</td><td>{{ item.attendeeCount }}<span v-if="item.seatCount != null"> / {{ item.seatCount }}</span><div v-if="item.seats.length" class="mono small muted">{{ item.seats.map((seat) => seat.seatCode).join('、') }}</div></td><td><span class="tag" :class="item.status === 'APPROVED' || item.status === 'IN_USE' || item.status === 'COMPLETED' ? 'tag--green' : item.status === 'PENDING_SCHEDULE' ? 'tag--amber' : item.status === 'NO_SHOW' || item.status === 'VIOLATION_TERMINATED' ? 'tag--red' : ''">{{ statusLabels[item.status] }}</span></td><td><div class="button-row"><button class="button button--small" type="button" @click="openDetail(item)">详情</button><button v-if="canCancel(item)" class="button button--small button--danger" type="button" @click="askCancel(item)">取消</button></div></td></tr></tbody></table></div>
      <el-pagination v-model:current-page="page" class="pagination" background layout="total, prev, pager, next" :total="result.total" :page-size="10" @current-change="load" />
    </section>
    <el-dialog v-model="detailVisible" title="预约详情" width="680px">
      <div v-if="!detail" class="loading-line" aria-busy="true">正在读取详情</div><template v-else><dl class="detail-list"><dt>预约编号</dt><dd class="mono">#{{ detail.resvId }}</dd><dt>实验室</dt><dd>{{ detail.labName || '待排程分配' }}</dd><dt>正式时段</dt><dd class="mono">{{ detail.startTime ? `${formatDateTime(detail.startTime)} — ${formatDateTime(detail.endTime)}` : '尚未生成' }}</dd><dt>需求窗口</dt><dd class="mono">{{ detail.date }} {{ timeOnly(detail.windowStart) }}–{{ timeOnly(detail.windowEnd, true) }} / {{ detail.durationMinutes }}分钟</dd><dt>预约方式</dt><dd>{{ statusLabels[detail.allocationMode] }} · {{ statusLabels[detail.reservationMode] }}</dd><dt>用途</dt><dd>{{ detail.purpose }}</dd><dt>签到</dt><dd>{{ detail.checkinTime ? `${formatDateTime(detail.checkinTime)} / ${detail.checkinChannel}` : '未签到' }}</dd><dt>偏好</dt><dd>{{ detail.preferences?.zoneCode || '不限区域' }} · {{ detail.preferences?.minSpecLevel || '不限配置' }} · {{ detail.preferences?.nearWindow == null ? '不限靠窗' : detail.preferences.nearWindow ? '靠窗' : '不靠窗' }}</dd></dl><h3>正式座位</h3><div v-if="detail.seats.length" class="seat-list"><span v-for="seat in detail.seats" :key="seat.seatId" class="seat-chip"><strong class="mono">{{ seat.seatCode }}</strong><small>{{ seat.zoneCode }} / {{ seat.specLevel }}</small><i v-if="seat.zoneMismatch || seat.nearWindowMismatch">偏好不完全匹配</i></span></div><EmptyState v-else title="尚未分配正式座位" description="整室预约或待排程预约可能没有独立座位。" /><div class="dialog-actions"><button v-if="canCancel(detail)" class="button button--danger" type="button" @click="askCancel(detail)">取消此预约</button></div></template>
    </el-dialog>
    <el-dialog v-model="cancelVisible" title="取消预约" width="480px"><p class="small">取消后时段将被释放，此操作会写入预约记录。</p><label class="field"><span>取消原因（选填）</span><textarea v-model="cancelReason" rows="3" maxlength="500" /></label><div class="dialog-actions"><button class="button" type="button" @click="cancelVisible = false">保留预约</button><button class="button button--danger" type="button" :disabled="actionLoading" @click="cancel">{{ actionLoading ? '正在取消…' : '确认取消' }}</button></div></el-dialog>
    <el-dialog v-model="batchVisible" title="教师课程批次 · 固定整室" width="820px">
      <div class="batch-common"><label class="field"><span>课程名称</span><input v-model="batch.courseName" maxlength="128"></label><label class="field"><span>同一实验室</span><select v-model.number="batch.labId"><option v-for="lab in labs" :key="lab.labId" :value="lab.labId">{{ lab.name }}</option></select></label><label class="field"><span>人数</span><input v-model.number="batch.attendeeCount" type="number" min="1"></label></div>
      <label class="field batch-purpose"><span>用途</span><input v-model="batch.purpose" maxlength="500"></label>
      <div class="batch-heading"><strong>上课时段（{{ batch.items.length }}/14）</strong><button class="button button--small" type="button" :disabled="batch.items.length >= 14" @click="addBatchItem">添加一条</button></div>
      <div class="batch-items"><div v-for="(item, index) in batch.items" :key="index" class="batch-item"><span class="mono">{{ String(index + 1).padStart(2, '0') }}</span><input v-model="item.date" type="date" aria-label="批次日期"><select v-model="item.startTime" aria-label="开始时间"><option v-for="time in startOptions" :key="time">{{ time }}</option></select><span>至</span><select v-model="item.endTime" aria-label="结束时间"><option v-for="time in endOptions" :key="time">{{ time }}</option></select><button class="button button--small" type="button" :disabled="batch.items.length === 1" @click="removeBatchItem(index)">移除</button></div></div>
      <div v-if="batchError" class="error-banner" role="alert">{{ batchError }}</div><div class="dialog-actions"><button class="button" type="button" @click="batchVisible = false">关闭</button><button class="button button--primary" type="button" :disabled="actionLoading" @click="submitBatch">{{ actionLoading ? '正在提交…' : '提交整个批次' }}</button></div>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-wrap .data-table { min-width: 1180px; table-layout: fixed; }
.table-wrap .data-table th:nth-child(1) { width: 110px; }
.table-wrap .data-table th:nth-child(2) { width: 140px; }
.table-wrap .data-table th:nth-child(3) { width: 215px; }
.table-wrap .data-table th:nth-child(4) { width: 130px; }
.table-wrap .data-table th:nth-child(6) { width: 100px; }
.table-wrap .data-table th:nth-child(7) { width: 120px; }
.table-wrap .data-table td:nth-child(2), .table-wrap .data-table td:nth-child(4) { word-break: keep-all; }
.table-wrap .data-table td:nth-child(3) { overflow-wrap: anywhere; white-space: normal; }
.table-wrap .data-table td:nth-child(5) .mono { display: block; margin-top: 6px; line-height: 1.8; overflow-wrap: anywhere; }
.table-wrap .data-table td:last-child .button-row { flex-wrap: nowrap; }
.batch-no { display: block; margin-top: 4px; }
.seat-list { display: flex; flex-wrap: wrap; gap: 9px; }
.seat-chip { display: grid; gap: 4px; min-width: 110px; padding: 10px; background: var(--paper); border: 1px solid var(--line); }
.seat-chip small { color: var(--muted); }.seat-chip i { color: var(--amber); font-size: 10px; font-style: normal; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 22px; }
.batch-common { display: grid; grid-template-columns: 1.4fr 1.4fr .6fr; gap: 12px; }
.batch-purpose { margin-top: 13px; }
.batch-heading { display: flex; align-items: center; justify-content: space-between; margin: 20px 0 11px; font-size: 13px; }
.batch-items { display: grid; gap: 8px; max-height: 360px; overflow: auto; }
.batch-item { display: grid; grid-template-columns: 30px 1.2fr 1fr 20px 1fr auto; gap: 8px; align-items: center; padding: 7px; background: transparent; border-bottom: 1px solid var(--line); }
.batch-item input, .batch-item select { min-width: 0; min-height: 40px; padding: 8px; background: var(--paper); border: 1px solid var(--line); border-radius: 0; }
@media (max-width: 700px) {
  .batch-common { grid-template-columns: 1fr; }
  .batch-item { grid-template-columns: 24px 1fr; }
  .batch-item span:nth-of-type(2) { display: none; }
  .batch-item select, .batch-item input[type='date'] { grid-column: 2; }
  .batch-item .button { grid-column: 2; justify-self: start; }
  .dialog-actions { flex-direction: column-reverse; }
  .dialog-actions .button { width: 100%; }
}
</style>
