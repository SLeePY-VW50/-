import { createRouter, createWebHistory } from 'vue-router'
import type { Role } from '../types'
import { useAuthStore } from '../stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    roles?: Role[]
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true, title: '登录' } },
    { path: '/terminal', name: 'terminal', component: () => import('../views/TerminalView.vue'), meta: { public: true, title: '终端签到' } },
    {
      path: '/',
      component: () => import('../layouts/AppLayout.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', name: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '工作台', roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] } },
        { path: 'labs', name: 'labs', component: () => import('../views/LabsView.vue'), meta: { title: '实验室与预约', roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] } },
        { path: 'reservations', name: 'reservations', component: () => import('../views/ReservationsView.vue'), meta: { title: '预约记录', roles: ['STUDENT', 'TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] } },
        { path: 'assistant', name: 'assistant', component: () => import('../views/AssistantView.vue'), meta: { title: 'AI预约助手', roles: ['STUDENT', 'TEACHER'] } },
        { path: 'schedules', name: 'schedules', component: () => import('../views/ScheduleView.vue'), meta: { title: '排程与对比实验', roles: ['LAB_ADMIN', 'SYS_ADMIN'] } },
        { path: 'statistics', name: 'statistics', component: () => import('../views/StatisticsView.vue'), meta: { title: '使用统计', roles: ['TEACHER', 'LAB_ADMIN', 'SYS_ADMIN'] } },
        { path: 'credit', name: 'credit', component: () => import('../views/CreditView.vue'), meta: { title: '信用与申诉', roles: ['STUDENT', 'TEACHER'] } },
        { path: 'admin/resources', name: 'admin-resources', component: () => import('../views/admin/ResourcesView.vue'), meta: { title: '资源管理', roles: ['SYS_ADMIN'] } },
        { path: 'admin/users', name: 'admin-users', component: () => import('../views/admin/UsersView.vue'), meta: { title: '用户管理', roles: ['SYS_ADMIN'] } },
        { path: 'admin/rules', name: 'admin-rules', component: () => import('../views/admin/RulesView.vue'), meta: { title: '开放规则', roles: ['SYS_ADMIN'] } },
        { path: 'admin/appeals', name: 'admin-appeals', component: () => import('../views/admin/AppealsView.vue'), meta: { title: '申诉核定', roles: ['SYS_ADMIN'] } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true
  if (!auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.roles && !to.meta.roles.some((role) => auth.roles.includes(role))) {
    return { name: 'dashboard' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 校园实验室` : '校园实验室预约平台'
})

export default router
