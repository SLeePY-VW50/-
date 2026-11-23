import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import LabsView from '../src/views/LabsView.vue'
import { useAuthStore } from '../src/stores/auth'
import http from '../src/api/http'

vi.mock('../src/api/http', () => ({
  default: { get: vi.fn(), post: vi.fn() },
  AUTH_TOKEN_KEY: 'campus.lab.token',
  AUTH_USER_KEY: 'campus.lab.user',
  errorMessage: (error: unknown) => error instanceof Error ? error.message : '请求失败',
}))

const rules = {
  labId: 1,
  weeklyRules: [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({
    ruleId: dayOfWeek,
    labId: 1,
    dayOfWeek,
    openTime: '08:00:00',
    closeTime: '22:00:00',
    openStartPeriod: 1,
    openEndPeriod: 10,
    minPeriods: 1,
    maxPeriods: 5,
    advanceMinDays: 1,
    advanceMaxDays: 14,
  })),
  cancellation: { freeBeforeHours: 2 },
  checkin: { beforeMinutes: 15, graceMinutes: 15, choiceExpireSeconds: 30, maxFailedAttempts: 5, lockoutMinutes: 10 },
}

const availability = {
  labId: 1,
  date: '2026-11-12',
  labStatus: 'ACTIVE',
  roomMask: '0',
  slots: [],
  seats: [
    { seatId: 1, seatCode: 'LAB-A-SEAT-01', zoneCode: 'LAB-A-NORTH', seatIndex: 1, specLevel: 'STANDARD', nearWindow: true, status: 'ACTIVE', mask: '0' },
    { seatId: 15, seatCode: 'LAB-A-SEAT-15', zoneCode: 'LAB-A-SOUTH', seatIndex: 15, specLevel: 'HIGH', nearWindow: false, status: 'ACTIVE', mask: '0' },
    { seatId: 21, seatCode: 'CHECKIN-SEAT-02', zoneCode: 'TEST', seatIndex: 2, specLevel: 'STANDARD', nearWindow: false, status: 'ACTIVE', mask: '0' },
  ],
}

async function mountLabs() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().setAuth('token', {
    userId: 1, account: '20240001', name: '演示学生', roles: ['STUDENT'], status: 'ACTIVE',
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  const wrapper = mount(LabsView, {
    global: {
      plugins: [pinia, router],
      stubs: { 'el-dialog': { template: '<div />' } },
    },
  })
  await flushPromises()
  return wrapper
}

describe('时间尺工位列表', () => {
  beforeEach(() => {
    vi.mocked(http.get).mockImplementation((url: string) => {
      if (url === '/api/labs') {
        return Promise.resolve([{ labId: 1, code: 'LAB-A', name: '人工智能实验室', location: '创新楼 A301', capacity: 20, status: 'ACTIVE' }])
      }
      if (url.endsWith('/rules')) return Promise.resolve(rules)
      if (url.includes('/availability')) return Promise.resolve(availability)
      return Promise.reject(new Error(url))
    })
  })

  it('工位模式只显示短编号，整室模式不列出工位', async () => {
    const wrapper = await mountLabs()

    expect(wrapper.find('.seat').exists()).toBe(false)
    expect(wrapper.find('.seat-note').exists()).toBe(false)

    await wrapper.findAll('button').find((button) => button.text() === '工位')!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.seat').map((seat) => seat.text())).toEqual(['01', '15', '02'])
    expect(wrapper.find('.seat-row').text()).not.toContain('LAB-')
    expect(wrapper.find('.seat-row').text()).not.toContain('SEAT-')
    expect(wrapper.find('button.seat').exists()).toBe(false)
    expect(wrapper.get('.seat').attributes('title')).toContain('LAB-A-SEAT-01')
    const note = wrapper.get('.seat-note')
    expect(note.text()).toBe('工位余量显示在开放格右侧。具体工位在提交后分配，这里只供查看。')
    expect(wrapper.get('.seat-row').element.compareDocumentPosition(note.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const strayA = wrapper.findAll('.rail *').filter((node) => node.text().trim() === 'A' && node.find('*').exists() === false)
    expect(strayA).toHaveLength(0)

    await wrapper.findAll('button').find((button) => button.text() === '整室')!.trigger('click')
    expect(wrapper.find('.seat').exists()).toBe(false)
    expect(wrapper.find('.seat-note').exists()).toBe(false)
  })

  it('点选后可以重置并重新选择', async () => {
    const periods = [
      ['08:30', '09:15', 'MORNING'], ['09:15', '10:00', 'MORNING'], ['10:05', '10:50', 'MORNING'],
      ['11:00', '11:45', 'MORNING'], ['11:45', '12:30', 'MORNING'],
    ] as const
    const slots = periods.map(([start, end, session], index) => ({
      index, periodNo: index + 1, session, label: `第${index + 1}节`, start, end, bookable: true,
      roomOccupied: false, occupiedSeatCount: 0, availableSeatCount: 20,
    }))
    vi.mocked(http.get).mockImplementation((url: string) => {
      if (url === '/api/labs') {
        return Promise.resolve([{ labId: 1, code: 'LAB-A', name: '人工智能实验室', location: '创新楼 A301', capacity: 20, status: 'ACTIVE' }])
      }
      if (url.endsWith('/rules')) return Promise.resolve(rules)
      if (url.includes('/availability')) return Promise.resolve({ ...availability, slots })
      return Promise.reject(new Error(url))
    })

    const wrapper = await mountLabs()
    await wrapper.findAll('.cell')[0].trigger('click')
    expect(wrapper.get('.range span').text()).toBe('08:30–09:15，1 节')
    await wrapper.findAll('.cell')[1].trigger('click')
    expect(wrapper.get('.range span').text()).toBe('08:30–10:00，2 节')
    expect(wrapper.find('button.reset').exists()).toBe(true)

    await wrapper.get('button.reset').trigger('click')
    expect(wrapper.get('.range span').text()).toBe('在开放格上点选一段连续时段')
    expect(wrapper.find('button.reset').exists()).toBe(false)

    await wrapper.findAll('.cell')[2].trigger('click')
    expect(wrapper.get('.range span').text()).toBe('10:05–10:50，1 节')
  })

  it('点击推荐时段只回填时间尺，不提交预约', async () => {
    const slots = [{
      index: 0, periodNo: 1, session: 'MORNING' as const, label: '第1节', start: '08:30', end: '09:15', bookable: true,
      roomOccupied: false, occupiedSeatCount: 0, availableSeatCount: 20,
    }, {
      index: 1, periodNo: 2, session: 'MORNING' as const, label: '第2节', start: '09:15', end: '10:00', bookable: true,
      roomOccupied: false, occupiedSeatCount: 0, availableSeatCount: 20,
    }]
    vi.mocked(http.get).mockImplementation((url: string) => {
      if (url === '/api/labs') {
        return Promise.resolve([{ labId: 1, code: 'LAB-A', name: '人工智能实验室', location: '创新楼 A301', capacity: 20, status: 'ACTIVE' }])
      }
      if (url.endsWith('/rules')) return Promise.resolve(rules)
      if (url.includes('/availability')) return Promise.resolve({ ...availability, slots })
      if (url.includes('/recommendations')) {
        return Promise.resolve({
          outcome: 'AVAILABLE',
          recommendations: [{ startSlot: 0, endSlot: 2, startTime: '08:30', endTime: '10:00', durationPeriods: 2 }],
        })
      }
      return Promise.reject(new Error(url))
    })

    const wrapper = await mountLabs()
    await wrapper.get('[data-testid="recommend-duration"]').setValue('2')
    await wrapper.get('[data-testid="recommend-attendees"]').setValue('2')
    await wrapper.get('[data-testid="recommend"]').trigger('click')
    await flushPromises()

    expect(http.get).toHaveBeenCalledWith('/api/reservations/recommendations', expect.objectContaining({
      params: expect.objectContaining({ labId: 1, durationPeriods: 2, reservationMode: 'ROOM', attendeeCount: 2 }),
    }))
    await wrapper.get('[data-testid="recommendation"]').trigger('click')
    expect(wrapper.get('.range span').text()).toBe('08:30–10:00，2 节')
    expect(http.post).not.toHaveBeenCalled()
  })
})
