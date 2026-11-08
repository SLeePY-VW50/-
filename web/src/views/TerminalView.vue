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
    <header class="terminal-header"><div class="terminal-brand"><span>L</span><div><strong>实验室签到终端</strong><small>PUBLIC CHECK-IN · NO DESKTOP SESSION</small></div></div><router-link to="/login">工作人员登录</router-link></header>
    <section class="terminal-workspace">
      <div class="terminal-intro"><span class="eyebrow">CHECK-IN / 30 MIN SLOTS</span><h1>到场，确认，<br>开始实验。</h1><p>此页面不会创建学生桌面会话。终端身份与账号身份分步验证，敏感信息不会被回显。</p><div class="terminal-steps"><span :class="{ active: step === 'session' }">01 终端验证</span><span :class="{ active: step === 'account' || step === 'choice' }">02 账号签到</span><span :class="{ active: step === 'success' }">03 完成</span></div></div>
      <div class="terminal-card">
        <div v-if="session" class="terminal-session"><div><span>当前终端</span><strong>{{ session.lab.name }}</strong><small>{{ session.lab.location }} · #{{ session.deviceId }}</small></div><div class="session-clock"><span>会话剩余</span><strong class="mono">{{ durationText(sessionSeconds) }}</strong></div></div>
        <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
        <form v-if="step === 'session'" class="terminal-form" @submit.prevent="createSession"><span class="eyebrow">STEP 01</span><h2>验证签到终端</h2><p>终端秘密仅用于本次验证，提交后立即清空。</p><label class="field"><span>终端编码</span><input v-model="terminal.code" autocomplete="off" placeholder="如 LAB-A-TERM"></label><label class="field"><span>终端秘密</span><input v-model="terminal.secret" type="password" autocomplete="off" placeholder="输入终端秘密"></label><button class="button button--primary terminal-submit" type="submit" :disabled="loading">{{ loading ? '正在验证…' : '验证终端' }}</button></form>
        <form v-else-if="step === 'account'" class="terminal-form" @submit.prevent="checkin"><span class="eyebrow">STEP 02</span><h2>验证到场账号</h2><p>使用本人学号/工号和密码签到，不会登录后台。</p><label class="field"><span>学号或工号</span><input v-model="account.account" autocomplete="username" placeholder="输入本人账号"></label><label class="field"><span>账号密码</span><input v-model="account.password" type="password" autocomplete="current-password" placeholder="签到后立即清空"></label><button class="button button--primary terminal-submit" type="submit" :disabled="loading">{{ loading ? '正在签到…' : '确认签到' }}</button></form>
        <div v-else-if="step === 'choice' && result" class="terminal-form"><span class="eyebrow">MULTIPLE RESERVATIONS</span><h2>选择本次预约</h2><p>账号对应多条可签到预约，请选择一条。选择有效期 <strong class="mono">{{ durationText(choiceSeconds) }}</strong>。</p><div class="choice-list"><label v-for="item in result.reservations" :key="item.reservationId" :class="{ selected: selectedReservation === item.reservationId }"><input v-model.number="selectedReservation" type="radio" :value="item.reservationId"><span><strong class="mono">#{{ item.reservationId }} · {{ formatDateTime(item.startTime).slice(0, 16) }}</strong><small>{{ statusLabels[item.reservationMode] }} · {{ item.attendeeCount }}人 <template v-if="item.seats.length">· {{ item.seats.map((seat) => seat.seatCode).join('、') }}</template></small></span></label></div><button class="button button--primary terminal-submit" :disabled="loading || !selectedReservation" @click="selectReservation">{{ loading ? '正在确认…' : '按所选预约签到' }}</button></div>
        <div v-else-if="step === 'success' && result" class="terminal-form terminal-success"><span class="success-mark" aria-hidden="true"><el-icon><CircleCheck /></el-icon></span><span class="eyebrow">CHECK-IN RECORDED</span><h2>{{ result.alreadyCheckedIn ? '此前已完成签到' : '签到成功' }}</h2><p>预约 #{{ result.reservationId }} · {{ statusLabels[result.status || ''] }}</p><dl class="detail-list"><dt>签到时间</dt><dd class="mono">{{ formatDateTime(result.checkinTime) }}</dd><dt>签到渠道</dt><dd>{{ result.checkinChannel || 'TERMINAL' }}</dd></dl><button class="button button--primary terminal-submit" @click="nextCheckin">继续下一位签到</button></div>
      </div>
    </section>
    <footer class="terminal-footer"><span>请勿在终端保存个人密码</span><span class="mono">HTTPONLY COOKIE / SAME-SITE STRICT</span></footer>
  </main>
</template>

<style scoped>
.terminal-page { min-height: 100dvh; display: flex; flex-direction: column; }
.terminal-header { display: flex; align-items: center; justify-content: space-between; height: 78px; padding: 0 5vw; background: rgb(255 255 255 / 88%); border-bottom: 1px solid var(--line); backdrop-filter: blur(14px); }
.terminal-brand { display: flex; align-items: center; gap: 12px; }.terminal-brand > span { display: grid; place-items: center; width: 36px; height: 36px; color: #fff; background: var(--primary); border-radius: 8px; font-size: 15px; font-weight: 600; }.terminal-brand strong, .terminal-brand small { display: block; }.terminal-brand strong { color: var(--primary); font-size: 15px; font-weight: 600; }.terminal-brand small { margin-top: 4px; color: #898989; font: 8px Consolas, monospace; }.terminal-header a { padding: 9px 13px; color: #374151; border: 1px solid var(--line); border-radius: 8px; font-size: 12px; font-weight: 600; }.terminal-header a:hover { color: var(--primary); background: #f5f5f5; text-decoration: none; }
.terminal-workspace { flex: 1; display: grid; grid-template-columns: minmax(300px, .9fr) minmax(420px, 1.1fr); gap: 7vw; align-items: center; width: min(1160px, 90vw); margin: 0 auto; padding: 48px 0; }
.terminal-intro h1 { margin: 17px 0 20px; font-size: clamp(42px, 5vw, 64px); font-weight: 700; line-height: 1.17; letter-spacing: -.055em; }.terminal-intro > p { max-width: 430px; color: var(--muted); font-size: 13px; line-height: 1.9; }
.terminal-steps { display: grid; gap: 0; margin-top: 35px; }.terminal-steps span { position: relative; padding: 0 0 23px 25px; color: #939ead; font-size: 11px; font-weight: 500; }.terminal-steps span::before { position: absolute; top: 2px; left: 0; width: 8px; height: 8px; background: #d8dde6; border: 3px solid var(--wash); border-radius: 50%; box-shadow: 0 0 0 1px #d8dde6; content: ''; }.terminal-steps span:not(:last-child)::after { position: absolute; top: 15px; bottom: 2px; left: 5px; width: 1px; background: #dfe4eb; content: ''; }.terminal-steps span.active { color: var(--primary); font-weight: 700; }.terminal-steps span.active::before { background: var(--primary); box-shadow: 0 0 0 1px var(--primary); }
.terminal-card { min-height: 450px; padding: 32px; background: #fff; border: 1px solid var(--line); border-radius: 16px; box-shadow: 0 4px 12px rgb(0 0 0 / 8%); }
.terminal-session { display: flex; justify-content: space-between; gap: 16px; margin: -10px -10px 25px; padding: 14px; background: #f5f5f5; border: 1px solid var(--line); border-radius: 12px; }.terminal-session span, .terminal-session strong, .terminal-session small { display: block; }.terminal-session span { color: #6b7280; font-size: 10px; }.terminal-session strong { margin: 4px 0; font-size: 13px; }.terminal-session small { color: #898989; font-size: 10px; }.session-clock { text-align: right; }.session-clock strong { color: var(--primary); }
.terminal-form { display: grid; gap: 19px; }.terminal-form h2 { margin: 0; font-size: 28px; font-weight: 600; letter-spacing: -.04em; }.terminal-form > p { margin: -8px 0 2px; color: var(--muted); font-size: 13px; line-height: 1.5; }.terminal-form .field input { min-height: 42px; }.terminal-submit { min-height: 44px; margin-top: 4px; }
.choice-list { display: grid; gap: 9px; max-height: 270px; overflow-y: auto; }.choice-list label { display: flex; gap: 11px; padding: 13px; border: 1px solid var(--line); border-radius: 8px; cursor: pointer; transition: background .16s ease, border-color .16s ease; }.choice-list label.selected { background: #f5f5f5; border-color: var(--primary); }.choice-list input { accent-color: var(--primary); }.choice-list strong, .choice-list small { display: block; }.choice-list strong { font-size: 12px; }.choice-list small { margin-top: 5px; color: var(--muted); font-size: 11px; }
.terminal-success { text-align: center; }.success-mark { display: grid; place-items: center; width: 62px; height: 62px; margin: 0 auto 7px; color: #fff; background: var(--green); border-radius: 50%; font-size: 28px; }.terminal-success .detail-list { margin: 12px 0; padding: 15px; background: #f8f9fa; border-radius: 12px; text-align: left; }
.terminal-footer { display: flex; justify-content: space-between; padding: 19px 5vw; color: #8c97a7; border-top: 1px solid var(--line); font-size: 10px; }
@media (max-width: 800px) { .terminal-header { height: 70px; padding-inline: 20px; }.terminal-brand small { display: none; }.terminal-workspace { grid-template-columns: 1fr; gap: 25px; width: min(100% - 32px, 560px); padding: 31px 0; }.terminal-intro h1 { margin-block: 12px; font-size: 39px; }.terminal-intro br { display: none; }.terminal-intro > p { margin-bottom: 0; }.terminal-steps { display: none; }.terminal-card { min-height: 0; padding: 23px; border-radius: 17px; }.terminal-footer { padding-inline: 20px; }.terminal-footer .mono { display: none; } }
</style>
