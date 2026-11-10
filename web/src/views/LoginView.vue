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
const form = reactive({ account: '', password: '' })
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

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
  <main class="login-page">
    <div class="login-sheet">
      <header class="login-brand">
        <span class="brand-mark" aria-hidden="true">L</span>
        <span class="login-brand__copy"><strong>LabSpace</strong><small>高校实验室智能预约与排期</small></span>
      </header>
      <p class="product-line">看清时段、容量和审批，再提交预约或完成签到。</p>
      <div class="motif" aria-hidden="true">
        <i v-for="n in 48" :key="n" :class="{ pair: n % 8 === 2 || n % 8 === 3 }" />
      </div>
      <form class="login-card" @submit.prevent="login">
        <div class="login-card__heading">
          <span class="eyebrow">账号登录</span>
          <h1>登录工作台</h1>
        </div>
        <label class="field"><span>账号</span><input v-model="form.account" autocomplete="username" autofocus placeholder="学号或工号"></label>
        <label class="field"><span>密码</span><input v-model="form.password" type="password" autocomplete="current-password" placeholder="请输入密码"></label>
        <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
        <button class="button button--primary login-submit" type="submit" :disabled="loading"><span>{{ loading ? '正在验证…' : '进入工作台' }}</span><el-icon v-if="!loading"><ArrowRight /></el-icon></button>
        <div v-if="isDev" class="demo-accounts">
          <div class="demo-accounts__title"><span>本地开发账号</span><small>只填充表单</small></div>
          <div class="demo-accounts__grid">
            <button v-for="item in accounts" :key="item.account" type="button" @click="fill(item.account)"><strong>{{ item.role }}</strong><code>{{ item.account }}</code></button>
          </div>
        </div>
        <router-link class="terminal-entry" to="/terminal"><span><el-icon><Monitor /></el-icon>实验室终端签到</span><el-icon><ArrowRight /></el-icon></router-link>
      </form>
      <p class="login-foot">终端签到单独验证，不会建立管理会话。</p>
    </div>
  </main>
</template>

<style scoped>
.login-page { min-height: 100dvh; display: grid; place-items: center; padding: 40px 20px; background: var(--canvas); }
.login-sheet { width: min(440px, 100%); }
.login-brand { display: flex; align-items: center; gap: 12px; }
.brand-mark { display: grid; place-items: center; width: 32px; height: 32px; color: var(--paper); background: var(--primary); border-radius: 6px; font-size: 16px; font-weight: 700; }
.login-brand__copy strong, .login-brand__copy small { display: block; }
.login-brand__copy strong { color: var(--ink); font-size: 18px; letter-spacing: -.02em; }
.login-brand__copy small { margin-top: 2px; color: var(--muted); font-size: 12px; }
.product-line { margin: 22px 0 16px; color: var(--body); font-size: 15px; line-height: 1.6; }
.motif { display: grid; grid-template-columns: repeat(16, minmax(0, 1fr)); gap: 4px; margin-bottom: 22px; }
.motif i { height: 10px; background: transparent; border: 1px solid var(--line); border-radius: 2px; }
.motif i.pair { background: var(--primary-soft); border-color: #c9e3d8; }
.login-card { display: grid; gap: 16px; padding: 24px; background: var(--paper); border: 1px solid var(--line); border-radius: 8px; }
.login-card__heading h1 { margin: 8px 0 0; font-size: 30px; }
.login-card .error-banner { margin: 0; }
.login-submit { justify-content: space-between; }
.demo-accounts { padding-top: 16px; border-top: 1px solid var(--line); }
.demo-accounts__title { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.demo-accounts__title span { color: var(--body); font-size: 13px; font-weight: 600; }
.demo-accounts__title small { color: var(--muted); font-size: 12px; }
.demo-accounts__grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 10px; }
.demo-accounts__grid button { display: grid; gap: 4px; min-height: 40px; padding: 8px 10px; color: var(--body); background: #f7f4ee; border: 1px solid var(--line); border-radius: 6px; cursor: pointer; text-align: left; }
.demo-accounts__grid button:hover { color: var(--primary); border-color: #c9e3d8; background: var(--primary-soft); }
.demo-accounts__grid button:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
.demo-accounts__grid strong { font-size: 13px; font-weight: 600; }
.demo-accounts__grid code { color: var(--muted); font: 12px var(--mono); }
.terminal-entry { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 40px; padding-top: 14px; color: var(--body); border-top: 1px solid var(--line); font-size: 14px; font-weight: 600; }
.terminal-entry:hover { color: var(--primary); text-decoration: none; }
.terminal-entry > span { display: flex; align-items: center; gap: 8px; }
.login-foot { margin: 16px 0 0; color: var(--muted); font-size: 12px; }
@media (max-width: 420px) {
  .login-page { padding: 24px 14px; }
  .login-card { padding: 18px; }
  .demo-accounts__grid { grid-template-columns: 1fr; }
}
</style>
