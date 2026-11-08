import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ReservationForm from '../src/components/ReservationForm.vue'
import http from '../src/api/http'

vi.mock('../src/api/http', () => ({
  default: { post: vi.fn() },
  errorMessage: (error: unknown) => error instanceof Error ? error.message : '请求失败',
}))

const labs = [{ labId: 7, code: 'LAB-A', name: '创新实验室', location: 'A301', capacity: 40, status: 'ACTIVE' as const }]
const response = { resvId: 99 }

async function mountForm() {
  const wrapper = mount(ReservationForm, {
    props: { labs },
    global: { plugins: [createPinia()] },
  })
  await flushPromises()
  return wrapper
}

async function fillCommon(wrapper: Awaited<ReturnType<typeof mountForm>>) {
  await wrapper.get('[data-testid="lab"]').setValue('7')
  await wrapper.get('[data-testid="date"]').setValue('2026-11-03')
  await wrapper.get('[data-testid="attendee-count"]').setValue('2')
  await wrapper.get('[data-testid="purpose"]').setValue('嵌入式课程实验')
}

describe('ReservationForm', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(http.post).mockResolvedValue(response)
  })

  it('FIXED ROOM提交开始结束时间且不发送seatCount', async () => {
    const wrapper = await mountForm()
    await fillCommon(wrapper)
    await wrapper.get('[data-testid="start-time"]').setValue('09:00')
    await wrapper.get('[data-testid="end-time"]').setValue('11:00')
    await wrapper.get('form').trigger('submit.prevent')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/api/reservations', expect.objectContaining({
      allocationMode: 'FIXED', reservationMode: 'ROOM', attendeeCount: 2,
      startTime: '09:00', endTime: '11:00', seatCount: null,
    }))
  })

  it('SCHEDULABLE SEAT提交窗口时长并同步seatCount与人数', async () => {
    const wrapper = await mountForm()
    await wrapper.get('[data-testid="allocation-schedulable"]').setValue(true)
    await wrapper.get('[data-testid="mode-seat"]').setValue(true)
    await fillCommon(wrapper)
    await wrapper.get('[data-testid="window-start"]').setValue('08:00')
    await wrapper.get('[data-testid="window-end"]').setValue('12:00')
    await wrapper.get('[data-testid="duration"]').setValue('120')
    await wrapper.get('form').trigger('submit.prevent')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/api/reservations', expect.objectContaining({
      allocationMode: 'SCHEDULABLE', reservationMode: 'SEAT', attendeeCount: 2, seatCount: 2,
      windowStart: '08:00', windowEnd: '12:00', durationMinutes: 120,
    }))
  })

  it('支持24:00作为结束时间', async () => {
    const wrapper = await mountForm()
    await fillCommon(wrapper)
    await wrapper.get('[data-testid="start-time"]').setValue('23:00')
    await wrapper.get('[data-testid="end-time"]').setValue('24:00')
    await wrapper.get('form').trigger('submit.prevent')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/api/reservations', expect.objectContaining({ endTime: '24:00' }))
  })

  it('冲突报错时保留输入且不重复自动提交', async () => {
    vi.mocked(http.post).mockRejectedValueOnce(new Error('所选时段与已有预约冲突'))
    const wrapper = await mountForm()
    await fillCommon(wrapper)
    await wrapper.get('[data-testid="start-time"]').setValue('09:00')
    await wrapper.get('[data-testid="end-time"]').setValue('11:00')
    await wrapper.get('form').trigger('submit.prevent')
    await flushPromises()
    expect(wrapper.text()).toContain('所选时段与已有预约冲突')
    expect((wrapper.get('[data-testid="purpose"]').element as HTMLTextAreaElement).value).toBe('嵌入式课程实验')
    expect((wrapper.get('[data-testid="attendee-count"]').element as HTMLInputElement).value).toBe('2')
    expect(http.post).toHaveBeenCalledTimes(1)
  })
})
