import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import DashboardView from '../src/views/DashboardView.vue'
import StatisticsView from '../src/views/StatisticsView.vue'
import { useAuthStore } from '../src/stores/auth'
import http from '../src/api/http'

const { initMock, setOptionMock } = vi.hoisted(() => ({
  initMock: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
  setOptionMock: vi.fn(),
}))
initMock.mockImplementation(() => ({ setOption: setOptionMock, resize: vi.fn(), dispose: vi.fn() }))

vi.mock('echarts', () => ({ init: initMock }))
vi.mock('../src/api/http', () => ({
  default: { get: vi.fn() },
  AUTH_TOKEN_KEY: 'campus.lab.token',
  AUTH_USER_KEY: 'campus.lab.user',
  errorMessage: (error: unknown) => error instanceof Error ? error.message : '请求失败',
}))

function hasLineChart() {
  return setOptionMock.mock.calls.some(([option]) => (option as { series?: Array<{ type?: string }> }).series?.some((series) => series.type === 'line'))
}

describe('图表加载时序', () => {
  beforeEach(() => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().setAuth('token', { userId: 1, account: 'student', name: '测试学生', roles: ['STUDENT'], status: 'ACTIVE' })
    initMock.mockClear()
    initMock.mockImplementation(() => ({ setOption: setOptionMock, resize: vi.fn(), dispose: vi.fn() }))
    setOptionMock.mockClear()
    vi.mocked(http.get).mockReset()
  })

  it('工作台数据渲染后初始化预约趋势图', async () => {
    vi.mocked(http.get).mockResolvedValue({
      date: '2026-11-03', labCount: 1, reservationCount: 2, pendingScheduleCount: 0,
      inUseCount: 1, approvedCount: 1, completedCount: 0, noShowCount: 0, cancelledCount: 0,
      plannedUtilization: .5, actualUtilization: .4, noShowRate: 0, creditScore: 100,
      trend: [{ date: '2026-11-03', reservationCount: 2, noShowCount: 0 }],
    })
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().setAuth('token', { userId: 1, account: 'student', name: '测试学生', roles: ['STUDENT'], status: 'ACTIVE' })
    const wrapper = mount(DashboardView, { global: { plugins: [pinia], stubs: { 'router-link': { template: '<a><slot /></a>' }, 'el-icon': { template: '<span><slot /></span>' } } } })
    await flushPromises()
    await nextTick()

    expect(wrapper.find('.chart').exists()).toBe(true)
    expect(hasLineChart()).toBe(true)
    wrapper.unmount()
  })

  it('首次统计数据返回后初始化日期趋势图', async () => {
    vi.mocked(http.get).mockImplementation((url: string) => {
      if (url === '/api/labs') return Promise.resolve([{ labId: 7, code: 'LAB-A', name: '创新实验室', location: 'A301', capacity: 40, status: 'ACTIVE' }])
      if (url === '/api/statistics/heatmap') return Promise.resolve([{ labId: 7, date: '2026-11-03', slots: [{ slot: 0, start: '00:00', end: '00:30', plannedRatio: .5, actualRatio: .4 }] }])
      return Promise.reject(new Error(`未知请求：${url}`))
    })
    const wrapper = mount(StatisticsView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    await nextTick()
    await flushPromises()

    expect(wrapper.find('.chart').exists()).toBe(true)
    expect(hasLineChart()).toBe(true)
    wrapper.unmount()
  })
})
