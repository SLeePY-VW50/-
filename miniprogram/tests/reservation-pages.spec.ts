import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Reservation, ReservationStatus } from '../miniprogram/api/types'

vi.mock('../miniprogram/api/reservations', () => ({
  myReservations: vi.fn(),
  reservationDetail: vi.fn(),
}))
vi.mock('../miniprogram/actions/reservations', () => ({
  cancelReservationAction: vi.fn(),
  checkoutFromWeChat: vi.fn(),
  scanAndCheckin: vi.fn(),
}))
vi.mock('../miniprogram/auth/session', () => ({ restoreSession: vi.fn() }))

import { myReservations, reservationDetail } from '../miniprogram/api/reservations'
import { cancelReservationAction, checkoutFromWeChat, scanAndCheckin } from '../miniprogram/actions/reservations'
import { restoreSession } from '../miniprogram/auth/session'

declare const require: (id: string) => { readFileSync(path: string, encoding: string): string }

type ReservationItem = Reservation & { timeText: string; statusText: string; canCancel: boolean }

interface ReservationListDefinition {
  data: {
    items: ReservationItem[]
    labels: Record<string, string>
    status: ReservationStatus | ''
    filters: Array<{ value: ReservationStatus | ''; label: string }>
    page: number
    total: number
    loading: boolean
    error: string
  }
  onShow(): Promise<void>
  reload(): Promise<void>
  loadMore(): Promise<void>
  onReachBottom(): Promise<void>
  onFilter(event: WechatMiniprogram.TouchEvent): Promise<void>
  open(event: WechatMiniprogram.TouchEvent): void
}

interface ReservationDetailDefinition {
  data: {
    id: number
    detail: Reservation | null
    labels: Record<string, string>
    timeText: string
    statusText: string
    seatText: string
    checkinText: string
    checkoutText: string
    canCancel: boolean
    canScan: boolean
    canCheckout: boolean
    loading: boolean
    actionLoading: boolean
    error: string
  }
  onLoad(query: Record<string, string>): Promise<void>
  load(): Promise<void>
  scan(): Promise<void>
  cancel(): Promise<void>
  checkout(): Promise<void>
}

function instantiate<T extends { data: object }>(definition: T) {
  const page = {
    ...definition,
    data: { ...definition.data },
    setData(patch: Partial<T['data']>) { Object.assign(page.data, patch) },
  }
  return page
}

async function loadListPage() {
  vi.resetModules()
  let definition: ReservationListDefinition | undefined
  vi.stubGlobal('Page', (page: ReservationListDefinition) => { definition = page })
  await import('../miniprogram/pages/reservations/index')
  return instantiate(definition!)
}

async function loadDetailPage() {
  vi.resetModules()
  let definition: ReservationDetailDefinition | undefined
  vi.stubGlobal('Page', (page: ReservationDetailDefinition) => { definition = page })
  await import('../miniprogram/pages/reservation-detail/index')
  return instantiate(definition!)
}

function reservation(overrides: Partial<Reservation> = {}): Reservation {
  return {
    resvId: 7,
    batchId: null,
    batchNo: null,
    labId: 3,
    labName: '创新实验室',
    date: '2026-11-08',
    windowStart: '08:00:00',
    windowEnd: '12:00:00',
    durationMinutes: 120,
    startTime: '2026-11-08T09:00:00',
    endTime: '2026-11-08T11:00:00',
    allocationMode: 'FIXED',
    reservationMode: 'SEAT',
    status: 'APPROVED',
    attendeeCount: 2,
    seatCount: 2,
    purpose: '项目实验',
    preferences: {},
    seats: [
      { seatId: 1, seatCode: 'A-01', zoneCode: 'NORTH', seatIndex: 1, specLevel: 'HIGH', mask: '', zoneMismatch: false, nearWindowMismatch: false },
      { seatId: 2, seatCode: 'A-02', zoneCode: 'NORTH', seatIndex: 2, specLevel: 'HIGH', mask: '', zoneMismatch: false, nearWindowMismatch: false },
    ],
    checkinChannel: null,
    checkinTime: null,
    checkoutChannel: null,
    checkoutTime: null,
    createdAt: '2026-11-07T10:00:00',
    ...overrides,
  }
}

function touch(value: string | number, key = 'value') {
  return { currentTarget: { dataset: { [key]: value } } } as unknown as WechatMiniprogram.TouchEvent
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(restoreSession).mockResolvedValue({ userId: 1 } as never)
})

afterEach(() => vi.unstubAllGlobals())

describe('reservation list page', () => {
  it('restores the session and loads formatted reservations from the real my endpoint', async () => {
    vi.mocked(myReservations).mockResolvedValue({ items: [reservation()], page: 0, size: 10, total: 1 })
    const page = await loadListPage()

    await page.onShow()

    expect(restoreSession).toHaveBeenCalledOnce()
    expect(myReservations).toHaveBeenCalledWith(undefined, 0)
    expect(page.data.items[0]).toMatchObject({
      resvId: 7,
      timeText: '2026-11-08 09:00–11:00',
      statusText: '已通过',
      canCancel: true,
    })
    expect(page.data).toMatchObject({ page: 1, total: 1, loading: false, error: '' })
  })

  it('shows a retryable error instead of an empty state when loading offline', async () => {
    vi.mocked(myReservations).mockRejectedValue(new Error('网络连接失败'))
    const page = await loadListPage()

    await page.onShow()

    expect(page.data).toMatchObject({ items: [], error: '网络连接失败', loading: false })
    const { readFileSync } = require('node:fs')
    const wxml = readFileSync('miniprogram/pages/reservations/index.wxml', 'utf8')
    expect(wxml).toContain('bindtap="reload">重试</text>')
    expect(wxml).toContain('wx:if="{{!items.length && !loading && !error}}"')
  })

  it('does not request reservations when session restoration fails', async () => {
    vi.mocked(restoreSession).mockResolvedValue(null as never)
    const page = await loadListPage()

    await page.onShow()

    expect(myReservations).not.toHaveBeenCalled()
  })

  it('applies a status filter by reloading from page zero', async () => {
    vi.mocked(myReservations).mockResolvedValue({ items: [], page: 0, size: 10, total: 0 })
    const page = await loadListPage()
    page.setData({ page: 2, items: [{ ...reservation(), timeText: '', statusText: '', canCancel: true }], total: 3 })

    await page.onFilter(touch('IN_USE'))

    expect(myReservations).toHaveBeenCalledWith('IN_USE', 0)
    expect(page.data).toMatchObject({ status: 'IN_USE', page: 1, items: [], total: 0 })
  })

  it('appends the next page on reach bottom and stops at the total', async () => {
    vi.mocked(myReservations)
      .mockResolvedValueOnce({ items: [reservation({ resvId: 1 })], page: 0, size: 10, total: 2 })
      .mockResolvedValueOnce({ items: [reservation({ resvId: 2 })], page: 1, size: 10, total: 2 })
    const page = await loadListPage()

    await page.loadMore()
    await page.onReachBottom()
    await page.onReachBottom()

    expect(myReservations).toHaveBeenCalledTimes(2)
    expect(myReservations).toHaveBeenNthCalledWith(2, undefined, 1)
    expect(page.data.items.map(item => item.resvId)).toEqual([1, 2])
    expect(page.data.page).toBe(2)
  })

  it('keeps loaded items after an error and retries a clean reload', async () => {
    vi.mocked(myReservations)
      .mockResolvedValueOnce({ items: [reservation()], page: 0, size: 10, total: 2 })
      .mockRejectedValueOnce(new Error('记录读取失败'))
      .mockResolvedValueOnce({ items: [reservation({ resvId: 8 })], page: 0, size: 10, total: 1 })
    const page = await loadListPage()
    await page.loadMore()

    await page.loadMore()
    expect(page.data.items).toHaveLength(1)
    expect(page.data).toMatchObject({ error: '记录读取失败', loading: false })

    await page.reload()
    expect(myReservations).toHaveBeenLastCalledWith(undefined, 0)
    expect(page.data.items.map(item => item.resvId)).toEqual([8])
    expect(page.data.error).toBe('')
  })

  it('invalidates an in-flight response when the filter changes', async () => {
    const previous = deferred<Awaited<ReturnType<typeof myReservations>>>()
    const next = deferred<Awaited<ReturnType<typeof myReservations>>>()
    vi.mocked(myReservations).mockReturnValueOnce(previous.promise).mockReturnValueOnce(next.promise)
    const page = await loadListPage()
    const initial = page.loadMore()

    const filtering = page.onFilter(touch('IN_USE'))
    previous.resolve({ items: [reservation({ resvId: 1 })], page: 0, size: 10, total: 1 })
    await initial

    expect(myReservations).toHaveBeenCalledTimes(2)
    expect(myReservations).toHaveBeenNthCalledWith(2, 'IN_USE', 0)
    expect(page.data).toMatchObject({ status: 'IN_USE', items: [], page: 0, error: '', loading: true })

    next.resolve({ items: [reservation({ resvId: 8 })], page: 0, size: 10, total: 1 })
    await filtering
    expect(page.data.items.map(item => item.resvId)).toEqual([8])
    expect(page.data).toMatchObject({ page: 1, loading: false })
  })

  it('starts a fresh reload and ignores errors and completion from the old request', async () => {
    const previous = deferred<Awaited<ReturnType<typeof myReservations>>>()
    const next = deferred<Awaited<ReturnType<typeof myReservations>>>()
    vi.mocked(myReservations).mockReturnValueOnce(previous.promise).mockReturnValueOnce(next.promise)
    const page = await loadListPage()
    const initial = page.loadMore()

    const reloaded = page.reload()
    expect(myReservations).toHaveBeenCalledTimes(2)

    previous.reject(new Error('旧请求失败'))
    await initial
    expect(page.data).toMatchObject({ items: [], page: 0, error: '', loading: true })

    next.resolve({ items: [reservation({ resvId: 9 })], page: 0, size: 10, total: 1 })
    await reloaded
    expect(page.data.items.map(item => item.resvId)).toEqual([9])
    expect(page.data).toMatchObject({ page: 1, error: '', loading: false })
  })

  it('opens the selected reservation detail', async () => {
    const navigateTo = vi.fn()
    vi.stubGlobal('wx', { navigateTo })
    const page = await loadListPage()

    page.open(touch(42, 'id'))

    expect(navigateTo).toHaveBeenCalledWith({ url: '/pages/reservation-detail/index?id=42' })
  })
})

describe('reservation detail page', () => {
  it('restores the session and presents formal time, seats and check-in details', async () => {
    vi.mocked(reservationDetail).mockResolvedValue(reservation({
      checkinTime: '2026-11-08T09:02:03',
      checkoutTime: '2026-11-08T10:30:00',
    }))
    const page = await loadDetailPage()

    await page.onLoad({ id: '7' })

    expect(reservationDetail).toHaveBeenCalledWith(7)
    expect(page.data).toMatchObject({
      id: 7,
      timeText: '2026-11-08 09:00–11:00',
      statusText: '已通过',
      seatText: 'A-01、A-02',
      checkinText: '2026-11-08 09:02:03',
      checkoutText: '2026-11-08 10:30:00',
      canCancel: true,
      canScan: true,
      canCheckout: false,
      loading: false,
      error: '',
    })
    expect(page.data.detail?.purpose).toBe('项目实验')
  })

  it('shows a visible retry after the initial detail request fails and loads on retry', async () => {
    vi.mocked(reservationDetail).mockRejectedValueOnce(new Error('网络连接失败')).mockResolvedValueOnce(reservation())
    const page = await loadDetailPage()

    await page.onLoad({ id: '7' })

    expect(page.data).toMatchObject({ detail: null, error: '网络连接失败', loading: false })
    const { readFileSync } = require('node:fs')
    const wxml = readFileSync('miniprogram/pages/reservation-detail/index.wxml', 'utf8')
    expect(wxml).toMatch(/<button[^>]*bindtap="load"[^>]*>重试<\/button>/)

    await page.load()

    expect(reservationDetail).toHaveBeenCalledTimes(2)
    expect(page.data).toMatchObject({ id: 7, detail: reservation(), error: '', loading: false })
  })

  it('reloads the real detail after a successful scan but not after scan cancellation', async () => {
    vi.mocked(scanAndCheckin).mockResolvedValueOnce(true).mockResolvedValueOnce(false)
    vi.mocked(reservationDetail).mockResolvedValue(reservation({ status: 'IN_USE' }))
    const page = await loadDetailPage()
    page.setData({ id: 7 })

    await page.scan()
    expect(reservationDetail).toHaveBeenCalledWith(7)
    expect(page.data).toMatchObject({ canCheckout: true, canScan: false, actionLoading: false })

    await page.scan()
    expect(reservationDetail).toHaveBeenCalledOnce()
  })

  it('requires confirmation before cancellation and reloads the latest detail', async () => {
    const showModal = vi.fn().mockResolvedValue({ confirm: true })
    vi.stubGlobal('wx', { showModal })
    vi.mocked(cancelReservationAction).mockResolvedValue(reservation({ status: 'CANCELLED' }))
    vi.mocked(reservationDetail).mockResolvedValue(reservation({ status: 'CANCELLED' }))
    const page = await loadDetailPage()
    page.setData({ id: 7 })

    await page.cancel()

    expect(showModal).toHaveBeenCalledWith(expect.objectContaining({ title: '取消预约', confirmText: '确认取消' }))
    expect(cancelReservationAction).toHaveBeenCalledWith(7)
    expect(reservationDetail).toHaveBeenCalledWith(7)
    expect(page.data).toMatchObject({ statusText: '已取消', canCancel: false, actionLoading: false })
  })

  it('does nothing when cancellation is not confirmed', async () => {
    vi.stubGlobal('wx', { showModal: vi.fn().mockResolvedValue({ confirm: false }) })
    const page = await loadDetailPage()
    page.setData({ id: 7 })

    await page.cancel()

    expect(cancelReservationAction).not.toHaveBeenCalled()
    expect(reservationDetail).not.toHaveBeenCalled()
  })

  it('keeps the current detail and shows the server error when cancellation is rejected', async () => {
    vi.stubGlobal('wx', { showModal: vi.fn().mockResolvedValue({ confirm: true }) })
    vi.mocked(cancelReservationAction).mockRejectedValue(new Error('当前状态不可取消'))
    const current = reservation()
    const page = await loadDetailPage()
    page.setData({ id: 7, detail: current, statusText: '已通过' })

    await page.cancel()

    expect(page.data.detail).toBe(current)
    expect(page.data).toMatchObject({ error: '当前状态不可取消', actionLoading: false })
    expect(reservationDetail).not.toHaveBeenCalled()
  })

  it('requires confirmation before early checkout and reloads the latest detail', async () => {
    const showModal = vi.fn().mockResolvedValue({ confirm: true })
    vi.stubGlobal('wx', { showModal })
    vi.mocked(checkoutFromWeChat).mockResolvedValue(reservation({ status: 'COMPLETED' }))
    vi.mocked(reservationDetail).mockResolvedValue(reservation({ status: 'COMPLETED', checkoutTime: '2026-11-08T10:00:00' }))
    const page = await loadDetailPage()
    page.setData({ id: 7 })

    await page.checkout()

    expect(showModal).toHaveBeenCalledWith(expect.objectContaining({ title: '提前签退', confirmText: '确认签退' }))
    expect(checkoutFromWeChat).toHaveBeenCalledWith(7)
    expect(reservationDetail).toHaveBeenCalledWith(7)
    expect(page.data).toMatchObject({ statusText: '已完成', checkoutText: '2026-11-08 10:00:00', canCheckout: false })
  })

  it('keeps the current detail and shows the server error when checkout is rejected', async () => {
    vi.stubGlobal('wx', { showModal: vi.fn().mockResolvedValue({ confirm: true }) })
    vi.mocked(checkoutFromWeChat).mockRejectedValue(new Error('当前状态不可签退'))
    const current = reservation({ status: 'IN_USE' })
    const page = await loadDetailPage()
    page.setData({ id: 7, detail: current, statusText: '使用中' })

    await page.checkout()

    expect(page.data.detail).toBe(current)
    expect(page.data).toMatchObject({ error: '当前状态不可签退', actionLoading: false })
    expect(reservationDetail).not.toHaveBeenCalled()
  })

  it('locks scanning synchronously against duplicate entry', async () => {
    const scanning = deferred<boolean>()
    vi.mocked(scanAndCheckin).mockReturnValue(scanning.promise)
    const page = await loadDetailPage()
    page.setData({ id: 7 })

    const first = page.scan()
    const second = page.scan()
    expect(scanAndCheckin).toHaveBeenCalledOnce()
    expect(page.data.actionLoading).toBe(true)

    scanning.resolve(false)
    await Promise.all([first, second])
    expect(page.data.actionLoading).toBe(false)
    expect(reservationDetail).not.toHaveBeenCalled()
  })

  it('locks cancellation before confirmation and runs only one mutation for concurrent entries', async () => {
    const confirmation = deferred<{ confirm: boolean }>()
    const showModal = vi.fn().mockReturnValue(confirmation.promise)
    vi.stubGlobal('wx', { showModal })
    vi.mocked(cancelReservationAction).mockResolvedValue(reservation({ status: 'CANCELLED' }))
    vi.mocked(reservationDetail).mockResolvedValue(reservation({ status: 'CANCELLED' }))
    const page = await loadDetailPage()
    page.setData({ id: 7 })

    const first = page.cancel()
    const second = page.cancel()
    expect(showModal).toHaveBeenCalledOnce()
    expect(page.data.actionLoading).toBe(true)

    confirmation.resolve({ confirm: true })
    await Promise.all([first, second])
    expect(cancelReservationAction).toHaveBeenCalledOnce()
    expect(reservationDetail).toHaveBeenCalledOnce()
    expect(page.data.actionLoading).toBe(false)
  })

  it('locks checkout before confirmation and runs only one mutation for concurrent entries', async () => {
    const confirmation = deferred<{ confirm: boolean }>()
    const showModal = vi.fn().mockReturnValue(confirmation.promise)
    vi.stubGlobal('wx', { showModal })
    vi.mocked(checkoutFromWeChat).mockResolvedValue(reservation({ status: 'COMPLETED' }))
    vi.mocked(reservationDetail).mockResolvedValue(reservation({ status: 'COMPLETED' }))
    const page = await loadDetailPage()
    page.setData({ id: 7 })

    const first = page.checkout()
    const second = page.checkout()
    expect(showModal).toHaveBeenCalledOnce()
    expect(page.data.actionLoading).toBe(true)

    confirmation.resolve({ confirm: true })
    await Promise.all([first, second])
    expect(checkoutFromWeChat).toHaveBeenCalledOnce()
    expect(reservationDetail).toHaveBeenCalledOnce()
    expect(page.data.actionLoading).toBe(false)
  })
})

describe('reservation page templates', () => {
  it('registers pages and provides visible states, accessible controls and 96rpx targets', () => {
    const { readFileSync } = require('node:fs')
    const listWxml = readFileSync('miniprogram/pages/reservations/index.wxml', 'utf8')
    const listWxss = readFileSync('miniprogram/pages/reservations/index.wxss', 'utf8')
    const detailWxml = readFileSync('miniprogram/pages/reservation-detail/index.wxml', 'utf8')
    const detailWxss = readFileSync('miniprogram/pages/reservation-detail/index.wxss', 'utf8')
    const app = JSON.parse(readFileSync('miniprogram/app.json', 'utf8')) as { pages: string[] }

    expect(app.pages).toEqual(expect.arrayContaining(['pages/reservations/index', 'pages/reservation-detail/index']))
    for (const text of ['正在加载预约记录', '没有符合条件的预约', '重试']) expect(listWxml).toContain(text)
    expect(listWxml).toContain('status--{{item.status}}')
    expect(listWxml).toContain('aria-live="assertive"')
    expect(listWxss).toContain('min-height: 96rpx')
    for (const text of ['正式时段', '正式座位', '用途', '签到', '签退', '扫码签到', '提前签退', '取消预约']) expect(detailWxml).toContain(text)
    expect(detailWxml).toContain('wx:if="{{canScan}}"')
    expect(detailWxml).toContain('wx:if="{{canCheckout}}"')
    expect(detailWxml).toContain('wx:if="{{canCancel}}"')
    expect(detailWxml).toContain('status--{{detail.status}}')
    expect(detailWxss).toContain('min-height: 96rpx')
    expect(listWxml).not.toMatch(/\{\{\s*(reservationTime|formatDateTime)\s*\(/)
    expect(detailWxml).not.toMatch(/\{\{\s*(reservationTime|formatDateTime)\s*\(/)
  })
})
