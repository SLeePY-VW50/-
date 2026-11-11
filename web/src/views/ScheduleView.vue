<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import http, { errorMessage } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { ExperimentBatch, Page, ScheduleBatch, ScheduleBatchDetail, ScheduleTask } from '../types'
import { formatDateTime, percent, statusLabels } from '../utils'
import MetricCard from '../components/MetricCard.vue'
import EmptyState from '../components/EmptyState.vue'

const auth = useAuthStore()
const batches = ref<Page<ScheduleBatch>>({ items: [], page: 0, size: 10, total: 0 })
const page = ref(1)
const detail = ref<ScheduleBatchDetail | null>(null)
const task = ref<ScheduleTask | null>(null)
const loading = ref(false)
const error = ref('')
const experiment = ref<ExperimentBatch | null>(null)
const experimentLoading = ref(false)
const experimentError = ref('')
const experimentBatchNo = ref('')
const dataScale = ref(100)
const seed = ref(42)
let timer: ReturnType<typeof setInterval> | undefined
const active = (status?: string) => status === 'PENDING' || status === 'RUNNING'
const metric = (value: number | null | undefined) => value == null ? '—' : percent(value)
const summaryMetrics = [
  ['allocationSuccessRate', '分配成功率'],
  ['averageSeatUtilization', '平均工位利用率'],
  ['seatPreferenceMismatchRate', '偏好不匹配率'],
  ['timeFragmentationRate', '时间碎片率'],
  ['seatAllocationFailureRate', '工位分配失败率'],
] as const

async function loadBatches() {
  error.value = ''
  try { batches.value = await http.get<Page<ScheduleBatch>>('/api/schedules/batches', { params: { page: page.value - 1, size: 10 } }) } catch (cause) { error.value = errorMessage(cause) }
}
async function selectBatch(batchNo: string) {
  loading.value = true; error.value = ''; task.value = null
  try {
    detail.value = await http.get<ScheduleBatchDetail>(`/api/schedules/batches/${batchNo}`)
    const primary = detail.value.tasks.find((item) => item.taskId === detail.value?.primaryTaskId) || detail.value.tasks[0]
    if (primary) await selectTask(primary.taskId)
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
async function selectTask(taskId: number) {
  error.value = ''
  try { task.value = await http.get<ScheduleTask>(`/api/schedules/tasks/${taskId}`) } catch (cause) { error.value = errorMessage(cause) }
}
async function runExperiment() {
  experimentLoading.value = true; experimentError.value = ''
  try {
    const run = await http.post<{ batchNo: string }>('/api/experiments/run', { dataScale: Number(dataScale.value), seed: Number(seed.value) })
    await loadExperiment(run.batchNo)
  } catch (cause) { experimentError.value = errorMessage(cause) } finally { experimentLoading.value = false }
}
async function loadExperiment(batchNo: string) {
  const normalized = batchNo.trim()
  if (!normalized) { experimentError.value = '请输入实验批次号'; return }
  experimentBatchNo.value = normalized
  experimentError.value = ''
  try { experiment.value = await http.get<ExperimentBatch>(`/api/experiments/${normalized}`) } catch (cause) { experimentError.value = errorMessage(cause) }
}
async function refreshActive() {
  if (document.hidden) return
  if (detail.value && active(detail.value.primaryStatus)) {
    await loadBatches()
    const batchNo = detail.value.batchNo
    detail.value = await http.get<ScheduleBatchDetail>(`/api/schedules/batches/${batchNo}`)
    if (task.value) task.value = await http.get<ScheduleTask>(`/api/schedules/tasks/${task.value.taskId}`)
  }
  if (experiment.value && active(experiment.value.status)) await loadExperiment(experiment.value.batchNo)
}
onMounted(async () => {
  await loadBatches()
  if (batches.value.items.length) await selectBatch(batches.value.items[0].batchNo)
  timer = setInterval(() => { refreshActive().catch((cause) => { error.value = errorMessage(cause) }) }, 3000)
})
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <div class="page">
    <header class="page-header"><div><span class="eyebrow">可核验排程任务</span><h1>生产排程与对比实验</h1><p>进度、五项指标与分配结果均来自后端任务；运行中每3秒轮询真实状态。</p></div></header>
    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <section class="panel">
      <div class="panel__header"><div><h2>生产批次</h2><span class="panel__subtitle">选择批次查看任务和正式分配结果</span></div></div>
      <EmptyState v-if="!error && !batches.items.length" title="暂无生产排程批次" />
      <div v-else class="table-wrap"><table class="data-table"><thead><tr><th>批次号</th><th>请求日期</th><th>数据集</th><th>任务数</th><th>主任务状态</th><th>创建时间</th><th /></tr></thead><tbody><tr v-for="item in batches.items" :key="item.batchNo" :class="{ 'selected-row': detail?.batchNo === item.batchNo }"><td class="mono">{{ item.batchNo }}</td><td class="mono">{{ item.requestDate }}</td><td class="mono">{{ item.datasetId }}</td><td>{{ item.taskCount }}</td><td><span class="tag" :class="active(item.primaryStatus) ? 'tag--amber' : item.primaryStatus.includes('FAILED') ? 'tag--red' : 'tag--green'">{{ statusLabels[item.primaryStatus] }}</span></td><td class="mono small">{{ formatDateTime(item.createdAt) }}</td><td><button class="button button--small" @click="selectBatch(item.batchNo)">查看批次</button></td></tr></tbody></table></div>
      <el-pagination v-model:current-page="page" class="pagination" background layout="total, prev, pager, next" :total="batches.total" :page-size="10" @current-change="loadBatches" />
    </section>
    <section v-if="detail" class="panel">
      <div class="panel__header"><div><h2 class="mono">{{ detail.batchNo }}</h2><span class="panel__subtitle">{{ detail.datasetId }} · {{ detail.requestDate }}</span></div><span class="tag" :class="active(detail.primaryStatus) ? 'tag--amber' : 'tag--green'">{{ statusLabels[detail.primaryStatus] }}</span></div>
      <div class="table-wrap"><table class="data-table"><thead><tr><th>任务</th><th>算法</th><th>进度</th><th>状态</th><th>开始 / 结束</th><th /></tr></thead><tbody><tr v-for="item in detail.tasks" :key="item.taskId"><td class="mono">#{{ item.taskId }} <span v-if="item.primary" class="tag">主任务</span></td><td>{{ item.algorithmType }}</td><td class="mono">{{ percent(item.progress) }}</td><td>{{ statusLabels[item.status] }}<small v-if="item.errorMessage" class="error-text">{{ item.errorMessage }}</small></td><td class="mono small">{{ formatDateTime(item.startedAt) }}<br>{{ formatDateTime(item.finishedAt) }}</td><td><button class="button button--small" @click="selectTask(item.taskId)">任务详情</button></td></tr></tbody></table></div>
    </section>
    <section v-if="task" class="panel">
      <div class="panel__header"><div><h2>{{ task.algorithmType }} 任务 #{{ task.taskId }}</h2><span class="panel__subtitle">seed {{ task.randomSeed ?? '—' }} · repeat {{ task.repeatIndex }} · {{ task.results.length }} 条分配结果</span></div></div>
      <div v-if="task.experiment" class="metrics-grid metrics-grid--five"><MetricCard label="分配成功率" :value="percent(task.experiment.allocationSuccessRate)" tone="green" /><MetricCard label="平均工位利用率" :value="percent(task.experiment.averageSeatUtilization)" /><MetricCard label="偏好不匹配率" :value="percent(task.experiment.seatPreferenceMismatchRate)" tone="amber" /><MetricCard label="时间碎片率" :value="percent(task.experiment.timeFragmentationRate)" tone="amber" /><MetricCard label="工位分配失败率" :value="metric(task.experiment.seatAllocationFailureRate)" tone="red" /></div><EmptyState v-else title="五项指标尚未生成" description="任务完成后会显示后端计算结果，不使用估算值。" />
      <h3>分配结果</h3><EmptyState v-if="!task.results.length" title="暂无分配结果" /><div v-else class="table-wrap"><table class="data-table"><thead><tr><th>预约</th><th>分配实验室</th><th>正式时段</th><th>目标值</th><th>发布</th><th>座位分配</th></tr></thead><tbody><tr v-for="result in task.results" :key="result.resultId"><td class="mono">#{{ result.resvId }}</td><td class="mono">{{ result.assignedLabId || '—' }}</td><td class="mono small">{{ formatDateTime(result.assignedStartTime) }}<br>{{ formatDateTime(result.assignedEndTime) }}</td><td class="mono">{{ result.objectiveValue ?? '—' }}</td><td>{{ result.published ? '已发布' : '未发布' }}</td><td><details><summary>查看JSON</summary><pre class="code-block">{{ JSON.stringify(result.candidateSeatAllocations, null, 2) }}</pre></details></td></tr></tbody></table></div>
    </section>
    <section class="panel experiment-zone">
      <div class="panel__header"><div><span class="eyebrow">算法对比实验</span><h2>算法对比实验区</h2><span class="panel__subtitle">与生产排程分开查询；只有SYS_ADMIN可以发起运行。</span></div></div>
      <form class="toolbar experiment-lookup" @submit.prevent="loadExperiment(experimentBatchNo)"><label class="field"><span>实验批次号（只读查询）</span><input v-model="experimentBatchNo" class="mono" placeholder="输入已有实验批次号" required></label><button class="button" type="submit">读取实验状态与结果</button></form>
      <form v-if="auth.roles.includes('SYS_ADMIN')" class="toolbar" @submit.prevent="runExperiment"><label class="field"><span>数据规模 N</span><input v-model.number="dataScale" type="number" min="1" required></label><label class="field"><span>随机种子 seed</span><input v-model.number="seed" type="number" required></label><button class="button button--primary" type="submit" :disabled="experimentLoading">{{ experimentLoading ? '正在创建…' : '运行真实对比实验' }}</button></form>
      <div v-if="experimentError" class="error-banner" role="alert">{{ experimentError }}</div>
      <EmptyState v-if="!experiment" title="尚未选择实验批次" :description="auth.roles.includes('SYS_ADMIN') ? '输入N和seed运行；结果由FCFS、GREEDY、GENETIC真实任务返回。' : 'LAB_ADMIN可读取实验结果，但不能发起运行。'" />
      <template v-else><div class="experiment-meta"><span class="mono">{{ experiment.batchNo }}</span><span>N={{ experiment.dataScale }} · seed={{ experiment.seed }}</span><span class="tag" :class="active(experiment.status) ? 'tag--amber' : 'tag--green'">{{ statusLabels[experiment.status] }}</span></div><div class="table-wrap"><table class="data-table"><thead><tr><th>算法 / 重复</th><th>状态</th><th>成功率</th><th>利用率</th><th>偏好不匹配</th><th>碎片率</th><th>分配失败率</th></tr></thead><tbody><tr v-for="item in experiment.tasks" :key="item.taskId"><td>{{ item.algorithmType }} <span class="mono muted">#{{ item.repeatIndex }}</span></td><td>{{ statusLabels[item.status] }}</td><td class="mono">{{ metric(item.experiment?.allocationSuccessRate) }}</td><td class="mono">{{ metric(item.experiment?.averageSeatUtilization) }}</td><td class="mono">{{ metric(item.experiment?.seatPreferenceMismatchRate) }}</td><td class="mono">{{ metric(item.experiment?.timeFragmentationRate) }}</td><td class="mono">{{ metric(item.experiment?.seatAllocationFailureRate) }}</td></tr></tbody></table></div><h3 class="summary-title">GENETIC重复实验汇总（均值 ± 标准差）</h3><div class="genetic-summary"><div v-for="[key, label] in summaryMetrics" :key="key"><span>{{ label }}</span><strong class="mono">{{ experiment.geneticSummary?.[`${key}Mean`] == null ? '—' : `${percent(experiment.geneticSummary[`${key}Mean`]!)} ± ${percent(experiment.geneticSummary[`${key}StandardDeviation`]!)}` }}</strong></div></div></template>
    </section>
  </div>
</template>

<style scoped>
.selected-row { background: var(--primary-soft); }
.error-text { display: block; margin-top: 4px; color: var(--danger); }
.metrics-grid--five { grid-template-columns: repeat(5, minmax(0, 1fr)); }
@media (max-width: 1100px) { .metrics-grid--five { grid-template-columns: repeat(2, minmax(0, 1fr)); } .genetic-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .metrics-grid--five, .genetic-summary { grid-template-columns: 1fr; } }
.experiment-zone { border-color: var(--line); }
.experiment-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 16px; margin-bottom: 18px; font-size: 12px; }
.summary-title { margin-top: 24px; font-size: 14px; }
.genetic-summary { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.genetic-summary div { padding: 12px; background: transparent; border-bottom: 1px solid var(--line); }
.genetic-summary span, .genetic-summary strong { display: block; font-size: 11px; }
.genetic-summary span { margin-bottom: 7px; color: var(--muted); }
@media (max-width: 1200px) { .metrics-grid--five, .genetic-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
