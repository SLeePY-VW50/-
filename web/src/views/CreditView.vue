<script setup lang="ts">
import { onMounted, ref } from 'vue'
import http, { errorMessage } from '../api/http'
import type { CreditRecord, Page, Violation } from '../types'
import { formatDateTime, statusLabels } from '../utils'
import MetricCard from '../components/MetricCard.vue'
import EmptyState from '../components/EmptyState.vue'

const creditScore = ref(0)
const records = ref<Page<CreditRecord>>({ items: [], page: 0, size: 10, total: 0 })
const violations = ref<Page<Violation>>({ items: [], page: 0, size: 10, total: 0 })
const recordPage = ref(1)
const violationPage = ref(1)
const loading = ref(false)
const error = ref('')
const success = ref('')
const appealVisible = ref(false)
const appealViolation = ref<Violation | null>(null)
const reason = ref('')
const submitting = ref(false)

async function loadRecords() {
  error.value = ''
  try {
    const data = await http.get<{ creditScore: number; records: Page<CreditRecord> }>('/api/credit/me', { params: { page: recordPage.value - 1, size: 10 } })
    creditScore.value = data.creditScore; records.value = data.records
  } catch (cause) { error.value = errorMessage(cause) }
}
async function loadViolations() {
  error.value = ''
  try { violations.value = await http.get<Page<Violation>>('/api/credit/violations', { params: { page: violationPage.value - 1, size: 10 } }) } catch (cause) { error.value = errorMessage(cause) }
}
async function load() { loading.value = true; await Promise.all([loadRecords(), loadViolations()]); loading.value = false }
function openAppeal(item: Violation) { appealViolation.value = item; reason.value = ''; appealVisible.value = true }
async function submitAppeal() {
  if (!appealViolation.value || !reason.value.trim()) { error.value = '请填写申诉理由'; return }
  submitting.value = true; error.value = ''
  try {
    await http.post('/api/appeals', { violationId: appealViolation.value.violationId, reason: reason.value.trim() })
    appealVisible.value = false; success.value = '申诉已提交，请等待系统管理员核定。'; await loadViolations()
  } catch (cause) { error.value = errorMessage(cause) } finally { submitting.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-header"><div><span class="eyebrow">CREDIT / APPEAL</span><h1>我的信用与违规记录</h1><p>信用分、扣分流水和申诉状态均来自本人记录；此处没有管理核定入口。</p></div></header>
    <div v-if="success" class="success-banner" role="status">{{ success }}</div><div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <div class="metrics-grid credit-metrics"><MetricCard label="当前信用分" :value="creditScore" hint="由信用流水实时汇总" tone="green" /></div>
    <div v-if="loading" class="loading-line">正在读取信用记录</div>
    <template v-else>
      <section class="panel"><div class="panel__header"><div><h2>违规记录</h2><span class="panel__subtitle">仅未发起申诉的违规可提交申诉</span></div></div><EmptyState v-if="!violations.items.length" title="暂无违规记录" /><div v-else class="table-wrap"><table class="data-table"><thead><tr><th>违规编号</th><th>预约</th><th>类型</th><th>扣分</th><th>状态</th><th>发生时间</th><th>申诉</th></tr></thead><tbody><tr v-for="item in violations.items" :key="item.violationId"><td class="mono">#{{ item.violationId }}</td><td class="mono">#{{ item.resvId }}</td><td>{{ item.violationType }}</td><td class="mono">-{{ item.deductPoints }}</td><td>{{ statusLabels[item.status] || item.status }}<small v-if="item.revokedAt" class="muted">已撤销</small></td><td class="mono small">{{ formatDateTime(item.createdAt) }}</td><td><span v-if="item.appealId" class="tag" :class="item.appealStatus === 'APPROVED' ? 'tag--green' : item.appealStatus === 'REJECTED' ? 'tag--red' : 'tag--amber'">{{ statusLabels[item.appealStatus || 'PENDING'] }}</span><button v-else class="button button--small" @click="openAppeal(item)">提交申诉</button></td></tr></tbody></table></div><el-pagination v-model:current-page="violationPage" class="pagination" background layout="total, prev, pager, next" :total="violations.total" :page-size="10" @current-change="loadViolations" /></section>
      <section class="panel"><div class="panel__header"><h2>信用流水</h2></div><EmptyState v-if="!records.items.length" title="暂无信用流水" /><div v-else class="table-wrap"><table class="data-table"><thead><tr><th>流水</th><th>变更类型</th><th>关联违规</th><th>变更</th><th>变更前</th><th>变更后</th><th>时间</th></tr></thead><tbody><tr v-for="item in records.items" :key="item.recordId"><td class="mono">#{{ item.recordId }}</td><td>{{ item.changeType }}</td><td class="mono">{{ item.violationId ? `#${item.violationId}` : '—' }}</td><td class="mono" :class="item.changeAmount < 0 ? 'negative' : 'positive'">{{ item.changeAmount > 0 ? '+' : '' }}{{ item.changeAmount }}</td><td class="mono">{{ item.balanceBefore }}</td><td class="mono">{{ item.balanceAfter }}</td><td class="mono small">{{ formatDateTime(item.createdAt) }}</td></tr></tbody></table></div><el-pagination v-model:current-page="recordPage" class="pagination" background layout="total, prev, pager, next" :total="records.total" :page-size="10" @current-change="loadRecords" /></section>
    </template>
    <el-dialog v-model="appealVisible" title="提交违规申诉" width="520px"><p v-if="appealViolation" class="small">针对违规 #{{ appealViolation.violationId }}，说明事实与依据。提交后由系统管理员核定。</p><label class="field"><span>申诉理由</span><textarea v-model="reason" rows="5" maxlength="1000" placeholder="请具体说明申诉依据" /></label><div class="dialog-actions"><button class="button" @click="appealVisible = false">关闭</button><button class="button button--primary" :disabled="submitting || !reason.trim()" @click="submitAppeal">{{ submitting ? '正在提交…' : '提交申诉' }}</button></div></el-dialog>
  </div>
</template>

<style scoped>
.credit-metrics { grid-template-columns: minmax(220px, 320px); }
.negative { color: var(--danger); }.positive { color: var(--green); }
.dialog-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 22px; }
td small { display: block; margin-top: 4px; }
</style>
