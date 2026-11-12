<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import axios from 'axios'
import { CircleCheck } from '@element-plus/icons-vue'
import type { Lab, TerminalCheckin } from '../types'
import { formatDateTime, statusLabels } from '../utils'

interface ApiBody<T> { code: number; message: string; data: T }
interface Session { deviceId: number; lab: Lab; expiresIn: number }
const terminal = reactive({ code: import.meta.env.DEV ? 'LAB-A-TERM' : '', secret: import.meta.env.DEV ? 'Terminal@2026' : '' })
const account = reactive({ account: '', password: '' })
const step = ref<'session' | 'account' | 'choice' | 'success'>('session')
const session = ref<Session | null>(null)
const result = ref<TerminalCheckin | null>(null)
const choiceToken = ref('')
const selectedReservation = ref<number | null>(null)
const sessionExpiresAt = ref(0)
const choiceExpiresAt = ref(0)
const clock = ref(Date.now())
const loading = ref(false)
const error = ref('')
let timer: ReturnType<typeof setInterval> | undefined
const sessionSeconds = computed(() => Math.max(0, Math.ceil((sessionExpiresAt.value - clock.value) / 1000)))
const choiceSeconds = computed(() => Math.max(0, Math.ceil((choiceExpiresAt.value - clock.value) / 1000)))
const durationText = (seconds: number) => `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`

async function post<T>(url: string, payload: unknown): Promise<T> {
  try {
    const response = await axios.post<ApiBody<T>>(url, payload, { withCredentials: true, timeout: 15000 })
    if (response.data.code !== 0) throw new Error(response.data.message || '请求失败')
    return response.data.data
  } catch (cause) {
    if (axios.isAxiosError<ApiBody<unknown>>(cause)) throw new Error(cause.response?.data?.message || cause.message)
    throw cause
  }
}
function clearSensitive() { account.password = ''; choiceToken.value = ''; selectedReservation.value = null }
function resetSession(message = '') {
  clearSensitive(); session.value = null; result.value = null; step.value = 'session'; sessionExpiresAt.value = 0; choiceExpiresAt.value = 0; error.value = message
}
async function createSession() {
  if (!terminal.code.trim() || !terminal.secret) { error.value = '请输入终端编码和终端秘密'; return }
  loading.value = true; error.value = ''
  try {
    session.value = await post<Session>('/api/checkin/terminal/session', { terminalCode: terminal.code.trim(), terminalSecret: terminal.secret })
    sessionExpiresAt.value = Date.now() + session.value.expiresIn * 1000
    terminal.secret = ''
    step.value = 'account'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '终端验证失败' } finally { loading.value = false; terminal.secret = '' }
}
async function checkin() {
  if (!account.account.trim() || !account.password) { error.value = '请输入学号/工号和密码'; return }
  loading.value = true; error.value = ''
  try {
    const data = await post<TerminalCheckin>('/api/checkin/terminal', { account: account.account.trim(), password: account.password })
    account.password = ''
    if (data.choiceRequired) {
      result.value = data; choiceToken.value = data.choiceToken || ''; choiceExpiresAt.value = Date.now() + (data.expiresIn || 0) * 1000; selectedReservation.value = data.reservations[0]?.reservationId || null; step.value = 'choice'
    } else {
      result.value = data; choiceToken.value = ''; step.value = 'success'
    }
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '签到失败' } finally { loading.value = false; account.password = '' }
}
async function selectReservation() {
  if (!selectedReservation.value || !choiceToken.value) return
  loading.value = true; error.value = ''
  try {
    result.value = await post<TerminalCheckin>('/api/checkin/terminal/select', { choiceToken: choiceToken.value, reservationId: selectedReservation.value })
    choiceToken.value = ''; choiceExpiresAt.value = 0; step.value = 'success'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '选择预约失败' } finally { loading.value = false }
}
function nextCheckin() { clearSensitive(); account.account = ''; result.value = null; error.value = ''; step.value = 'account' }
onBeforeUnmount(() => { if (timer) clearInterval(timer); clearSensitive() })
timer = setInterval(() => {
  clock.value = Date.now()
  if (session.value && sessionExpiresAt.value <= clock.value) resetSession('终端会话已超时，请重新验证终端。')
  else if (step.value === 'choice' && choiceExpiresAt.value <= clock.value) {
    clearSensitive(); result.value = null; step.value = 'account'; error.value = '预约选择已超时，请重新验证账号。'
  }
}, 1000)
</script>

<template>
  <main class="terminal-page">
    <header class="terminal-header"><div class="terminal-brand"><strong>LabSpace</strong><small>签到终端 · 不创建桌面会话</small></div><router-link to="/login">工作人员登录</router-link></header>
    <section class="terminal-workspace">
      <div class="terminal-intro"><span class="eyebrow">签到终端</span><h1>到场签到</h1><p>此页面不会创建学生桌面会话。终端与账号分步验证，密码和终端秘密不会被回显。</p><div class="terminal-steps" aria-label="签到步骤"><span :class="{ active: step === 'session' }">1 终端验证</span><span :class="{ active: step === 'account' || step === 'choice' }">2 账号签到</span><span :class="{ active: step === 'success' }">3 完成</span></div></div>
      <div class="terminal-card">
        <div v-if="session" class="terminal-session"><div><span>当前终端</span><strong>{{ session.lab.name }}</strong><small>{{ session.lab.location }} · #{{ session.deviceId }}</small></div><div class="session-clock"><span>会话剩余</span><strong class="mono">{{ durationText(sessionSeconds) }}</strong></div></div>
        <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
        <form v-if="step === 'session'" class="terminal-form" @submit.prevent="createSession"><span class="eyebrow">步骤 1</span><h2>验证签到终端</h2><p>终端秘密仅用于本次验证，提交后立即清空。</p><label class="field"><span>终端编码</span><input v-model="terminal.code" autocomplete="off" placeholder="如 LAB-A-TERM"></label><label class="field"><span>终端秘密</span><input v-model="terminal.secret" type="password" autocomplete="off" placeholder="输入终端秘密"></label><button class="button button--primary terminal-submit" type="submit" :disabled="loading">{{ loading ? '正在验证…' : '验证终端' }}</button></form>
        <form v-else-if="step === 'account'" class="terminal-form" @submit.prevent="checkin"><span class="eyebrow">步骤 2</span><h2>验证到场账号</h2><p>使用本人学号/工号和密码签到，不会登录后台。</p><label class="field"><span>学号或工号</span><input v-model="account.account" autocomplete="username" placeholder="输入本人账号"></label><label class="field"><span>账号密码</span><input v-model="account.password" type="password" autocomplete="current-password" placeholder="签到后立即清空"></label><button class="button button--primary terminal-submit" type="submit" :disabled="loading">{{ loading ? '正在签到…' : '确认签到' }}</button></form>
        <div v-else-if="step === 'choice' && result" class="terminal-form"><span class="eyebrow">多条可签到预约</span><h2>选择本次预约</h2><p>账号对应多条可签到预约，请选择一条。选择有效期 <strong class="mono">{{ durationText(choiceSeconds) }}</strong>。</p><div class="choice-list"><label v-for="item in result.reservations" :key="item.reservationId" :class="{ selected: selectedReservation === item.reservationId }"><input v-model.number="selectedReservation" type="radio" :value="item.reservationId"><span><strong class="mono">#{{ item.reservationId }} · {{ formatDateTime(item.startTime).slice(0, 16) }}</strong><small>{{ statusLabels[item.reservationMode] }} · {{ item.attendeeCount }}人 <template v-if="item.seats.length">· {{ item.seats.map((seat) => seat.seatCode).join('、') }}</template></small></span></label></div><button class="button button--primary terminal-submit" :disabled="loading || !selectedReservation" @click="selectReservation">{{ loading ? '正在确认…' : '按所选预约签到' }}</button></div>
        <div v-else-if="step === 'success' && result" class="terminal-form terminal-success"><span class="success-mark" aria-hidden="true"><el-icon><CircleCheck /></el-icon></span><span class="eyebrow">签到已记录</span><h2>{{ result.alreadyCheckedIn ? '此前已完成签到' : '签到成功' }}</h2><p>预约 #{{ result.reservationId }} · {{ statusLabels[result.status || ''] }}</p><dl class="detail-list"><dt>签到时间</dt><dd class="mono">{{ formatDateTime(result.checkinTime) }}</dd><dt>签到渠道</dt><dd>{{ result.checkinChannel || 'TERMINAL' }}</dd></dl><button class="button button--primary terminal-submit" @click="nextCheckin">继续下一位签到</button></div>
      </div>
    </section>
    <footer class="terminal-footer"><span>请勿在终端保存个人密码</span><span>会话 Cookie 仅用于本机签到</span></footer>
  </main>
</template>

<style scoped>
.terminal-page { min-height: 100dvh; display: flex; flex-direction: column; background: var(--canvas); }
.terminal-header { display: flex; align-items: center; justify-content: space-between; height: 48px; padding: 0 24px; background: var(--paper); border-bottom: 1px solid var(--line); }
.terminal-brand { display: flex; align-items: baseline; gap: 12px; }
.terminal-brand strong { color: var(--ink); font-size: 16px; font-weight: 600; }
.terminal-brand small { color: var(--muted); font-size: 13px; }
.terminal-header a { color: var(--muted); font-size: 14px; }
.terminal-header a:hover { color: var(--ink); text-decoration: none; }
.terminal-workspace { display: grid; gap: 18px; width: min(480px, calc(100% - 32px)); margin: 0 auto; padding: 32px 0 40px; }
.terminal-intro h1 { margin: 0 0 8px; font-size: 22px; font-weight: 600; }
.terminal-intro > p { margin: 0; color: var(--body); font-size: 14px; line-height: 1.65; }
.terminal-steps { display: flex; gap: 8px; margin-top: 16px; }
.terminal-steps span { min-height: 32px; padding: 6px 0; color: var(--muted); background: transparent; border-bottom: 1px solid transparent; font-size: 13px; }
.terminal-steps span.active { color: var(--ink); border-bottom-color: var(--ink); font-weight: 500; }
.terminal-card { padding: 0; background: transparent; border: 0; }
.terminal-session { display: flex; justify-content: space-between; gap: 16px; margin-bottom: 16px; padding: 12px 0; background: transparent; border-bottom: 1px solid var(--line); }
.terminal-session span, .terminal-session strong, .terminal-session small { display: block; }
.terminal-session span { color: var(--muted); font-size: 12px; }
.terminal-session strong { margin: 2px 0; color: var(--ink); font-size: 14px; }
.terminal-session small { color: var(--muted); font-size: 12px; }
.session-clock { text-align: right; }
.session-clock strong { color: var(--ink); font-size: 18px; }
.terminal-form { display: grid; gap: 12px; }
.terminal-form h2 { margin: 0; font-size: 18px; font-weight: 600; }
.terminal-form > p { margin: 0; color: var(--body); font-size: 14px; line-height: 1.55; }
.terminal-submit { min-height: 44px; }
.choice-list { display: grid; gap: 8px; max-height: 270px; overflow-y: auto; }
.choice-list label { display: flex; gap: 10px; min-height: 44px; padding: 10px 0; background: transparent; border-bottom: 1px solid var(--line); cursor: pointer; }
.choice-list label.selected { color: var(--ink); font-weight: 500; }
.choice-list input { accent-color: var(--primary); }
.choice-list strong, .choice-list small { display: block; }
.choice-list strong { font-size: 13px; }
.choice-list small { margin-top: 4px; color: var(--muted); font-size: 12px; }
.terminal-success { text-align: center; }
.success-mark { display: grid; place-items: center; width: 32px; height: 32px; margin: 0 auto 4px; color: var(--ink); font-size: 22px; }
.terminal-success .detail-list { margin: 8px 0 0; padding: 12px 0; text-align: left; }
.terminal-footer { display: flex; justify-content: space-between; gap: 12px; margin-top: auto; padding: 16px 24px; color: var(--muted); background: var(--paper); border-top: 1px solid var(--line); font-size: 12px; }
@media (max-width: 560px) {
  .terminal-header, .terminal-footer { padding-inline: 14px; }
  .terminal-brand small { display: none; }
  .terminal-workspace { width: min(100% - 28px, 480px); padding-top: 24px; }
  .terminal-intro h1 { font-size: 28px; }
  .terminal-card { padding: 16px; }
}
</style>
