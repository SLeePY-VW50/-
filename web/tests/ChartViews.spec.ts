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

vi.mock('../src/charts', () => ({ echarts: { init: initMock } }))
vi.mock('../src/api/http', () => ({
  default: { get: vi.fn() },
  AUTH_TOKEN_KEY: 'campus.lab.token',
  AUTH_USER_KEY: 'campus.lab.user',
  errorMessage: (error: unknown) => error instanceof Error ? error.message : '请求失败',
}))

function hasLineChart() {
  return setOptionMock.mock.calls.some(([option]) => (option as { series?: Array<{ type?: string }> }).series?.some((series) => series.type === 'line'))
}

function dashboard(date: string) {
  return {
    date, labCount: 1, reservationCount: 0, pendingScheduleCount: 0,
    inUseCount: 0, approvedCount: 0, completedCount: 0, noShowCount: 0, cancelledCount: 0,
    plannedUtilization: 0, actualUtilization: 0, noShowRate: 0, creditScore: 50,
    trend: [{ date, reservationCount: 0, noShowCount: 0 }],
  }
}

function reservation(partial: Record<string, unknown>) {
  return {
    resvId: 9, batchId: null, batchNo: null, labId: 1, labName: '人工智能实验室',
    date: '2026-11-11', windowStart: '14:00', windowEnd: '15:00', durationMinutes: 60,
    startTime: '2026-11-11T14:00:00', endTime: '2026-11-11T15:00:00',
    allocationMode: 'FIXED', reservationMode: 'ROOM', status: 'APPROVED',
    attendeeCount: 1, seatCount: null, purpose: '答辩演示', preferences: {}, seats: [],
    checkinChannel: null, checkinTime: null, checkoutChannel: null, checkoutTime: null, createdAt: '2026-11-10T12:00:00',
    ...partial,
  }
}

function weeklyRules(labId: number, advanceMinDays: number, openTime = '08:00:00', closeTime = '22:00:00') {
  return {
    labId,
    weeklyRules: [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({
      ruleId: dayOfWeek, labId, dayOfWeek, openTime, closeTime,
      minDurationMinutes: 30, maxDurationMinutes: 240, advanceMinDays, advanceMaxDays: 14,
    })),
  }
}

function mountLearner(role: 'STUDENT' | 'TEACHER', name: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().setAuth('token', { userId: 1, account: 'demo', name, roles: [role], status: 'ACTIVE' })
  return mount(DashboardView, {
    global: {
      plugins: [pinia],
      stubs: {
        'router-link': { props: ['to'], template: '<a :href="to"><slot /></a>' },
        'el-icon': { template: '<span><slot /></span>' },
      },
    },
  })
}

describe('图表加载时序', () => {
  beforeEach(() => {
    vi.useRealTimers()
    const pinia = createPinia()
    setActivePinia(pinia)
    initMock.mockClear()
    initMock.mockImplementation(() => ({ setOption: setOptionMock, resize: vi.fn(), dispose: vi.fn() }))
    setOptionMock.mockClear()
    vi.mocked(http.get).mockReset()
  })

  it('管理员工作台渲染后初始化预约趋势图', async () => {
    useAuthStore().setAuth('token', { userId: 9, account: 'admin', name: '测试管理员', roles: ['LAB_ADMIN'], status: 'ACTIVE' })
    vi.mocked(http.get).mockResolvedValue({
      date: '2026-11-03', labCount: 1, reservationCount: 2, pendingScheduleCount: 0,
      inUseCount: 1, approvedCount: 1, completedCount: 0, noShowCount: 0, cancelledCount: 0,
      plannedUtilization: .5, actualUtilization: .4, noShowRate: 0, creditScore: 100,
      trend: [{ date: '2026-11-03', reservationCount: 2, noShowCount: 0 }],
    })
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().setAuth('token', { userId: 9, account: 'admin', name: '测试管理员', roles: ['LAB_ADMIN'], status: 'ACTIVE' })
    const wrapper = mount(DashboardView, { global: { plugins: [pinia], stubs: { 'router-link': { template: '<a><slot /></a>' }, 'el-icon': { template: '<span><slot /></span>' } } } })
    await flushPromises()
    await nextTick()

    expect(wrapper.find('.chart').exists()).toBe(true)
    expect(hasLineChart()).toBe(true)
    wrapper.unmount()
  })

  it('学生工作台展示今日实验焦点而非趋势图', async () => {
    useAuthStore().setAuth('token', { userId: 1, account: 'student', name: '测试学生', roles: ['STUDENT'], status: 'ACTIVE' })
    vi.mocked(http.get).mockImplementation((url: string) => {
      if (url === '/api/statistics/dashboard') {
        return Promise.resolve({
          date: '2026-11-11', labCount: 1, reservationCount: 1, pendingScheduleCount: 0,
          inUseCount: 0, approvedCount: 1, completedCount: 0, noShowCount: 0, cancelledCount: 0,
          plannedUtilization: 0, actualUtilization: 0, noShowRate: 0, creditScore: 70,
          trend: [{ date: '2026-11-11', reservationCount: 1, noShowCount: 0 }],
        })
      }
      if (url === '/api/reservations/my') {
        return Promise.resolve({
          items: [{
            resvId: 9, batchId: null, batchNo: null, labId: 1, labName: '人工智能实验室',
            date: '2026-11-11', windowStart: '14:00', windowEnd: '15:00', durationMinutes: 60,
            startTime: '2026-11-11T14:00:00', endTime: '2026-11-11T15:00:00',
            allocationMode: 'FIXED', reservationMode: 'ROOM', status: 'APPROVED',
            attendeeCount: 1, seatCount: null, purpose: '答辩演示', preferences: {}, seats: [],
            checkinChannel: null, checkinTime: null, checkoutChannel: null, checkoutTime: null, createdAt: '2026-11-10T12:00:00',
          }],
          page: 0, size: 20, total: 1,
        })
      }
      return Promise.reject(new Error(`未知请求：${url}`))
    })
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().setAuth('token', { userId: 1, account: 'student', name: '测试学生', roles: ['STUDENT'], status: 'ACTIVE' })
    const wrapper = mount(DashboardView, { global: { plugins: [pinia], stubs: { 'router-link': { template: '<a><slot /></a>' }, 'el-icon': { template: '<span><slot /></span>' } } } })
    await flushPromises()
    await nextTick()

    expect(wrapper.text()).toContain('下一场实验')
    expect(wrapper.text()).toContain('人工智能实验室')
    expect(wrapper.text()).toContain('到场签到')
    expect(wrapper.text()).toContain('随后')
    expect(wrapper.text()).not.toContain('今天还没有下一场')
    expect(wrapper.text()).not.toContain('即将开放')
    expect(wrapper.find('.session__time').exists()).toBe(true)
    expect(wrapper.find('.session').exists()).toBe(true)
    expect(wrapper.find('.timeline').exists()).toBe(true)
    expect(wrapper.find('.day__side .chart').exists()).toBe(true)
    expect(vi.mocked(http.get).mock.calls.map(([url]) => url)).not.toContain('/api/labs')
    wrapper.unmount()
  })

  it('没有下一场时用四短条引导，并列出真实开放时段', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 11, 10, 0, 0))
    useAuthStore().setAuth('token', { userId: 1, account: 'student', name: '演示学生', roles: ['STUDENT'], status: 'ACTIVE' })
    vi.mocked(http.get).mockImplementation((url: string) => {
      if (url === '/api/statistics/dashboard') return Promise.resolve(dashboard('2026-11-11'))
      if (url === '/api/reservations/my') return Promise.resolve({ items: [], page: 0, size: 20, total: 0 })
      if (url === '/api/labs') return Promise.resolve([
        { labId: 1, code: 'LAB-A', name: '人工智能实验室', location: '创新楼 A301', capacity: 20, status: 'ACTIVE' },
        { labId: 8, code: 'LAB-M', name: '维护中实验室', location: '地下室', capacity: 4, status: 'MAINTENANCE' },
      ])
      if (url === '/api/labs/1/rules') return Promise.resolve(weeklyRules(1, 1))
      return Promise.reject(new Error(`未知请求：${url}`))
    })
    const wrapper = mountLearner('STUDENT', '演示学生')
    await flushPromises()
    await nextTick()

    expect(wrapper.findAll('.empty-state__mark i')).toHaveLength(4)
    expect(wrapper.text()).toContain('今天还没有下一场')
    expect(wrapper.text()).toContain('选择实验室')
    expect(wrapper.text()).toContain('提交预约')
    expect(wrapper.text()).toContain('即将开放')
    expect(wrapper.text()).toContain('人工智能实验室')
    expect(wrapper.text()).toContain('2026-11-12')
    expect(wrapper.text()).toContain('08:00–22:00')
    expect(wrapper.text()).toContain('创新楼 A301')
    expect(wrapper.text()).not.toContain('维护中实验室')
    expect(wrapper.text()).not.toContain('随后')
    expect(wrapper.find('.session__time').exists()).toBe(false)
    const book = wrapper.findAll('a').find((link) => link.text().includes('去时间尺预约'))
    expect(book?.attributes('href')).toBe('/labs')
    wrapper.unmount()
    vi.useRealTimers()
  })

  it('没有下一场但有历史预约时列出近期记录', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 11, 10, 0, 0))
    vi.mocked(http.get).mockImplementation((url: string) => {
      if (url === '/api/statistics/dashboard') return Promise.resolve(dashboard('2026-11-11'))
      if (url === '/api/reservations/my') {
        return Promise.resolve({
          items: [reservation({ resvId: 3, date: '2026-11-09', startTime: '2026-11-09T09:00:00', endTime: '2026-11-09T10:00:00', status: 'COMPLETED', labName: '软件开发实验室', purpose: '课程实验' })],
          page: 0, size: 20, total: 1,
        })
      }
      if (url === '/api/labs') return Promise.resolve([])
      return Promise.reject(new Error(`未知请求：${url}`))
    })
    const wrapper = mountLearner('STUDENT', '演示学生')
    await flushPromises()
    await nextTick()

    expect(wrapper.text()).toContain('近期预约')
    expect(wrapper.text()).toContain('软件开发实验室')
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.text()).toContain('今天还没有下一场')
    expect(wrapper.find('.session__time').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('近期没有预约记录，也没有读到开放时段。')
    expect(wrapper.text()).not.toContain('即将开放')
    wrapper.unmount()
    vi.useRealTimers()
  })

  it('开放窗口已过则顺延到下一天', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 11, 23, 0, 0))
    vi.mocked(http.get).mockImplementation((url: string) => {
      if (url === '/api/statistics/dashboard') return Promise.resolve(dashboard('2026-11-11'))
      if (url === '/api/reservations/my') return Promise.resolve({ items: [], page: 0, size: 20, total: 0 })
      if (url === '/api/labs') return Promise.resolve([{ labId: 4, code: 'LAB-C', name: '嵌入式实验室', location: '实验楼 C201', capacity: 16, status: 'ACTIVE' }])
      if (url === '/api/labs/4/rules') return Promise.resolve(weeklyRules(4, 0, '08:00:00', '22:00:00'))
      return Promise.reject(new Error(`未知请求：${url}`))
    })
    const wrapper = mountLearner('TEACHER', '演示教师')
    await flushPromises()
    await nextTick()

    expect(wrapper.text()).toContain('演示教师')
    expect(wrapper.text()).toContain('今天还没有下一场')
    expect(wrapper.text()).toContain('嵌入式实验室')
    expect(wrapper.text()).toContain('2026-11-12')
    expect(wrapper.text()).not.toContain('2026-11-11 · 08:00–22:00')
    wrapper.unmount()
    vi.useRealTimers()
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
