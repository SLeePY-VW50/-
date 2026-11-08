<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Calendar, DataAnalysis, Cpu, User, OfficeBuilding, Setting, ChatDotSquare, Tickets, Fold, Expand, SwitchButton, Monitor } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { roleLabels } from '../utils'
import type { Role } from '../types'

interface NavItem { path: string; label: string; icon: unknown; roles: Role[]; section: string }
const navItems: NavItem[] = [
  { path: '/dashboard', label: '工作台', icon: Monitor, roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'], section: '工作空间' },
  { path: '/labs', label: '实验室与预约', icon: OfficeBuilding, roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'], section: '工作空间' },
  { path: '/reservations', label: '预约记录', icon: Calendar, roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'], section: '工作空间' },
  { path: '/assistant', label: 'AI预约助手', icon: ChatDotSquare, roles: ['STUDENT', 'TEACHER'], section: '工作空间' },
  { path: '/statistics', label: '使用统计', icon: DataAnalysis, roles: ['TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'], section: '数据与记录' },
  { path: '/schedules', label: '排程与实验', icon: Cpu, roles: ['LAB_ADMIN', 'SYS_ADMIN'], section: '数据与记录' },
  { path: '/credit', label: '信用与申诉', icon: Tickets, roles: ['STUDENT', 'TEACHER'], section: '数据与记录' },
  { path: '/admin/resources', label: '资源管理', icon: Setting, roles: ['SYS_ADMIN'], section: '系统管理' },
  { path: '/admin/users', label: '用户管理', icon: User, roles: ['SYS_ADMIN'], section: '系统管理' },
  { path: '/admin/rules', label: '每周开放规则', icon: Calendar, roles: ['SYS_ADMIN'], section: '系统管理' },
  { path: '/admin/appeals', label: '申诉核定', icon: Tickets, roles: ['SYS_ADMIN'], section: '系统管理' },
]
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const collapsed = ref(false)
const visibleItems = computed(() => navItems.filter((item) => item.roles.some((role) => auth.roles.includes(role))))
const sections = computed(() => [...new Set(visibleItems.value.map((item) => item.section))])
const roleText = computed(() => auth.roles.map((role) => roleLabels[role]).join(' / '))
function itemsFor(section: string) { return visibleItems.value.filter((item) => item.section === section) }
function isActive(path: string) { return route.path === path || (path !== '/dashboard' && route.path.startsWith(`${path}/`)) }
async function logout() { auth.logout(); await router.replace('/login') }
</script>

<template>
  <div class="app-shell" :class="{ 'app-shell--collapsed': collapsed }">
    <a class="skip-link" href="#main-content">跳转到主要内容</a>
    <aside class="sidebar">
      <router-link class="brand" to="/dashboard" aria-label="LabSpace 工作台">
        <span class="brand__mark" aria-hidden="true">L</span>
        <span class="brand__copy"><strong>LabSpace</strong><small>高校实验室预约与排期平台</small></span>
      </router-link>
      <nav class="sidebar__nav" aria-label="主导航">
        <div v-for="section in sections" :key="section" class="nav-section">
          <span class="nav-section__label">{{ section }}</span>
          <router-link v-for="item in itemsFor(section)" :key="item.path" :to="item.path" class="nav-item" :class="{ active: isActive(item.path) }" :title="item.label" @click="collapsed = false">
            <el-icon :size="18"><component :is="item.icon" /></el-icon><span>{{ item.label }}</span><i class="nav-item__active" aria-hidden="true" />
          </router-link>
        </div>
      </nav>
      <div class="sidebar__foot"><span class="service-icon"><i class="status-dot" /></span><span><strong>数据服务</strong><small>实时读取 · 权限隔离</small></span></div>
    </aside>
    <button v-if="collapsed" class="mobile-scrim" aria-label="关闭导航" @click="collapsed = false" />
    <section class="workspace">
      <header class="topbar">
        <div class="topbar__left">
          <button class="icon-button" aria-label="展开或折叠导航" :aria-expanded="collapsed" @click="collapsed = !collapsed"><el-icon :size="19"><Fold v-if="!collapsed" /><Expand v-else /></el-icon></button>
          <div class="topbar__title"><span class="topbar__crumb">LabSpace / 工作空间</span><strong>{{ route.meta.title }}</strong></div>
        </div>
        <div class="topbar__right">
          <router-link class="terminal-link" to="/terminal"><el-icon><Monitor /></el-icon><span>终端签到</span></router-link>
          <span class="topbar__divider" />
          <el-dropdown trigger="click">
            <button class="user-button" aria-label="用户菜单"><span class="avatar">{{ auth.user?.name.slice(0, 1) }}</span><span class="user-button__text"><strong>{{ auth.user?.name }}</strong><small>{{ roleText }}</small></span><el-icon class="user-button__arrow"><ArrowDown /></el-icon></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item disabled>{{ auth.user?.account }}</el-dropdown-item><el-dropdown-item divided @click="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <main id="main-content" class="workspace__main"><router-view /></main>
    </section>
  </div>
</template>

<style scoped>
.app-shell { min-height: 100dvh; display: grid; grid-template-columns: 248px minmax(0, 1fr); transition: grid-template-columns .2s ease; }
.skip-link { position: fixed; top: 10px; left: 12px; z-index: 100; padding: 10px 14px; color: #fff; background: var(--primary); border-radius: 8px; transform: translateY(-150%); }
.skip-link:focus { transform: translateY(0); }
.sidebar { position: sticky; top: 0; z-index: 20; display: flex; flex-direction: column; height: 100dvh; background: #fff; border-right: 1px solid var(--line); }
.brand { display: flex; align-items: center; gap: 11px; height: 72px; padding: 0 20px; color: var(--ink); border-bottom: 1px solid #edf1f6; }
.brand:hover, .nav-item:hover, .terminal-link:hover { text-decoration: none; }
.brand__mark { flex: 0 0 auto; display: grid; place-items: center; width: 36px; height: 36px; color: #fff; background: linear-gradient(135deg, #3f75f2, #6d8df5); border-radius: 11px; box-shadow: 0 6px 14px rgb(56 103 232 / 20%); font-size: 20px; font-weight: 800; }
.brand__copy { min-width: 0; }
.brand strong { display: block; font-size: 17px; font-weight: 700; letter-spacing: .01em; white-space: nowrap; }
.brand small { display: block; margin-top: 3px; color: #77849a; font-size: 8px; font-weight: 500; white-space: nowrap; }
.sidebar__nav { flex: 1; overflow-y: auto; padding: 22px 14px; scrollbar-width: thin; }
.nav-section { margin-bottom: 24px; }
.nav-section__label { display: block; padding: 0 12px 9px; color: #a0aabc; font-size: 10px; font-weight: 600; letter-spacing: .08em; }
.nav-item { position: relative; display: flex; align-items: center; gap: 12px; min-height: 43px; margin: 3px 0; padding: 9px 12px; color: #627089; border-radius: 10px; font-size: 13px; font-weight: 500; transition: color .16s ease, background .16s ease; }
.nav-item .el-icon { flex: 0 0 auto; color: #8996aa; }
.nav-item:hover { color: var(--primary); background: #f4f7fc; }
.nav-item.active { color: var(--primary); background: var(--primary-soft); font-weight: 650; }
.nav-item.active .el-icon { color: var(--primary); }
.nav-item__active { position: absolute; right: 11px; width: 5px; height: 5px; background: transparent; border-radius: 50%; }
.nav-item.active .nav-item__active { background: var(--primary); }
.sidebar__foot { display: flex; align-items: center; gap: 10px; margin: 12px 14px 16px; padding: 13px; background: #f6f8fc; border: 1px solid #edf1f6; border-radius: 12px; }
.service-icon { display: grid; place-items: center; flex: 0 0 auto; width: 29px; height: 29px; background: #eaf8f2; border-radius: 9px; }
.status-dot { width: 7px; height: 7px; background: #20b884; border-radius: 50%; }
.sidebar__foot strong, .sidebar__foot small { display: block; white-space: nowrap; }
.sidebar__foot strong { color: #53627a; font-size: 11px; font-weight: 600; }
.sidebar__foot small { margin-top: 3px; color: #8b98aa; font-size: 9px; }
.workspace { min-width: 0; }
.topbar { position: sticky; top: 0; z-index: 10; display: flex; align-items: center; justify-content: space-between; gap: 20px; height: 72px; padding: 0 32px; background: #fff; border-bottom: 1px solid var(--line); }
.topbar__left, .topbar__right { display: flex; align-items: center; gap: 14px; }
.topbar__title strong { display: block; margin-top: 3px; font-size: 14px; font-weight: 600; }
.topbar__crumb { display: block; color: #96a1b2; font-size: 10px; }
.icon-button, .user-button, .mobile-scrim { border: 0; cursor: pointer; }
.icon-button { display: grid; place-items: center; width: 36px; height: 36px; color: #627089; background: #fff; border: 1px solid var(--line); border-radius: 50%; }
.icon-button:hover { color: var(--primary); background: var(--primary-soft); }
.terminal-link { display: inline-flex; align-items: center; gap: 7px; min-height: 36px; padding: 8px 12px; color: #53627a; background: #fff; border: 1px solid var(--line); border-radius: 9px; font-size: 12px; font-weight: 600; }
.terminal-link:hover { color: var(--primary); background: #f8faff; }
.topbar__divider { width: 1px; height: 24px; background: var(--line); }
.user-button { display: flex; align-items: center; gap: 10px; padding: 2px 0; text-align: left; background: transparent; }
.avatar { display: grid; place-items: center; width: 36px; height: 36px; color: var(--primary); background: var(--primary-soft); border-radius: 50%; font-size: 13px; font-weight: 700; }
.user-button__text strong, .user-button__text small { display: block; }
.user-button__text strong { color: var(--ink); font-size: 12px; font-weight: 600; }
.user-button__text small { max-width: 160px; margin-top: 2px; overflow: hidden; color: #8a96a8; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.user-button__arrow { margin-left: 2px; color: #9aa5b5; font-size: 11px; }
.workspace__main { min-height: calc(100dvh - 72px); }
.mobile-scrim { display: none; }
@media (min-width: 901px) {
  .app-shell--collapsed { grid-template-columns: 80px minmax(0, 1fr); }
  .app-shell--collapsed .brand { justify-content: center; padding: 0; }
  .app-shell--collapsed .brand__copy, .app-shell--collapsed .nav-item > span, .app-shell--collapsed .nav-item__active, .app-shell--collapsed .nav-section__label, .app-shell--collapsed .sidebar__foot > span:last-child { display: none; }
  .app-shell--collapsed .sidebar__nav { padding-inline: 12px; }
  .app-shell--collapsed .nav-section { margin-bottom: 14px; }
  .app-shell--collapsed .nav-item { justify-content: center; padding-inline: 0; }
  .app-shell--collapsed .sidebar__foot { justify-content: center; margin-inline: 12px; padding-inline: 0; }
}
@media (max-width: 900px) {
  .app-shell { display: block; }
  .sidebar { position: fixed; left: 0; width: 248px; transform: translateX(-102%); transition: transform .2s ease; box-shadow: 12px 0 30px rgb(24 36 58 / 12%); }
  .app-shell--collapsed .sidebar { transform: translateX(0); }
  .app-shell--collapsed .mobile-scrim { position: fixed; inset: 0; z-index: 15; display: block; width: 100%; height: 100%; background: rgb(24 36 58 / 38%); }
  .topbar { padding: 0 18px; }
}
@media (max-width: 560px) {
  .topbar { padding: 0 14px; }
  .topbar__left, .topbar__right { gap: 9px; }
  .topbar__crumb, .user-button__text, .topbar__divider, .user-button__arrow { display: none; }
  .topbar__title strong { margin: 0; font-size: 13px; }
  .terminal-link { width: 36px; height: 36px; padding: 0; justify-content: center; }
  .terminal-link span { display: none; }
}
</style>
