<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import markUrl from '../assets/labspace-mark.svg'
import http, { errorMessage } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { today } from '../utils'
import type { User } from '../types'

const isDev = import.meta.env.DEV
const accounts = [
  { account: '20240001', role: '学生' },
  { account: 'T1001', role: '教师' },
  { account: 'L1001', role: '实验室管理员' },
  { account: 'A1001', role: '系统管理员' },
]
const periods = [
  { name: '上午', range: '08:30–12:30', cells: ['08:30', '09:15', '10:05', '11:00', '11:45'] },
  { name: '下午', range: '13:30–17:30', cells: ['13:30', '14:15', '15:05', '16:00', '16:45'] },
  { name: '晚上', range: '18:30–20:50', cells: ['18:30', '19:15', '20:05'] },
]
const form = reactive({ account: '', password: '' })
const registerForm = reactive({ account: '', name: '', password: '', confirm: '' })
const mode = ref<'login' | 'register'>('login')
const loading = ref(false)
const registering = ref(false)
const error = ref('')
const success = ref('')
const now = ref({ period: -1, top: '0%', label: '' })
let clock: ReturnType<typeof setInterval> | undefined
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const todayLabel = computed(() => {
  const value = new Date()
  return `${value.getFullYear()} 年 ${value.getMonth() + 1} 月 ${value.getDate()} 日`
})

function cellClass(periodIndex: number, cellIndex: number) {
  if (periodIndex === 2) return 'cell cell--closed'
  if (periodIndex === 0 && cellIndex === 1) return 'cell cell--occupied'
  if (periodIndex === 1 && cellIndex <= 1) return 'cell cell--selected'
  return 'cell'
}
function placeNow() {
  const current = new Date()
  const mins = current.getHours() * 60 + current.getMinutes()
  const spans = [[510, 750], [810, 1050], [1110, 1250]]
  const period = spans.findIndex(([start, end]) => mins >= start && mins < end)
  const span = spans[period]
  now.value = {
    period,
    top: span ? `calc(48px + (100% - 48px) * ${(mins - span[0]) / (span[1] - span[0])})` : '0%',
    label: `${String(current.getHours()).padStart(2, '0')}:${String(current.getMinutes()).padStart(2, '0')}`,
  }
}
function fill(account: string) { form.account = account; form.password = 'Lab@2026'; error.value = '' }
async function login() {
  if (!form.account.trim() || !form.password) { error.value = '请输入账号和密码'; return }
  loading.value = true
  error.value = ''
  success.value = ''
  try {
    const result = await http.post<{ token: string; user: User }>('/api/auth/login', { account: form.account.trim(), password: form.password })
    auth.setAuth(result.token, result.user)
    form.password = ''
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//') ? route.query.redirect : '/labs'
    await router.replace(redirect)
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
function switchMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  error.value = ''
  success.value = ''
}
async function register() {
  const account = registerForm.account.trim()
  const name = registerForm.name.trim()
  if (!account || !name || !registerForm.password) { error.value = '请填写账号、姓名和密码'; return }
  if (registerForm.password !== registerForm.confirm) { error.value = '两次输入的密码不一致'; return }
  registering.value = true
  error.value = ''
  success.value = ''
  try {
    await http.post<User>('/api/auth/register', { account, name, password: registerForm.password })
    form.account = account
    form.password = ''
    registerForm.account = ''
    registerForm.name = ''
    registerForm.password = ''
    registerForm.confirm = ''
    mode.value = 'login'
    success.value = `账号 ${account} 注册成功，请使用该账号登录`
  } catch (cause) { error.value = errorMessage(cause) } finally { registering.value = false }
}
onMounted(() => { placeNow(); clock = setInterval(placeNow, 30_000) })
onBeforeUnmount(() => { if (clock) clearInterval(clock) })
</script>

<template>
  <main class="gate">
    <header class="gate__top">
      <span class="brand"><img class="brand__mark" :src="markUrl" alt="" width="32" height="32" aria-hidden="true" />LabSpace</span>
      <router-link to="/terminal">终端签到</router-link>
    </header>
    <div class="gate__body">
      <aside class="rail">
        <form class="login-box" @submit.prevent="mode === 'login' ? login() : register()">
          <h1>{{ mode === 'login' ? '一天十三节。' : '注册学生账号。' }}</h1>
          <p class="lead">{{ mode === 'login' ? '先看清占用、容量和审批，再决定预约或签到。' : '新账号默认学生角色，注册后即可用该账号登录。' }}</p>
          <template v-if="mode === 'login'">
            <label class="field"><span>账号</span><input v-model="form.account" autocomplete="username" autofocus placeholder="学号或工号"></label>
            <label class="field"><span>密码</span><input v-model="form.password" type="password" autocomplete="current-password" placeholder="密码"></label>
            <button class="go" type="submit" :disabled="loading">{{ loading ? '正在验证…' : '进入' }}</button>
            <button class="switch" type="button" @click="switchMode">注册用户</button>
          </template>
          <template v-else>
            <label class="field"><span>账号</span><input v-model="registerForm.account" autocomplete="username" maxlength="32" placeholder="学号或工号"></label>
            <label class="field"><span>姓名</span><input v-model="registerForm.name" autocomplete="name" maxlength="64" placeholder="真实姓名"></label>
            <label class="field"><span>密码</span><input v-model="registerForm.password" type="password" autocomplete="new-password" placeholder="密码"></label>
            <label class="field"><span>确认密码</span><input v-model="registerForm.confirm" type="password" autocomplete="new-password" placeholder="再次输入密码"></label>
            <button class="go" type="submit" :disabled="registering">{{ registering ? '正在注册…' : '注册' }}</button>
            <button class="switch" type="button" @click="switchMode">返回登录</button>
          </template>
          <p v-if="success" class="success-banner" role="status">{{ success }}</p>
          <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
          <div v-if="isDev && mode === 'login'" class="hints">
            本地账号
            <button v-for="item in accounts" :key="item.account" type="button" @click="fill(item.account)">{{ item.role }} {{ item.account }}</button>
          </div>
        </form>
      </aside>
      <section class="ruler-main" :aria-label="`时间尺示意，${today()}`">
        <div class="main-head"><b>{{ todayLabel }}</b><span>一节一格</span></div>
        <div class="periods" aria-hidden="true">
          <div v-for="(period, periodIndex) in periods" :key="period.name" class="period">
            <div class="period-h"><b>{{ period.name }}</b>{{ period.range }}</div>
            <div class="slots">
              <span v-for="(cell, cellIndex) in period.cells" :key="cell" :class="cellClass(periodIndex, cellIndex)">{{ cell }}</span>
            </div>
            <div v-if="now.period === periodIndex" class="now-line" :style="{ top: now.top }"><span>{{ now.label }}</span></div>
          </div>
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.gate { height: 100dvh; display: grid; grid-template-rows: 48px minmax(0, 1fr); background: var(--paper); }
.gate__top { display: flex; align-items: center; justify-content: space-between; height: 48px; padding: 0 20px 0 24px; background: var(--paper); border-bottom: 1px solid var(--line); }
.brand { display: inline-flex; align-items: center; gap: 10px; color: var(--ink); font-size: 24px; font-weight: 600; line-height: 1; white-space: nowrap; }
.brand__mark { display: block; width: 32px; height: 32px; flex: 0 0 auto; }
.gate__top a { color: var(--muted); font-size: 14px; }
.gate__top a:hover { color: var(--ink); text-decoration: none; }
.gate__body { display: grid; grid-template-columns: 280px minmax(0, 1fr); min-height: 0; }
.rail { display: flex; flex-direction: column; min-height: 0; padding: 24px 20px; overflow: auto; background: var(--paper); border-right: 1px solid var(--line); }
.login-box { display: flex; flex-direction: column; gap: 16px; }
.login-box h1 { margin: 0; font-size: 28px; font-weight: 600; line-height: 1.15; }
.lead { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.6; }
.go { height: 44px; width: 100%; color: #fff; background: var(--ink); border: 0; cursor: pointer; font-size: 15px; font-weight: 500; }
.go:disabled { opacity: .35; cursor: default; }
.login-box .error-banner { margin: 0; }
.hints { display: flex; flex-direction: column; gap: 6px; color: var(--faint); font-size: 13px; }
.hints button { padding: 0; color: var(--muted); background: none; border: 0; cursor: pointer; font-size: 13px; text-align: left; text-decoration: underline; text-underline-offset: 3px; }
.switch { padding: 0; color: var(--muted); background: none; border: 0; cursor: pointer; font-size: 13px; text-align: left; text-decoration: underline; text-underline-offset: 3px; }
.switch:hover { color: var(--ink); }
.ruler-main { display: flex; flex-direction: column; min-width: 0; min-height: 0; background: var(--paper); }
.main-head { display: flex; align-items: center; justify-content: space-between; height: 48px; padding: 0 24px; border-bottom: 1px solid var(--line); color: var(--muted); font-size: 14px; }
.main-head b { color: var(--ink); font-weight: 500; }
.cell { cursor: default; }
@media (max-width: 800px) {
  .gate { height: auto; min-height: 100dvh; }
  .gate__body { grid-template-columns: 1fr; }
  .rail { border-right: 0; border-bottom: 1px solid var(--line); }
  .ruler-main { min-height: 70vh; }
}
</style>
