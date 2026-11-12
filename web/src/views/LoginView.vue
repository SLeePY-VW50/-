<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
  { name: '凌晨', range: '00:00–06:00' },
  { name: '上午', range: '06:00–12:00' },
  { name: '下午', range: '12:00–18:00' },
  { name: '晚间', range: '18:00–24:00' },
]
const form = reactive({ account: '', password: '' })
const loading = ref(false)
const error = ref('')
const now = ref({ period: -1, top: '0%', label: '' })
let clock: ReturnType<typeof setInterval> | undefined
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const todayLabel = computed(() => {
  const value = new Date()
  return `${value.getFullYear()} 年 ${value.getMonth() + 1} 月 ${value.getDate()} 日`
})

function timeLabel(index: number) {
  return `${String(Math.floor(index / 2)).padStart(2, '0')}:${index % 2 ? '30' : '00'}`
}
function cellClass(index: number) {
  const hour = Math.floor(index / 2)
  if (hour < 8 || hour >= 22) return 'cell cell--closed'
  if (index === 28 || index === 29) return 'cell cell--occupied'
  if (index >= 32 && index <= 34) return 'cell cell--selected'
  return 'cell'
}
function placeNow() {
  const current = new Date()
  const mins = current.getHours() * 60 + current.getMinutes()
  const period = Math.min(3, Math.floor(mins / 360))
  now.value = {
    period,
    top: `calc(48px + (100% - 48px) * ${(mins % 360) / 360})`,
    label: `${String(current.getHours()).padStart(2, '0')}:${String(current.getMinutes()).padStart(2, '0')}`,
  }
}
function fill(account: string) { form.account = account; form.password = 'Lab@2026'; error.value = '' }
async function login() {
  if (!form.account.trim() || !form.password) { error.value = '请输入账号和密码'; return }
  loading.value = true
  error.value = ''
  try {
    const result = await http.post<{ token: string; user: User }>('/api/auth/login', { account: form.account.trim(), password: form.password })
    auth.setAuth(result.token, result.user)
    form.password = ''
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//') ? route.query.redirect : '/labs'
    await router.replace(redirect)
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
onMounted(() => { placeNow(); clock = setInterval(placeNow, 30_000) })
onBeforeUnmount(() => { if (clock) clearInterval(clock) })
</script>

<template>
  <main class="gate">
    <header class="gate__top">
      <span class="brand">LabSpace</span>
      <router-link to="/terminal">终端签到</router-link>
    </header>
    <div class="gate__body">
      <aside class="rail">
        <form class="login-box" @submit.prevent="login">
          <h1>一天四十八格。</h1>
          <p class="lead">先看清占用、容量和审批，再决定预约或签到。</p>
          <label class="field"><span>账号</span><input v-model="form.account" autocomplete="username" autofocus placeholder="学号或工号"></label>
          <label class="field"><span>密码</span><input v-model="form.password" type="password" autocomplete="current-password" placeholder="密码"></label>
          <button class="go" type="submit" :disabled="loading">{{ loading ? '正在验证…' : '进入' }}</button>
          <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
          <div v-if="isDev" class="hints">
            本地账号
            <button v-for="item in accounts" :key="item.account" type="button" @click="fill(item.account)">{{ item.role }} {{ item.account }}</button>
          </div>
        </form>
      </aside>
      <section class="ruler-main" :aria-label="`时间尺示意，${today()}`">
        <div class="main-head"><b>{{ todayLabel }}</b><span>每格 30 分钟</span></div>
        <div class="periods" aria-hidden="true">
          <div v-for="(period, periodIndex) in periods" :key="period.name" class="period">
            <div class="period-h"><b>{{ period.name }}</b>{{ period.range }}</div>
            <div class="slots">
              <span v-for="cell in 12" :key="cell" :class="cellClass(periodIndex * 12 + cell - 1)">{{ cellClass(periodIndex * 12 + cell - 1).includes('closed') ? '' : timeLabel(periodIndex * 12 + cell - 1) }}</span>
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
.brand { font-size: 16px; font-weight: 600; }
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
