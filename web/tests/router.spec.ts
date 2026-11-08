import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useAuthStore } from '../src/stores/auth'
import router from '../src/router'
import type { Role } from '../src/types'

const user = (roles: Role[]) => ({
  userId: 1,
  account: 'tester',
  name: '测试用户',
  roles,
  status: 'ACTIVE' as const,
})

describe('路由守卫', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('未登录访问受保护页面时回登录并保留目标', async () => {
    await router.push('/reservations')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/reservations')
  })

  it('角色越权时回工作台', async () => {
    useAuthStore().setAuth('token', user(['STUDENT']))
    await router.push('/admin/users')
    expect(router.currentRoute.value.name).toBe('dashboard')
  }, 20_000)

  it('LAB_ADMIN可查询业务页面但不能进入系统管理或AI页面', async () => {
    useAuthStore().setAuth('token', user(['LAB_ADMIN']))
    for (const path of ['/labs', '/reservations', '/schedules', '/statistics']) {
      await router.push(path)
      expect(router.currentRoute.value.path).toBe(path)
    }
    for (const path of ['/assistant', '/admin/resources', '/admin/rules']) {
      await router.push(path)
      expect(router.currentRoute.value.name).toBe('dashboard')
    }
  })
})
