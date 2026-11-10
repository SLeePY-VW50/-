<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Monitor } from '@element-plus/icons-vue'
import http, { errorMessage } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { User } from '../types'

const isDev = import.meta.env.DEV
const accounts = [
  { account: '20240001', role: '学生' },
  { account: 'T1001', role: '教师' },
  { account: 'L1001', role: '实验室管理员' },
  { account: 'A1001', role: '系统管理员' },
]
const bands = [
  { name: '凌晨', range: '00–06' },
  { name: '上午', range: '06–12' },
  { name: '下午', range: '12–18' },
  { name: '晚间', range: '18–24' },
]
const form = reactive({ account: '', password: '' })
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

function cellOn(band: number, cell: number) {
  const index = band * 12 + cell
  return index % 9 === 2 || index % 9 === 3
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
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//') ? route.query.redirect : '/dashboard'
    await router.replace(redirect)
  } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false }
}
</script>

<template>
  <main class="gate">
    <header class="gate__top">
      <span class="brand"><i aria-hidden="true">L</i><strong>LabSpace</strong></span>
      <router-link to="/terminal"><el-icon><Monitor /></el-icon>终端签到</router-link>
    </header>
    <div class="gate__center">
      <div class="gate__cluster">
        <section class="gate__stage">
          <div class="gate__copy">
            <p class="eyebrow">高校实验室</p>
            <h1>一天<br>四十八格。</h1>
            <p>先看清占用、容量和审批，再决定提交预约还是去签到。</p>
          </div>
          <div class="gate__board" aria-hidden="true">
            <div v-for="(band, bandIndex) in bands" :key="band.name" class="gate__band">
              <span><strong>{{ band.name }}</strong><small>{{ band.range }}</small></span>
              <i v-for="cell in 12" :key="cell" :class="{ on: cellOn(bandIndex, cell) }" />
            </div>
          </div>
        </section>
        <form class="gate__dock" @submit.prevent="login">
          <div class="gate__fields">
            <label class="field"><span>账号</span><input v-model="form.account" autocomplete="username" autofocus placeholder="学号或工号"></label>
            <label class="field"><span>密码</span><input v-model="form.password" type="password" autocomplete="current-password" placeholder="密码"></label>
            <button class="button button--primary" type="submit" :disabled="loading">{{ loading ? '正在验证…' : '进入' }}<el-icon v-if="!loading"><ArrowRight /></el-icon></button>
          </div>
          <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
          <div v-if="isDev" class="gate__dev">
            <span>本地账号</span>
            <button v-for="item in accounts" :key="item.account" type="button" @click="fill(item.account)">{{ item.role }} {{ item.account }}</button>
          </div>
        </form>
      </div>
    </div>
  </main>
</template>

<style scoped>
.gate { min-height: 100dvh; display: flex; flex-direction: column; background: var(--canvas); }
.gate__top { display: flex; align-items: center; justify-content: space-between; height: 56px; padding: 0 28px; border-bottom: 1px solid var(--line); }
.brand { display: flex; align-items: center; gap: 8px; }
.brand i { display: grid; place-items: center; width: 26px; height: 26px; color: var(--paper); background: var(--primary); border-radius: 6px; font-style: normal; font-weight: 700; }
.brand strong { font-size: 15px; letter-spacing: -.02em; }
.gate__top a { display: inline-flex; align-items: center; gap: 6px; min-height: 40px; color: var(--body); font-size: 13px; font-weight: 600; }
.gate__top a:hover { color: var(--primary); text-decoration: none; }
.gate__center { flex: 1; display: flex; flex-direction: column; min-height: 0; overflow: auto; padding: 28px 36px 36px; }
.gate__cluster { width: min(1120px, 100%); margin-block: auto; display: grid; gap: 28px; }
.gate__stage { display: grid; grid-template-columns: minmax(240px, 340px) minmax(0, 1fr); gap: 36px; align-items: center; }
.gate__copy h1 { margin: 12px 0 14px; font-size: clamp(52px, 6vw, 80px); line-height: .92; letter-spacing: -.05em; }
.gate__copy p:last-child { max-width: 28ch; margin: 0; color: var(--body); font-size: 15px; line-height: 1.6; }
.gate__board { display: grid; gap: 10px; align-content: center; }
.gate__band { display: grid; grid-template-columns: 52px repeat(12, minmax(0, 1fr)); gap: 6px; align-items: center; }
.gate__band span strong, .gate__band span small { display: block; }
.gate__band span strong { font-size: 13px; }
.gate__band span small { color: var(--muted); font: 11px var(--mono); }
.gate__band i { height: 42px; border: 1px solid var(--line); border-radius: 4px; background: transparent; }
.gate__band i.on { background: var(--primary-soft); border-color: #c9e3d8; }
.gate__dock { display: grid; gap: 10px; padding-top: 18px; border-top: 1px solid var(--line); }
.gate__fields { display: grid; grid-template-columns: minmax(160px, 220px) minmax(160px, 220px) max-content; gap: 12px; align-items: end; justify-content: start; }
.gate__dock .error-banner { margin: 0; }
.gate__dev { display: flex; flex-wrap: wrap; gap: 8px 14px; align-items: center; color: var(--muted); font-size: 12px; }
.gate__dev button { padding: 0; color: var(--body); background: none; border: 0; cursor: pointer; font-size: 12px; text-decoration: underline; }
.gate__dev button:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
@media (max-width: 900px) {
  .gate__center { padding: 24px 16px 28px; }
  .gate__stage { grid-template-columns: 1fr; gap: 22px; }
  .gate__copy h1 { font-size: 48px; }
  .gate__band i { height: 22px; }
  .gate__fields { grid-template-columns: 1fr; }
}
</style>
