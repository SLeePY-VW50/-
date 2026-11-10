<script setup lang="ts">
import { onMounted, ref } from 'vue'
import http, { errorMessage } from '../../api/http'
import type { Lab, LabRules, TimeRule } from '../../types'
import { timeOnly } from '../../utils'
import EmptyState from '../../components/EmptyState.vue'

const labs = ref<Lab[]>([])
const labId = ref<number | null>(null)
const rules = ref<LabRules | null>(null)
const editable = ref<TimeRule[]>([])
const loading = ref(false)
const savingId = ref<number | null>(null)
const error = ref('')
const success = ref('')
const weekdays = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']
const startOptions = Array.from({ length: 48 }, (_, index) => `${String(Math.floor(index / 2)).padStart(2, '0')}:${index % 2 ? '30' : '00'}`)
const endOptions = [...startOptions.slice(1), '24:00']

async function loadLabs() {
  try { labs.value = await http.get<Lab[]>('/api/labs'); if (labs.value.length) { labId.value = labs.value[0].labId; await loadRules() } } catch (cause) { error.value = errorMessage(cause) }
}
async function loadRules() {
  if (!labId.value) return
  loading.value = true; error.value = ''
  try {
    rules.value = await http.get<LabRules>(`/api/labs/${labId.value}/rules`)
    editable.value = rules.value.weeklyRules.map((rule) => ({ ...rule, openTime: timeOnly(rule.openTime), closeTime: timeOnly(rule.closeTime, true) })).sort((a, b) => a.dayOfWeek - b.dayOfWeek)
  } catch (cause) { error.value = errorMessage(cause); rules.value = null; editable.value = [] } finally { loading.value = false }
}
async function save(rule: TimeRule) {
  if (!labId.value) return
  savingId.value = rule.ruleId; error.value = ''; success.value = ''
  try {
    await http.put(`/api/admin/labs/${labId.value}/rules/${rule.ruleId}`, { openTime: rule.openTime, closeTime: rule.closeTime, minDurationMinutes: Number(rule.minDurationMinutes), maxDurationMinutes: Number(rule.maxDurationMinutes), advanceMinDays: Number(rule.advanceMinDays), advanceMaxDays: Number(rule.advanceMaxDays) })
    success.value = `${weekdays[rule.dayOfWeek]}开放规则已保存`
    await loadRules()
  } catch (cause) { error.value = errorMessage(cause) } finally { savingId.value = null }
}
onMounted(loadLabs)
</script>

<template>
  <div class="page"><header class="page-header"><div><span class="eyebrow">系统管理 · 开放规则</span><h1>每周开放规则</h1><p>逐日编辑真实规则，时间按30分钟对齐，关闭时间支持24:00。</p></div><label class="field"><span>实验室</span><select v-model.number="labId" @change="loadRules"><option v-for="lab in labs" :key="lab.labId" :value="lab.labId">{{ lab.name }}</option></select></label></header><div v-if="success" class="success-banner" role="status">{{ success }}</div><div v-if="error" class="error-banner" role="alert">{{ error }}</div><div v-if="loading" class="loading-line">正在读取每周规则</div><EmptyState v-else-if="!error && !editable.length" title="暂无开放规则" /><section v-else class="rule-list"><article v-for="rule in editable" :key="rule.ruleId" class="panel rule-row"><div class="rule-day"><strong>{{ weekdays[rule.dayOfWeek] || `星期${rule.dayOfWeek}` }}</strong><span class="mono">RULE #{{ rule.ruleId }}</span></div><div class="rule-fields"><label class="field"><span>开放</span><select v-model="rule.openTime"><option v-for="time in startOptions" :key="time">{{ time }}</option></select></label><label class="field"><span>关闭</span><select v-model="rule.closeTime"><option v-for="time in endOptions" :key="time">{{ time }}</option></select></label><label class="field"><span>最短/分钟</span><input v-model.number="rule.minDurationMinutes" type="number" min="1"></label><label class="field"><span>最长/分钟</span><input v-model.number="rule.maxDurationMinutes" type="number" min="1"></label><label class="field"><span>最少提前/天</span><input v-model.number="rule.advanceMinDays" type="number" min="0"></label><label class="field"><span>最多提前/天</span><input v-model.number="rule.advanceMaxDays" type="number" min="0"></label><button class="button button--primary" :disabled="savingId === rule.ruleId" @click="save(rule)">{{ savingId === rule.ruleId ? '保存中…' : '保存' }}</button></div></article></section><section v-if="rules" class="panel rule-notes"><h2>关联签到与取消规则</h2><dl class="detail-list"><dt>免费取消</dt><dd>开始前 {{ rules.cancellation.freeBeforeHours }} 小时</dd><dt>签到窗口</dt><dd>开始前 {{ rules.checkin.beforeMinutes }} 分钟，宽限 {{ rules.checkin.graceMinutes }} 分钟</dd><dt>选择有效期</dt><dd>{{ rules.checkin.choiceExpireSeconds }} 秒</dd><dt>失败锁定</dt><dd>失败 {{ rules.checkin.maxFailedAttempts }} 次后锁定 {{ rules.checkin.lockoutMinutes }} 分钟</dd></dl></section></div>
</template>

<style scoped>
.rule-list { display: grid; gap: 12px; }.rule-row { display: grid; grid-template-columns: 100px minmax(0, 1fr); align-items: end; margin-bottom: 0; padding: 17px 19px; box-shadow: none; }.rule-day strong, .rule-day span { display: block; }.rule-day strong { font-size: 17px; font-weight: 700; }.rule-day span { margin-top: 6px; color: var(--muted); font-size: 12px; }.rule-fields { display: grid; grid-template-columns: 1fr 1fr repeat(4, .8fr) auto; gap: 10px; align-items: end; }.rule-notes { max-width: 620px; margin-top: 20px; }
@media (max-width: 1200px) { .rule-row { grid-template-columns: 1fr; gap: 14px; }.rule-fields { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 600px) { .rule-fields { grid-template-columns: 1fr 1fr; } }
</style>
