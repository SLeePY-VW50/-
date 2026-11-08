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
    <section class="login-context">
      <div class="login-brand"><span class="brand-mark" aria-hidden="true">L</span><span><strong>LabSpace</strong><small>高校实验室预约与排期平台</small></span></div>
      <div class="login-statement"><span class="eyebrow">CAMPUS LAB MANAGEMENT</span><h1>让每一次实验<br>预约都井然有序。</h1><p>统一管理实验室、工位与开放时段。通过清晰的预约与排程，让空间资源发挥更大价值。</p></div>
      <div class="schedule-preview" aria-hidden="true">
        <div class="schedule-preview__head"><span><i class="preview-dot" />本周预约时间板</span><small>30 MIN / SLOT</small></div>
        <div class="schedule-preview__days"><span v-for="day in ['一', '二', '三', '四', '五', '六', '日']" :key="day">周{{ day }}</span></div>
        <div class="schedule-preview__grid"><i v-for="index in 35" :key="index" :class="{ filled: [3, 4, 10, 17, 18, 25, 31].includes(index), soft: [8, 15, 22, 28, 29].includes(index) }" /></div>
        <div class="schedule-preview__axis"><span>08:00</span><span>12:00</span><span>18:00</span></div>
      </div>
      <div class="login-context__foot"><span><i />48 时间片，精确到每 30 分钟</span><span class="mono">FIXED · SCHEDULABLE · CHECK-IN</span></div>
    </section>
    <section class="login-panel">
      <form class="login-form" @submit.prevent="login">
        <div class="login-form__icon"><el-icon><Monitor /></el-icon></div>
        <div class="login-form__heading"><span class="eyebrow">WELCOME BACK</span><h2>欢迎回来</h2><p>登录以继续使用实验室管理平台。</p></div>
        <label class="field"><span>账号</span><input v-model="form.account" autocomplete="username" autofocus placeholder="请输入学号或工号"></label>
        <label class="field"><span>密码</span><input v-model="form.password" type="password" autocomplete="current-password" placeholder="请输入账号密码"></label>
        <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
        <button class="button button--primary login-submit" type="submit" :disabled="loading"><span>{{ loading ? '正在验证…' : '登录工作台' }}</span><el-icon v-if="!loading"><ArrowRight /></el-icon></button>
        <div v-if="isDev" class="demo-accounts"><div class="demo-accounts__title"><span>本地开发快捷体验</span><small>点击自动填充</small></div><div class="demo-accounts__grid"><button v-for="item in accounts" :key="item.account" type="button" @click="fill(item.account)"><strong>{{ item.role }}</strong><code>{{ item.account }}</code></button></div></div>
        <router-link class="terminal-entry" to="/terminal"><span><el-icon><Monitor /></el-icon>前往实验室终端签到</span><el-icon><ArrowRight /></el-icon></router-link>
      </form>
      <p class="login-panel__foot">© 2026 LabSpace · 按角色授权访问</p>
    </section>
  </main>
</template>

<style scoped>
.login-page { min-height: 100dvh; display: grid; grid-template-columns: minmax(480px, 1.08fr) minmax(440px, .92fr); background: #fff; }
.login-context { position: relative; display: flex; flex-direction: column; min-width: 0; padding: 36px clamp(36px, 5vw, 76px) 30px; overflow: hidden; color: #fff; background: #111c30; }
.login-brand { position: relative; z-index: 1; display: flex; align-items: center; gap: 12px; }
.brand-mark { display: grid; place-items: center; width: 36px; height: 36px; color: #fff; background: linear-gradient(135deg, #3f75f2, #6d8df5); border-radius: 11px; box-shadow: 0 6px 14px rgb(56 103 232 / 25%); font-size: 20px; font-weight: 800; }
.login-brand strong, .login-brand small { display: block; }
.login-brand strong { color: #fff; font-size: 17px; letter-spacing: .01em; }
.login-brand small { margin-top: 3px; color: #8793aa; font-size: 9px; }
.login-statement { position: relative; z-index: 1; margin: auto 0 34px; max-width: 570px; padding-top: 52px; }
.login-statement .eyebrow { color: #8ba4d8; }
.login-statement h1 { margin: 17px 0 20px; color: #fff; font-size: clamp(38px, 4vw, 54px); line-height: 1.3; letter-spacing: -.035em; }
.login-statement p { max-width: 440px; margin: 0; color: #a5b1c6; font-size: 14px; line-height: 1.9; }
.schedule-preview { position: relative; z-index: 1; width: min(500px, 100%); margin-bottom: 27px; padding: 21px; background: #1a2942; border: 1px solid #34435b; border-radius: 14px; box-shadow: 0 20px 50px rgb(0 0 0 / 20%); }
.schedule-preview__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 18px; }
.schedule-preview__head > span { display: flex; align-items: center; gap: 8px; color: #d5dbea; font-size: 12px; font-weight: 600; }
.preview-dot { width: 7px; height: 7px; background: #41c994; border-radius: 50%; }
.schedule-preview__head small { color: #7f90ad; font: 9px 'Cascadia Mono', Consolas, monospace; }
.schedule-preview__days, .schedule-preview__grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 7px; }
.schedule-preview__days { margin-bottom: 7px; }
.schedule-preview__days span { color: #7f90ad; text-align: center; font-size: 9px; }
.schedule-preview__grid i { height: 17px; background: #22334e; border: 1px solid #2d3e5a; border-radius: 4px; }
.schedule-preview__grid i.filled { background: #4b7bec; border-color: #4b7bec; }
.schedule-preview__grid i.soft { background: #344d7c; border-color: #344d7c; }
.schedule-preview__axis { display: flex; justify-content: space-between; margin-top: 12px; color: #7f90ad; font: 9px 'Cascadia Mono', Consolas, monospace; }
.login-context__foot { position: relative; z-index: 1; display: flex; align-items: center; justify-content: space-between; gap: 16px; color: #72819b; font-size: 9px; }
.login-context__foot > span:first-child { display: flex; align-items: center; gap: 7px; }
.login-context__foot i { width: 6px; height: 6px; background: #41c994; border-radius: 50%; }
.login-context__foot .mono { font-size: 8px; }
.login-panel { display: flex; align-items: center; justify-content: center; flex-direction: column; padding: 44px 40px 25px; background: #fff; }
.login-form { width: min(410px, 100%); display: grid; gap: 19px; margin: auto 0; padding: 31px; background: #fff; border: 1px solid var(--line); border-radius: 14px; box-shadow: var(--shadow); }
.login-form__icon { display: grid; place-items: center; width: 42px; height: 42px; margin-bottom: -3px; color: var(--primary); background: var(--primary-soft); border-radius: 10px; font-size: 20px; }
.login-form__heading { margin-bottom: 5px; }
.login-form__heading h2 { margin: 7px 0 7px; font-size: 28px; }
.login-form__heading p { margin: 0; color: var(--muted); font-size: 12px; }
.login-form .field { gap: 8px; }
.login-form .field input { min-height: 43px; }
.login-form .error-banner { margin: -3px 0; }
.login-submit { justify-content: space-between; min-height: 44px; padding-inline: 18px; }
.demo-accounts { padding-top: 18px; border-top: 1px solid var(--line); }
.demo-accounts__title { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.demo-accounts__title span { color: #53627a; font-size: 12px; font-weight: 600; }
.demo-accounts__title small { color: #96a1b2; font-size: 10px; }
.demo-accounts__grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 12px; }
.demo-accounts__grid button { display: grid; gap: 5px; padding: 10px 11px; color: #4b5b76; background: #f8faff; border: 1px solid #e8edf6; border-radius: 8px; cursor: pointer; text-align: left; }
.demo-accounts__grid button:hover { background: #f1f5ff; border-color: #91aaf0; }
.demo-accounts__grid strong { font-size: 12px; font-weight: 600; }
.demo-accounts__grid code { color: #96a1b2; font: 10px 'Cascadia Mono', Consolas, monospace; }
.terminal-entry { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding-top: 17px; color: #53627a; border-top: 1px solid var(--line); font-size: 13px; font-weight: 600; }
.terminal-entry > span { display: flex; align-items: center; gap: 7px; }
.login-panel__foot { margin: 28px 0 0; color: #a0aaba; font-size: 10px; }
@media (max-width: 1050px) { .login-page { grid-template-columns: minmax(390px, .9fr) minmax(420px, 1.1fr); } .login-context { padding-inline: 36px; } .login-context__foot .mono { display: none; } .login-form { padding: 26px; } }
@media (max-width: 820px) { .login-page { grid-template-columns: 1fr; } .login-context { display: none; } .login-panel { min-height: 100dvh; padding: 34px 22px 22px; background: #f4f7fb; } .login-form { margin: auto 0; } .login-panel__foot { margin-top: 25px; } }
@media (max-width: 440px) { .login-form { padding: 23px 20px; } .login-panel { padding-inline: 14px; } }
</style>
