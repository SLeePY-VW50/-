import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Lab, LabAvailability, LabRules, TimeRule } from '../miniprogram/api/types'
import type { SlotRange } from '../miniprogram/utils/slots'
import { addDays, reservationTime, timeOnly, toDateInput } from '../miniprogram/utils/format'

describe('local formatting', () => {
  it('formats dates without UTC conversion and supports midnight end', () => {
    expect(toDateInput(new Date(2026, 10, 7))).toBe('2026-11-07')
    expect(addDays('2026-11-07', 1)).toBe('2026-11-08')
    expect(timeOnly('2026-11-08T00:00:00', true)).toBe('24:00')
  })

  it('uses formal time or schedulable window', () => {
    const base = { startTime: '2026-11-08T14:00:00', endTime: '2026-11-08T16:00:00', date: '2026-11-08', windowStart: '08:00:00', windowEnd: '12:00:00', durationMinutes: 60 }
    expect(reservationTime(base)).toBe('2026-11-08 14:00–16:00')
    expect(reservationTime({ ...base, startTime: null, endTime: null })).toBe('2026-11-08 08:00–12:00（60分钟）')
  })
})

interface LabsPageDefinition {
  data: {
    labs: Lab[]
    labNames: string[]
    labIndex: number
    labId: number
    date: string
    mode: 'ROOM' | 'SEAT'
    rules: LabRules | null
    availability: LabAvailability | null
    dayRule: TimeRule | null
    openTime: string
    closeTime: string
    selection: SlotRange | null
    selectedText: string
    loading: boolean
    error: string
    errorSource: '' | 'labs' | 'availability'
  }
  requestGeneration: number
  onShow(): Promise<void>
  loadLabs(): Promise<void>
  loadAvailability(): Promise<void>
  retry(): void
}

function instantiateLabsPage(definition: LabsPageDefinition) {
  const page = {
    ...definition,
    data: { ...definition.data },
    setData(patch: Partial<LabsPageDefinition['data']>) { Object.assign(page.data, patch) },
  }
  return page
}

async function setupLabsPage() {
  let definition: LabsPageDefinition | undefined
  const requests: WechatMiniprogram.RequestOption[] = []
  vi.stubGlobal('Page', (page: LabsPageDefinition) => { definition = page })
  vi.stubGlobal('wx', {
    getStorageSync: () => '',
    request: (options: WechatMiniprogram.RequestOption) => { requests.push(options) },
  })
  await import('../miniprogram/pages/labs/index')
  return { page: instantiateLabsPage(definition!), requests }
}

function lab(labId: number): Lab {
  return { labId, code: `L${labId}`, name: `实验室${labId}`, location: '实验楼', capacity: 20, status: 'ACTIVE' }
}

function rules(labId: number, dayOfWeek: number): LabRules {
  return {
    labId,
    weeklyRules: [{ ruleId: labId, labId, dayOfWeek, openTime: '08:00:00', closeTime: '24:00:00', minDurationMinutes: 30, maxDurationMinutes: 240, advanceMinDays: 0, advanceMaxDays: 14 }],
    cancellation: { freeBeforeHours: 24 },
    checkin: { beforeMinutes: 15, graceMinutes: 15, choiceExpireSeconds: 60, maxFailedAttempts: 3, lockoutMinutes: 30 },
  }
}

function availability(labId: number, date: string): LabAvailability {
  return {
    labId,
    date,
    labStatus: 'ACTIVE',
    roomMask: '',
    seats: [],
    slots: [{ index: 16, start: '08:00', end: '08:30', roomOccupied: false, occupiedSeatCount: 0, availableSeatCount: 10 }],
  }
}

function respond(request: WechatMiniprogram.RequestOption, data: unknown) {
  request.success?.({ statusCode: 200, data: { code: 0, message: 'success', data } } as never)
}

describe('laboratory page request ordering', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
  })

  it('ignores a deferred old laboratory and date response after the new one succeeds', async () => {
    const { page, requests } = await setupLabsPage()
    page.setData({ labId: 1, date: '2026-11-08' })
    const oldLoad = page.loadAvailability()
    page.setData({ labId: 2, date: '2026-11-09' })
    const newLoad = page.loadAvailability()

    respond(requests[2]!, rules(2, 1))
    respond(requests[3]!, availability(2, '2026-11-09'))
    await newLoad
    respond(requests[0]!, rules(1, 7))
    respond(requests[1]!, availability(1, '2026-11-08'))
    await oldLoad

    expect(page.data.rules?.labId).toBe(2)
    expect(page.data.dayRule?.dayOfWeek).toBe(1)
    expect(page.data.availability).toEqual(availability(2, '2026-11-09'))
  })

  it('does not let a stale finally clear the latest request loading state', async () => {
    const { page, requests } = await setupLabsPage()
    page.setData({ labId: 1, date: '2026-11-08' })
    const oldLoad = page.loadAvailability()
    page.setData({ labId: 2, date: '2026-11-09' })
    const newLoad = page.loadAvailability()

    respond(requests[0]!, rules(1, 7))
    respond(requests[1]!, availability(1, '2026-11-08'))
    await oldLoad

    expect(page.data.loading).toBe(true)
    respond(requests[2]!, rules(2, 1))
    respond(requests[3]!, availability(2, '2026-11-09'))
    await newLoad
  })

  it('ignores an old list response that arrives after a newer availability load', async () => {
    const { page, requests } = await setupLabsPage()
    page.setData({ labId: 2, date: '2026-11-09' })
    const oldListLoad = page.loadLabs()
    const newAvailabilityLoad = page.loadAvailability()

    respond(requests[1]!, rules(2, 1))
    respond(requests[2]!, availability(2, '2026-11-09'))
    await newAvailabilityLoad
    respond(requests[0]!, [lab(1)])
    await Promise.resolve()
    await Promise.resolve()

    expect(page.data.labId).toBe(2)
    expect(page.data.availability).toEqual(availability(2, '2026-11-09'))
  })

  it('shows a retryable laboratory error instead of an empty state on an offline cold start', async () => {
    const { page, requests } = await setupLabsPage()
    const user = { userId: 1, account: '20240001', name: '演示学生', roles: ['STUDENT'], status: 'ACTIVE' }
    const values = new Map<string, unknown>([['campus.lab.token', 'jwt-1'], ['campus.lab.user', user]])
    vi.stubGlobal('wx', {
      getStorageSync: (key: string) => values.get(key) ?? '',
      showToast: vi.fn(),
      request: (options: WechatMiniprogram.RequestOption) => {
        requests.push(options)
        options.fail?.({ errMsg: 'offline' } as never)
      },
    })

    await page.onShow()

    expect(requests.map(request => request.url)).toEqual([
      'http://127.0.0.1:8080/api/auth/me',
      'http://127.0.0.1:8080/api/labs',
    ])
    expect(page.data).toMatchObject({ error: '网络连接失败，请检查服务是否启动', errorSource: 'labs', loading: false })
  })

  it('retries the list request after a list failure even when a labId remains', async () => {
    const { page, requests } = await setupLabsPage()
    page.setData({ labId: 7 })
    const listLoad = page.loadLabs()
    requests[0]!.fail?.({ errMsg: 'offline' } as never)
    await listLoad

    page.retry()

    expect(requests).toHaveLength(2)
    expect(requests[1]?.url).toBe('http://127.0.0.1:8080/api/labs')
  })
})
