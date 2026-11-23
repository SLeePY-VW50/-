import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useDraftStore } from '../src/stores/draft'
import { buildConfirmedSchedulablePayload } from '../src/schedulableDraft'
import http from '../src/api/http'

vi.mock('../src/api/http', () => ({
  default: { post: vi.fn() },
}))

const draft = {
  labId: 1,
  date: '2026-11-03',
  startTime: '09:00',
  endTime: '11:00',
  allocationMode: 'FIXED' as const,
  reservationMode: 'SEAT' as const,
  seatCount: 2,
  preferences: { minSpecLevel: 'HIGH', nearWindow: true },
}

describe('AI预约草稿', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('只保存并回填草稿，不自动创建预约', () => {
    const store = useDraftStore()
    store.setDraft(draft)
    expect(http.post).not.toHaveBeenCalled()
    expect(store.draft).toEqual(draft)
    expect(store.takeDraft()).toEqual(draft)
    expect(http.post).not.toHaveBeenCalled()
    expect(store.draft).toBeNull()
  })

  it('可调度草稿未确认时不能提交，确认后才生成排程请求', () => {
    const schedulable = {
      allocationMode: 'SCHEDULABLE' as const,
      date: '2026-11-03',
      windowStart: '08:30',
      windowEnd: '12:30',
      durationPeriods: 2,
      reservationMode: 'SEAT' as const,
      attendeeCount: 3,
      preferences: { minSpecLevel: 'HIGH' },
    }
    expect(() => buildConfirmedSchedulablePayload(schedulable, false, '嵌入式实验')).toThrow('未经确认不能提交')
    expect(http.post).not.toHaveBeenCalled()
    expect(buildConfirmedSchedulablePayload(schedulable, true, ' 嵌入式实验 ')).toEqual({
      allocationMode: 'SCHEDULABLE',
      labId: null,
      date: '2026-11-03',
      startTime: null,
      endTime: null,
      windowStart: '08:30',
      windowEnd: '12:30',
      durationPeriods: 2,
      reservationMode: 'SEAT',
      attendeeCount: 3,
      seatCount: 3,
      purpose: '嵌入式实验',
      preferences: { zoneCode: null, nearWindow: null, minSpecLevel: 'HIGH', allowedLabIds: [] },
    })
    expect(http.post).not.toHaveBeenCalled()
  })
})
