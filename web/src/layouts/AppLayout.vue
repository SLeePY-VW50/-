<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Close, Menu } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { roleLabels } from '../utils'
import type { Role } from '../types'

interface NavItem { path: string; label: string; roles: Role[] }
const navItems: NavItem[] = [
  { path: '/dashboard', label: '工作台', roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/labs', label: '时间尺', roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/reservations', label: '预约', roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/assistant', label: '助手', roles: ['STUDENT', 'TEACHER'] },
  { path: '/statistics', label: '统计', roles: ['TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/schedules', label: '排程', roles: ['LAB_ADMIN', 'SYS_ADMIN'] },
  { path: '/credit', label: '信用', roles: ['STUDENT', 'TEACHER'] },
  { path: '/admin/resources', label: '资源', roles: ['SYS_ADMIN'] },
  { path: '/admin/users', label: '用户', roles: ['SYS_ADMIN'] },
  { path: '/admin/rules', label: '规则', roles: ['SYS_ADMIN'] },
  { path: '/admin/appeals', label: '申诉', roles: ['SYS_ADMIN'] },
]
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const isNarrow = ref(false)
const visibleItems = computed(() => navItems.filter((item) => item.roles.some((role) => auth.roles.includes(role))))
const roleText = computed(() => auth.roles.map((role) => roleLabels[role]).join(' / '))
function isActive(path: string) { return route.path === path || (path !== '/dashboard' && route.path.startsWith(`${path}/`)) }
function syncViewport() {
  isNarrow.value = window.matchMedia('(max-width: 980px)').matches
  if (!isNarrow.value) menuOpen.value = false
}
function toggleMenu() { menuOpen.value = !menuOpen.value }
function closeMenu() { menuOpen.value = false }
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && menuOpen.value) closeMenu()
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
  <div class="mast-shell" :class="{ 'mast-shell--menu': menuOpen && isNarrow }">
    <a class="skip-link" href="#main-content">跳转到主要内容</a>
    <header class="mast">
      <router-link class="brand" to="/labs" aria-label="LabSpace 时间尺" @click="closeMenu">LabSpace</router-link>
      <nav v-show="!isNarrow" class="mast__nav" aria-label="主导航">
        <router-link v-for="item in visibleItems" :key="item.path" :to="item.path" class="mast__link" :class="{ active: isActive(item.path) }">{{ item.label }}</router-link>
      </nav>
      <div class="mast__tools">
        <button v-if="isNarrow" class="menu-button" type="button" :aria-label="menuOpen ? '关闭导航' : '打开导航'" :aria-expanded="menuOpen" aria-controls="mast-menu" @click="toggleMenu">
          <el-icon :size="18"><Close v-if="menuOpen" /><Menu v-else /></el-icon>
        </button>
        <router-link class="terminal-link" to="/terminal" @click="closeMenu">终端</router-link>
        <span class="who" :title="`${auth.user?.account || ''} ${roleText}`">{{ auth.user?.name }}</span>
        <button class="logout" type="button" @click="logout">退出</button>
      </div>
    </header>
    <nav v-if="isNarrow && menuOpen" id="mast-menu" class="mast-menu" aria-label="主导航">
      <router-link v-for="item in visibleItems" :key="item.path" :to="item.path" class="mast-menu__link" :class="{ active: isActive(item.path) }" @click="closeMenu">{{ item.label }}</router-link>
    </nav>
    <button v-if="isNarrow && menuOpen" class="mast-scrim" aria-label="关闭导航" @click="closeMenu" />
    <main id="main-content" class="mast-main" tabindex="-1"><router-view /></main>
  </div>
</template>

<style scoped>
.mast-shell { min-height: 100dvh; background: var(--canvas); }
.skip-link { position: fixed; top: 8px; left: 12px; z-index: 100; padding: 8px 12px; color: #fff; background: var(--ink); transform: translateY(-150%); }
.skip-link:focus { transform: translateY(0); }
.mast { position: sticky; top: 0; z-index: 30; display: flex; align-items: center; gap: 20px; height: 48px; padding: 0 20px 0 24px; background: var(--paper); border-bottom: 1px solid var(--line); }
.brand { flex: 0 0 auto; color: var(--ink); font-size: 16px; font-weight: 600; }
.brand:hover, .mast__link:hover, .terminal-link:hover, .mast-menu__link:hover, .logout:hover { color: var(--ink); text-decoration: none; }
.mast__nav { display: flex; align-items: center; gap: 20px; min-width: 0; overflow-x: auto; }
.mast__link { display: inline-flex; align-items: center; height: 48px; color: var(--muted); font-size: 14px; font-weight: 400; white-space: nowrap; }
.mast__link.active { color: var(--ink); font-weight: 500; }
.mast__link:focus-visible, .mast-menu__link:focus-visible { outline: 2px solid var(--ink); outline-offset: -2px; }
.mast__tools { display: flex; align-items: center; gap: 16px; margin-left: auto; color: var(--muted); font-size: 14px; }
.menu-button, .logout { border: 0; cursor: pointer; background: transparent; color: var(--muted); font-size: 14px; }
.menu-button { display: grid; place-items: center; width: 36px; height: 36px; color: var(--ink); }
.who { color: var(--ink); white-space: nowrap; }
.mast-menu { position: sticky; top: 48px; z-index: 25; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); background: var(--paper); border-bottom: 1px solid var(--line); }
.mast-menu__link { display: flex; align-items: center; min-height: 44px; padding: 0 20px; color: var(--muted); border-top: 1px solid var(--line); font-size: 14px; }
.mast-menu__link.active { color: var(--ink); font-weight: 500; }
.mast-scrim { position: fixed; inset: 48px 0 0; z-index: 20; border: 0; background: rgb(23 23 23 / 28%); cursor: pointer; }
.mast-main { min-height: calc(100dvh - 48px); outline: none; background: var(--canvas); }
@media (max-width: 720px) {
  .mast { padding-inline: 12px; gap: 12px; }
  .who { display: none; }
}
</style>
