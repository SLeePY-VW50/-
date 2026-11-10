<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
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
const desktopCollapsed = ref(false)
const mobileOpen = ref(false)
const isMobile = ref(false)
const visibleItems = computed(() => navItems.filter((item) => item.roles.some((role) => auth.roles.includes(role))))
const sections = computed(() => [...new Set(visibleItems.value.map((item) => item.section))])
const roleText = computed(() => auth.roles.map((role) => roleLabels[role]).join(' / '))
const navExpanded = computed(() => (isMobile.value ? mobileOpen.value : !desktopCollapsed.value))
const navToggleLabel = computed(() => {
  if (isMobile.value) return mobileOpen.value ? '关闭导航' : '打开导航'
  return desktopCollapsed.value ? '展开侧栏' : '折叠侧栏'
})
function itemsFor(section: string) { return visibleItems.value.filter((item) => item.section === section) }
function isActive(path: string) { return route.path === path || (path !== '/dashboard' && route.path.startsWith(`${path}/`)) }
function syncViewport() {
  isMobile.value = window.matchMedia('(max-width: 900px)').matches
  if (!isMobile.value) mobileOpen.value = false
}
function toggleNav() {
  if (isMobile.value) mobileOpen.value = !mobileOpen.value
  else desktopCollapsed.value = !desktopCollapsed.value
}
function closeMobileNav() { mobileOpen.value = false }
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && mobileOpen.value) closeMobileNav()
}
async function logout() { auth.logout(); await router.replace('/login') }
onMounted(() => {
  syncViewport()
  window.addEventListener('resize', syncViewport)
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', syncViewport)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="app-shell" :class="{ 'app-shell--rail': desktopCollapsed && !isMobile, 'app-shell--drawer-open': mobileOpen && isMobile }">
    <a class="skip-link" href="#main-content">跳转到主要内容</a>
    <aside class="sidebar" id="app-sidebar" :aria-hidden="isMobile && !mobileOpen ? true : undefined">
      <router-link class="brand" to="/dashboard" aria-label="LabSpace 工作台" @click="closeMobileNav">
        <span class="brand__mark" aria-hidden="true">L</span>
        <span class="brand__copy"><strong>LabSpace</strong><small>高校实验室预约与排期平台</small></span>
      </router-link>
      <nav class="sidebar__nav" aria-label="主导航">
        <div v-for="section in sections" :key="section" class="nav-section">
          <span class="nav-section__label">{{ section }}</span>
          <router-link v-for="item in itemsFor(section)" :key="item.path" :to="item.path" class="nav-item" :class="{ active: isActive(item.path) }" :title="item.label" @click="closeMobileNav">
            <el-icon :size="18"><component :is="item.icon" /></el-icon><span>{{ item.label }}</span><i class="nav-item__active" aria-hidden="true" />
          </router-link>
        </div>
      </nav>
      <div class="sidebar__foot"><span class="bench-mark" aria-hidden="true"><i /><i /><i /><i /></span><span><strong>30 分钟一格</strong><small>一天 48 个时段</small></span></div>
    </aside>
    <button v-if="mobileOpen && isMobile" class="mobile-scrim" aria-label="关闭导航" @click="closeMobileNav" />
    <section class="workspace">
      <header class="topbar">
        <div class="topbar__left">
          <button class="icon-button" type="button" :aria-label="navToggleLabel" :aria-expanded="navExpanded" aria-controls="app-sidebar" @click="toggleNav">
            <el-icon :size="19"><Fold v-if="navExpanded" /><Expand v-else /></el-icon>
          </button>
          <div class="topbar__title"><span class="topbar__crumb">LabSpace</span><strong>{{ route.meta.title }}</strong></div>
        </div>
        <div class="topbar__right">
          <router-link class="terminal-link" to="/terminal"><el-icon><Monitor /></el-icon><span>终端签到</span></router-link>
          <span class="topbar__divider" aria-hidden="true" />
          <el-dropdown trigger="click">
            <button class="user-button" type="button" aria-label="用户菜单"><span class="avatar">{{ auth.user?.name.slice(0, 1) }}</span><span class="user-button__text"><strong>{{ auth.user?.name }}</strong><small>{{ roleText }}</small></span><el-icon class="user-button__arrow"><ArrowDown /></el-icon></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item disabled>{{ auth.user?.account }}</el-dropdown-item><el-dropdown-item divided @click="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <main id="main-content" class="workspace__main" tabindex="-1"><router-view /></main>
    </section>
  </div>
</template>

<style scoped>
.app-shell { min-height: 100dvh; display: grid; grid-template-columns: 216px minmax(0, 1fr); background: var(--canvas); }
.skip-link { position: fixed; top: 10px; left: 12px; z-index: 100; padding: 10px 14px; color: var(--paper); background: var(--primary); border-radius: 6px; transform: translateY(-150%); }
.skip-link:focus { transform: translateY(0); }
.sidebar { position: sticky; top: 0; z-index: 20; display: flex; flex-direction: column; height: 100dvh; color: var(--ink); background: var(--paper); border-right: 1px solid var(--line); }
.brand { display: flex; align-items: center; gap: 10px; height: 56px; padding: 0 16px; color: var(--ink); border-bottom: 1px solid var(--line); }
.brand:hover, .nav-item:hover, .terminal-link:hover { text-decoration: none; }
.brand__mark { flex: 0 0 auto; display: grid; place-items: center; width: 28px; height: 28px; color: var(--paper); background: var(--primary); border-radius: 6px; font-size: 15px; font-weight: 700; }
.brand__copy { min-width: 0; }
.brand strong { display: block; color: var(--ink); font-size: 15px; font-weight: 700; letter-spacing: -.02em; white-space: nowrap; }
.brand small { display: block; margin-top: 1px; overflow: hidden; color: var(--muted); font-size: 11px; font-weight: 500; line-height: 1.3; text-overflow: ellipsis; white-space: nowrap; }
.sidebar__nav { flex: 1; overflow-y: auto; padding: 14px 10px; scrollbar-width: thin; }
.nav-section { margin-bottom: 16px; }
.nav-section__label { display: block; padding: 0 10px 6px; color: var(--muted); font-size: 11px; font-weight: 600; }
.nav-item { position: relative; display: flex; align-items: center; gap: 10px; min-height: 40px; margin: 1px 0; padding: 8px 10px 8px 14px; color: var(--body); border-radius: 6px; font-size: 13px; font-weight: 500; }
.nav-item .el-icon { flex: 0 0 auto; color: var(--muted); }
.nav-item:hover { color: var(--primary); background: #f7f4ee; }
.nav-item.active { color: var(--primary); background: var(--primary-soft); font-weight: 600; }
.nav-item.active::before { position: absolute; top: 8px; bottom: 8px; left: 0; width: 3px; background: var(--primary); border-radius: 2px; content: ''; }
.nav-item.active .el-icon { color: var(--primary); }
.nav-item:focus-visible { outline: 2px solid var(--ring); outline-offset: 2px; }
.nav-item__active { display: none; }
.sidebar__foot { display: flex; align-items: center; gap: 10px; margin: 0 12px 12px; padding: 12px 2px 2px; border-top: 1px solid var(--line); }
.bench-mark { display: grid; grid-template-columns: repeat(4, 4px); gap: 2px; }
.bench-mark i { height: 12px; background: var(--line); border-radius: 1px; }
.bench-mark i:nth-child(2), .bench-mark i:nth-child(3) { background: var(--primary); }
.sidebar__foot strong, .sidebar__foot small { display: block; white-space: nowrap; }
.sidebar__foot strong { color: var(--ink); font-size: 12px; font-weight: 600; }
.sidebar__foot small { margin-top: 2px; color: var(--muted); font-size: 11px; }
.workspace { min-width: 0; background: var(--canvas); }
.topbar { position: sticky; top: 0; z-index: 10; display: flex; align-items: center; justify-content: space-between; gap: 16px; height: 56px; padding: 0 24px; background: var(--paper); border-bottom: 1px solid var(--line); }
.topbar__left, .topbar__right { display: flex; align-items: center; gap: 12px; }
.topbar__title strong { display: block; color: var(--ink); font-size: 14px; font-weight: 600; }
.topbar__crumb { display: block; margin-bottom: 1px; color: var(--muted); font-size: 11px; }
.icon-button, .user-button, .mobile-scrim { border: 0; cursor: pointer; }
.icon-button { display: grid; place-items: center; width: 40px; height: 40px; color: var(--body); background: var(--paper); border: 1px solid var(--line); border-radius: 6px; }
.icon-button:hover { color: var(--primary); background: var(--primary-soft); }
.terminal-link { display: inline-flex; align-items: center; gap: 6px; min-height: 40px; padding: 8px 12px; color: var(--body); background: var(--paper); border: 1px solid var(--line); border-radius: 6px; font-size: 13px; font-weight: 600; }
.terminal-link:hover { color: var(--primary); background: #f7f4ee; }
.topbar__divider { width: 1px; height: 20px; background: var(--line); }
.user-button { display: flex; align-items: center; gap: 8px; min-height: 40px; padding: 2px; text-align: left; background: transparent; border-radius: 6px; }
.avatar { display: grid; place-items: center; width: 32px; height: 32px; color: var(--primary); background: var(--primary-soft); border-radius: 6px; font-size: 13px; font-weight: 700; }
.user-button__text strong, .user-button__text small { display: block; }
.user-button__text strong { color: var(--ink); font-size: 13px; font-weight: 600; }
.user-button__text small { max-width: 148px; overflow: hidden; color: var(--muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.user-button__arrow { color: var(--muted); font-size: 12px; }
.workspace__main { min-height: calc(100dvh - 56px); outline: none; }
.mobile-scrim { display: none; }
@media (min-width: 901px) {
  .app-shell--rail { grid-template-columns: 72px minmax(0, 1fr); }
  .app-shell--rail .brand { justify-content: center; padding: 0; }
  .app-shell--rail .brand__copy, .app-shell--rail .nav-item > span, .app-shell--rail .nav-section__label, .app-shell--rail .sidebar__foot > span:last-child { display: none; }
  .app-shell--rail .sidebar__nav { padding-inline: 8px; }
  .app-shell--rail .nav-item { justify-content: center; padding-inline: 0; }
  .app-shell--rail .sidebar__foot { justify-content: center; }
}
@media (max-width: 900px) {
  .app-shell { display: block; }
  .sidebar { position: fixed; left: 0; width: 216px; border-right: 1px solid var(--line); transform: translateX(-105%); transition: transform .16s ease; }
  .app-shell--drawer-open .sidebar { transform: translateX(0); }
  .app-shell--drawer-open .mobile-scrim { position: fixed; inset: 0; z-index: 15; display: block; width: 100%; height: 100%; background: rgb(26 29 27 / 36%); }
  .topbar { padding: 0 14px; }
}
@media (max-width: 560px) {
  .topbar__left, .topbar__right { gap: 8px; }
  .topbar__crumb, .user-button__text, .topbar__divider, .user-button__arrow { display: none; }
  .terminal-link { width: 40px; justify-content: center; padding: 0; }
  .terminal-link span { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  .sidebar { transition: none; }
}
</style>
