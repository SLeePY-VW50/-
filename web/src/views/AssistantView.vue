<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight } from '@element-plus/icons-vue'
import http, { errorMessage } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { useDraftStore } from '../stores/draft'
import type { ReservationDraft } from '../types'
import { formatDateTime } from '../utils'
import EmptyState from '../components/EmptyState.vue'

interface ChatMessage { role: 'user' | 'assistant'; content: string }
interface DoneEvent { draft?: ReservationDraft | null; sources?: string[]; generatedAt?: string; degraded?: boolean; fallback?: string | null }

const auth = useAuthStore()
const draftStore = useDraftStore()
const router = useRouter()
const threadId = ref(crypto.randomUUID())
const messages = ref<ChatMessage[]>([])
const question = ref('')
const streaming = ref(false)
const error = ref('')
const degradedNotice = ref('')
const generatedAt = ref('')
const sources = ref<string[]>([])
const draft = ref<ReservationDraft | null>(null)
const historyLoaded = ref(false)
const messageList = ref<HTMLDivElement>()

async function loadHistory() {
  try {
    const data = await http.get<{ items: ChatMessage[] }>(`/api/ai/chat/history/${threadId.value}`)
    messages.value = data.items || []
    historyLoaded.value = true
    await scrollToBottom()
  } catch (cause) { degradedNotice.value = `历史记录暂不可用：${errorMessage(cause)}`; historyLoaded.value = true }
}
async function checkHealth() {
  try {
    const health = await http.get<{ status: string; modelConfigured: boolean }>('/api/ai/health')
    if (!health.modelConfigured) degradedNotice.value = 'AI模型未配置，当前无法生成回答。仍可使用普通预约流程。'
  } catch { degradedNotice.value = 'AI服务状态暂时无法确认；若生成失败，请改用普通预约。' }
}
async function scrollToBottom() { await nextTick(); if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight }

function handleEvent(name: string, raw: string, assistant: ChatMessage) {
  const data = JSON.parse(raw) as { answer?: string; code?: string; fallback?: string } & DoneEvent
  if (name === 'answer') assistant.content += data.answer || ''
  if (name === 'done') {
    draft.value = data.draft || null
    sources.value = data.sources || []
    generatedAt.value = data.generatedAt || ''
    if (data.degraded) degradedNotice.value = 'AI已降级回答，请核对信息并使用普通预约。'
  }
  if (name === 'error') {
    error.value = data.code === 'AI_MODEL_NOT_CONFIGURED' ? 'AI模型未配置，请使用普通预约。' : 'AI服务暂时不可用，请稍后重试或使用普通预约。'
    degradedNotice.value = error.value
  }
}

async function send() {
  const content = question.value.trim()
  if (!content || streaming.value) return
  streaming.value = true; error.value = ''; draft.value = null; sources.value = []; generatedAt.value = ''
  messages.value.push({ role: 'user', content })
  const assistant: ChatMessage = { role: 'assistant', content: '' }
  messages.value.push(assistant)
  question.value = ''
  await scrollToBottom()
  try {
    const response = await fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${auth.token}` },
      body: JSON.stringify({ threadId: threadId.value, message: content }),
    })
    if (!response.ok) {
      const body = await response.json().catch(() => null) as { code?: string } | null
      throw new Error(body?.code === 'AI_MODEL_NOT_CONFIGURED' ? 'AI模型未配置，请使用普通预约。' : `AI请求失败（HTTP ${response.status}）`)
    }
    if (!response.body) throw new Error('浏览器未收到AI流式响应')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n')
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2)
        const event = block.split('\n').find((line) => line.startsWith('event:'))?.slice(6).trim() || 'message'
        const raw = block.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('\n')
        if (raw) handleEvent(event, raw, assistant)
        boundary = buffer.indexOf('\n\n')
      }
      await scrollToBottom()
      if (done) break
    }
    if (!assistant.content && !error.value) error.value = 'AI未返回回答，请使用普通预约。'
  } catch (cause) {
    error.value = errorMessage(cause)
    degradedNotice.value = `${error.value} 普通预约入口仍可使用。`
  } finally {
    if (!assistant.content) {
      const index = messages.value.lastIndexOf(assistant)
      if (index >= 0) messages.value.splice(index, 1)
    }
    streaming.value = false
    await scrollToBottom()
  }
}
function useDraft() {
  if (!draft.value) return
  draftStore.setDraft(draft.value)
  router.push({ path: '/labs', query: { draft: '1' } })
}
async function newThread() {
  threadId.value = crypto.randomUUID(); messages.value = []; draft.value = null; sources.value = []; generatedAt.value = ''; error.value = ''
  await loadHistory()
}
onMounted(() => { loadHistory(); checkHealth() })
</script>

<template>
  <div class="page assistant-page">
    <header class="page-header"><div><span class="eyebrow">辅助起草 · 人工核对</span><h1>AI预约助手</h1><p>把需求整理成草稿；任何预约都必须回到表单核对并由你主动提交。</p></div><div class="button-row"><button class="button" @click="newThread">新会话</button><router-link class="button button--primary" to="/labs">普通预约</router-link></div></header>
    <div v-if="degradedNotice" class="degrade-alert" role="alert"><strong>AI服务降级提示</strong><span>{{ degradedNotice }}</span><router-link to="/labs" class="degrade-alert__link">改用普通预约<el-icon><ArrowRight /></el-icon></router-link></div>
    <section class="assistant-layout">
      <div class="chat-panel">
        <div class="thread-bar"><span>当前会话</span><code>{{ threadId }}</code></div>
        <div ref="messageList" class="message-list" aria-live="polite">
          <div v-if="!historyLoaded" class="loading-line">正在读取会话历史</div>
          <EmptyState v-else-if="!messages.length" title="从一次实验需求开始" description="例如：下周三下午需要2个靠窗高配工位，用于嵌入式实验。" />
          <div v-for="(message, index) in messages" :key="index" class="message" :class="`message--${message.role}`"><span class="message__role">{{ message.role === 'user' ? '你' : 'AI' }}</span><div class="message__content">{{ message.content }}<span v-if="streaming && message.role === 'assistant' && index === messages.length - 1" class="typing-cursor">▍</span></div></div>
        </div>
        <form class="question-box" @submit.prevent="send"><label for="ai-question">实验预约需求</label><textarea id="ai-question" v-model="question" rows="3" maxlength="4000" :disabled="streaming" placeholder="说明日期、时间、人数、实验室或工位偏好" @keydown.ctrl.enter="send" /><div><span>Ctrl + Enter 发送</span><button class="button button--primary" type="submit" :disabled="streaming || !question.trim()">{{ streaming ? '正在生成…' : '发送需求' }}</button></div></form>
      </div>
      <aside class="answer-panel">
        <section class="panel"><div class="panel__header"><h2>生成信息</h2></div><dl class="detail-list"><dt>生成时间</dt><dd class="mono">{{ generatedAt ? formatDateTime(generatedAt) : '等待本次回答完成' }}</dd><dt>信息来源</dt><dd><ul v-if="sources.length" class="source-list"><li v-for="source in sources" :key="source">{{ source }}</li></ul><span v-else>暂无来源</span></dd></dl><p class="verify-note">请核对后提交。AI内容不会自动创建预约。</p></section>
        <section v-if="draft" class="panel draft-card"><span class="eyebrow">预约草稿</span><h2>检测到预约草稿</h2><dl class="detail-list"><dt>日期</dt><dd class="mono">{{ draft.date }}</dd><dt>时段</dt><dd class="mono">{{ draft.startTime }}–{{ draft.endTime }}</dd><dt>方式</dt><dd>{{ draft.reservationMode }} · {{ draft.seatCount }}人/工位</dd><dt>偏好</dt><dd>{{ draft.preferences.minSpecLevel || '不限配置' }} / {{ draft.preferences.zoneCode || '不限区域' }}</dd></dl><button class="button button--primary draft-button" @click="useDraft">回填到预约表单</button><small>仅写入本地草稿并跳转，不会自动POST。</small></section>
      </aside>
    </section>
  </div>
</template>

<style scoped>
.assistant-page { max-width: 1440px; }
.degrade-alert { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; padding: 12px 14px; color: #8a5a12; background: #fff4e4; border: 1px solid #efd7b0; border-radius: 6px; font-size: 13px; }
.degrade-alert strong { font-size: 13px; }.degrade-alert span { flex: 1; min-width: 240px; }
.degrade-alert__link { display: inline-flex; align-items: center; gap: 5px; white-space: nowrap; }
.assistant-layout { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(290px, .75fr); gap: 20px; }
.chat-panel { display: grid; grid-template-rows: auto minmax(360px, 56vh) auto; min-width: 0; overflow: hidden; background: var(--paper); border: 1px solid var(--line); border-radius: 8px; }
.thread-bar { display: flex; justify-content: space-between; gap: 12px; padding: 12px 16px; color: var(--muted); background: #f7f4ee; border-bottom: 1px solid var(--line); font-size: 12px; }
.thread-bar code { overflow: hidden; text-overflow: ellipsis; font-family: Consolas, monospace; }
.message-list { overflow-y: auto; padding: 23px; }
.message { display: grid; grid-template-columns: 34px minmax(0, 1fr); gap: 11px; margin-bottom: 22px; }
.message__role { display: grid; place-items: center; width: 32px; height: 32px; color: var(--paper); background: var(--ink); border-radius: 6px; font-size: 12px; font-weight: 700; }
.message--assistant .message__role { background: var(--primary); }
.message__content { padding: 12px 14px; background: #f7f4ee; border: 1px solid var(--line); border-radius: 6px; font-size: 14px; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.message--user .message__content { background: var(--primary-soft); border-color: #c9e3d8; }
.typing-cursor { color: var(--primary); }
.question-box { padding: 14px 16px; background: #f7f4ee; border-top: 1px solid var(--line); }
.question-box label { display: block; margin-bottom: 8px; color: var(--body); font-size: 13px; font-weight: 600; }
.question-box textarea { width: 100%; min-height: 72px; padding: 10px 12px; resize: vertical; background: var(--paper); border: 1px solid var(--line); border-radius: 6px; outline: none; font: 14px/1.6 -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei UI', sans-serif; }
.question-box textarea:focus { border-color: var(--primary); box-shadow: 0 0 0 3px rgb(31 107 87 / 16%); }
.question-box > div { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; color: #8b96a6; font-size: 10px; }
.answer-panel .panel { margin-bottom: 18px; }
.source-list { margin: 0; padding-left: 18px; }
.verify-note { margin: 16px 0 0; padding: 10px 12px; color: #8a5a12; background: #fff4e4; border-radius: 6px; font-size: 13px; }
.draft-card { border-color: var(--line); }
.draft-card h2 { margin-top: 8px; }
.draft-button { width: 100%; margin-top: 19px; }
.draft-card > small { display: block; margin-top: 9px; color: var(--muted); font-size: 10px; }
@media (max-width: 980px) { .assistant-layout { grid-template-columns: 1fr; }.chat-panel { grid-template-rows: auto minmax(330px, 50vh) auto; } }
</style>
