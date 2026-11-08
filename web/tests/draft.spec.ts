import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useDraftStore } from '../src/stores/draft'
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
})
