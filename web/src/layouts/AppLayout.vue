<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Close, Menu, Monitor, SwitchButton } from '@element-plus/icons-vue'
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
      <router-link class="brand" to="/dashboard" aria-label="LabSpace 工作台" @click="closeMenu">
        <span class="brand__mark" aria-hidden="true">L</span>
        <strong>LabSpace</strong>
      </router-link>
      <nav v-show="!isNarrow" class="mast__nav" aria-label="主导航">
        <router-link v-for="item in visibleItems" :key="item.path" :to="item.path" class="mast__link" :class="{ active: isActive(item.path) }">{{ item.label }}</router-link>
      </nav>
      <div class="mast__tools">
        <button v-if="isNarrow" class="menu-button" type="button" :aria-label="menuOpen ? '关闭导航' : '打开导航'" :aria-expanded="menuOpen" aria-controls="mast-menu" @click="toggleMenu">
          <el-icon :size="18"><Close v-if="menuOpen" /><Menu v-else /></el-icon>
        </button>
        <router-link class="terminal-link" to="/terminal" @click="closeMenu"><el-icon><Monitor /></el-icon><span>终端</span></router-link>
        <el-dropdown trigger="click">
          <button class="user-button" type="button" aria-label="用户菜单">
            <span class="avatar">{{ auth.user?.name.slice(0, 1) }}</span>
            <span class="user-button__text"><strong>{{ auth.user?.name }}</strong><small>{{ roleText }}</small></span>
            <el-icon><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ auth.user?.account }}</el-dropdown-item>
              <el-dropdown-item divided @click="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
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
.skip-link { position: fixed; top: 10px; left: 12px; z-index: 100; padding: 10px 14px; color: var(--paper); background: var(--primary); border-radius: 6px; transform: translateY(-150%); }
.skip-link:focus { transform: translateY(0); }
.mast { position: sticky; top: 0; z-index: 30; display: flex; align-items: stretch; gap: 8px; min-height: 56px; padding: 0 20px; background: var(--paper); border-bottom: 1px solid var(--line); }
.brand { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; color: var(--ink); }
.brand:hover, .mast__link:hover, .terminal-link:hover, .mast-menu__link:hover { text-decoration: none; }
.brand__mark { display: grid; place-items: center; width: 26px; height: 26px; color: var(--paper); background: var(--primary); border-radius: 6px; font-size: 14px; font-weight: 700; }
.brand strong { font-size: 15px; letter-spacing: -.02em; }
.mast__nav { display: flex; align-items: stretch; gap: 2px; min-width: 0; margin-left: 12px; overflow-x: auto; }
.mast__link { display: inline-flex; align-items: center; min-height: 56px; padding: 0 12px; color: var(--body); font-size: 14px; font-weight: 550; white-space: nowrap; }
.mast__link:hover { color: var(--ink); }
.mast__link.active { color: var(--primary); box-shadow: inset 0 -2px 0 var(--primary); font-weight: 650; }
.mast__link:focus-visible, .mast-menu__link:focus-visible { outline: 2px solid var(--primary); outline-offset: -2px; }
.mast__tools { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.menu-button, .user-button { border: 0; cursor: pointer; background: transparent; }
.menu-button { display: grid; place-items: center; width: 40px; height: 40px; color: var(--ink); border: 1px solid var(--line); border-radius: 6px; }
.terminal-link { display: inline-flex; align-items: center; gap: 6px; min-height: 40px; padding: 0 10px; color: var(--body); font-size: 13px; font-weight: 600; }
.terminal-link:hover { color: var(--primary); }
.user-button { display: flex; align-items: center; gap: 8px; min-height: 40px; padding: 0 2px; color: var(--ink); border-radius: 6px; }
.avatar { display: grid; place-items: center; width: 28px; height: 28px; color: var(--primary); background: var(--primary-soft); border-radius: 6px; font-size: 12px; font-weight: 700; }
.user-button__text strong, .user-button__text small { display: block; text-align: left; }
.user-button__text strong { font-size: 13px; font-weight: 600; }
.user-button__text small { max-width: 120px; overflow: hidden; color: var(--muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.mast-menu { position: sticky; top: 56px; z-index: 25; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); background: var(--paper); border-bottom: 1px solid var(--line); }
.mast-menu__link { display: flex; align-items: center; min-height: 48px; padding: 0 20px; color: var(--ink); border-top: 1px solid var(--line); font-size: 15px; }
.mast-menu__link.active { color: var(--primary); background: var(--primary-soft); }
.mast-scrim { position: fixed; inset: 112px 0 0; z-index: 20; border: 0; background: rgb(26 29 27 / 28%); cursor: pointer; }
.mast-main { min-height: calc(100dvh - 56px); outline: none; }
@media (max-width: 720px) {
  .mast { padding-inline: 12px; }
  .user-button__text, .terminal-link span { display: none; }
  .terminal-link { width: 40px; justify-content: center; padding: 0; }
}
</style>
