<script setup lang="ts">
import { onMounted, ref } from 'vue'
import http, { errorMessage } from '../../api/http'
import type { Appeal, Page } from '../../types'
import { formatDateTime, statusLabels } from '../../utils'
import EmptyState from '../../components/EmptyState.vue'

const result = ref<Page<Appeal>>({ items: [], page: 0, size: 10, total: 0 })
const page = ref(1)
const status = ref('')
const loading = ref(false)
const error = ref('')
const success = ref('')
const reviewVisible = ref(false)
const target = ref<Appeal | null>(null)
const decision = ref<'APPROVED' | 'REJECTED'>('APPROVED')
const opinion = ref('')
const submitting = ref(false)
async function load() { loading.value = true; error.value = ''; try { result.value = await http.get<Page<Appeal>>('/api/admin/appeals', { params: { status: status.value || undefined, page: page.value - 1, size: 10 } }) } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
function search() { page.value = 1; load() }
function openReview(item: Appeal, nextDecision: 'APPROVED' | 'REJECTED') { target.value = item; decision.value = nextDecision; opinion.value = ''; reviewVisible.value = true }
async function review() {
  if (!target.value || !opinion.value.trim()) { error.value = '核定意见不能为空'; return }
  submitting.value = true; error.value = ''
  try {
    await http.post(`/api/admin/appeals/${target.value.appealId}/review`, { decision: decision.value, opinion: opinion.value.trim() })
    success.value = `申诉 #${target.value.appealId} 已${decision.value === 'APPROVED' ? '核定通过' : '驳回'}`
    reviewVisible.value = false; await load()
  } catch (cause) { error.value = errorMessage(cause) } finally { submitting.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page"><header class="page-header"><div><span class="eyebrow">系统管理 · 申诉核定</span><h1>申诉核定</h1><p>查看申诉理由，填写核定意见后正式通过或驳回。</p></div></header><div v-if="success" class="success-banner" role="status">{{ success }}</div><div v-if="error" class="error-banner" role="alert">{{ error }}</div><section class="panel"><div class="toolbar"><label class="field"><span>状态</span><select v-model="status"><option value="">全部</option><option value="PENDING">待处理</option><option value="APPROVED">已通过</option><option value="REJECTED">已驳回</option></select></label><button class="button" @click="search">查询</button></div><div v-if="loading" class="loading-line">正在读取申诉</div><EmptyState v-else-if="!error && !result.items.length" title="没有符合条件的申诉" /><div v-else class="appeal-list"><article v-for="item in result.items" :key="item.appealId" class="appeal-item"><div class="appeal-item__meta"><span class="mono">APPEAL #{{ item.appealId }}</span><span class="tag" :class="item.status === 'PENDING' ? 'tag--amber' : item.status === 'APPROVED' ? 'tag--green' : 'tag--red'">{{ statusLabels[item.status] }}</span></div><dl class="detail-list"><dt>违规 / 用户</dt><dd class="mono">#{{ item.violationId }} / #{{ item.userId }}</dd><dt>提交时间</dt><dd class="mono">{{ formatDateTime(item.createdAt) }}</dd><dt>申诉理由</dt><dd>{{ item.reason }}</dd><template v-if="item.reviewOpinion"><dt>核定意见</dt><dd>{{ item.reviewOpinion }}<small class="muted">核定人 #{{ item.reviewerId }} · {{ formatDateTime(item.reviewedAt) }}</small></dd></template></dl><div v-if="item.status === 'PENDING'" class="button-row appeal-actions"><button class="button button--primary" @click="openReview(item, 'APPROVED')">核定通过</button><button class="button button--danger" @click="openReview(item, 'REJECTED')">驳回申诉</button></div></article></div><el-pagination v-model:current-page="page" class="pagination" background layout="total, prev, pager, next" :total="result.total" :page-size="10" @current-change="load" /></section><el-dialog v-model="reviewVisible" :title="decision === 'APPROVED' ? '核定通过申诉' : '驳回申诉'" width="520px"><p v-if="target" class="small">申诉 #{{ target.appealId }}：{{ target.reason }}</p><label class="field"><span>核定意见</span><textarea v-model="opinion" rows="5" maxlength="1000" placeholder="说明核定依据，将写入申诉记录" /></label><div class="dialog-actions"><button class="button" @click="reviewVisible = false">关闭</button><button class="button" :class="decision === 'APPROVED' ? 'button--primary' : 'button--danger'" :disabled="submitting || !opinion.trim()" @click="review">{{ submitting ? '正在提交…' : decision === 'APPROVED' ? '确认通过' : '确认驳回' }}</button></div></el-dialog></div>
</template>

<style scoped>
.appeal-list { display: grid; gap: 13px; }.appeal-item { padding: 16px; background: var(--paper); border-bottom: 1px solid var(--line); }.appeal-item__meta { display: flex; justify-content: space-between; margin-bottom: 16px; font-size: 12px; }.appeal-item small { display: block; margin-top: 6px; }.appeal-actions { justify-content: flex-end; margin-top: 18px; }.dialog-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 22px; }
</style>
