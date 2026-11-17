import { beforeEach, describe, expect, it, vi } from 'vitest'
import { recommendationFormQuery, recommendationNote } from '../miniprogram/utils/recommendations'

describe('slot recommendation entry', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
  })

  it('explains an empty or blocked recommendation without inventing a slot', () => {
    expect(recommendationNote('NONE_FREE')).toBe('没有足够的连续空闲时段')
    expect(recommendationNote('CREDIT_BLOCKED')).toContain('信用分')
    expect(recommendationNote('RULE_BLOCKED')).toContain('预约规则')
    expect(recommendationNote('DURATION_UNFIT')).toContain('开放时间')
    expect(recommendationNote('AVAILABLE')).toBe('')
  })

  it('one tap fills the fixed reservation form and does not submit', async () => {
    let definition: {
      data: {
        labId: number
        date: string
        mode: 'ROOM' | 'SEAT'
        attendeeCount: number
        recommendations: Array<{ startTime: string; endTime: string }>
      }
      useRecommendation(event: { currentTarget: { dataset: { index: number } } }): void
    } | undefined
    const navigateTo = vi.fn()
    const request = vi.fn()
    vi.stubGlobal('Page', (page: typeof definition) => { definition = page })
    vi.stubGlobal('wx', { navigateTo, request, getStorageSync: () => '' })
    await import('../miniprogram/pages/labs/index')
    const page = {
      ...definition!,
      data: {
        ...definition!.data,
        labId: 4,
        date: '2026-11-08',
        mode: 'ROOM' as const,
        attendeeCount: 6,
        recommendations: [{ startTime: '08:00', endTime: '09:00' }],
      },
    }

    page.useRecommendation({ currentTarget: { dataset: { index: 0 } } })

    expect(request).not.toHaveBeenCalled()
    expect(navigateTo).toHaveBeenCalledWith({
      url: `/pages/reservation-form/index?${recommendationFormQuery({
        labId: 4,
        date: '2026-11-08',
        mode: 'ROOM',
        attendeeCount: 6,
        startTime: '08:00',
        endTime: '09:00',
      })}`,
    })
  })
})
