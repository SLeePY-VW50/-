import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextReservation } from '../miniprogram/utils/reservations'
import type { PageView, Reservation, User } from '../miniprogram/api/types'

vi.mock('../miniprogram/api/reservations', () => ({ myReservations: vi.fn() }))
vi.mock('../miniprogram/actions/reservations', () => ({ scanAndCheckin: vi.fn() }))
vi.mock('../miniprogram/auth/session', () => ({ restoreSession: vi.fn() }))
vi.mock('../miniprogram/auth/storage', () => ({ readSession: vi.fn(), clearSession: vi.fn() }))
vi.mock('../miniprogram/config/index', () => ({ isDevelopmentLoginVisible: vi.fn() }))

import { myReservations } from '../miniprogram/api/reservations'
import { scanAndCheckin } from '../miniprogram/actions/reservations'
import { restoreSession } from '../miniprogram/auth/session'
import { readSession } from '../miniprogram/auth/storage'

const reservation = (resvId: number, status: Reservation['status'], startTime: string | null) => ({
  resvId,
  status,
  startTime,
  endTime: startTime ? startTime.replace('T', 'T').slice(0, 11) + '11:00:00' : null,
  labName: '创新实验室',
  date: startTime?.slice(0, 10) ?? '2026-11-08',
  windowStart: '08:00:00',
  windowEnd: '12:00:00',
  durationMinutes: 120,
  seats: [{ seatCode: 'A-01' }],
}) as Reservation

const user: User = { userId: 1, account: '20240001', name: '演示学生', roles: ['STUDENT'], status: 'ACTIVE' }
const page = <T,>(items: T[]): PageView<T> => ({ items, page: 0, size: 100, total: items.length })

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

interface HomeDefinition {
  data: {
    userName: string
    next: Reservation | null
    nextTime: string
    nextSeats: string
    nextStatus: string
    recent: Array<Reservation & { timeText: string; statusText: string }>
    loading: boolean
    actionLoading: boolean
    error: string
    generation: number
  }
  onShow(): Promise<void>
  load(): Promise<void>
  scanNext(): Promise<void>
}

function instantiate<T extends { data: object }>(definition: T) {
  const instance = {
    ...definition,
    data: { ...definition.data },
    setData(patch: Partial<T['data']>) { Object.assign(instance.data, patch) },
  }
  return instance
}

async function loadHomePage() {
  vi.resetModules()
  let definition: HomeDefinition | undefined
  vi.stubGlobal('Page', (pageDefinition: HomeDefinition) => { definition = pageDefinition })
  await import('../miniprogram/pages/home/index')
  return instantiate(definition!)
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(restoreSession).mockResolvedValue(user)
  vi.mocked(readSession).mockReturnValue({ token: 'not-rendered', user })
})

afterEach(() => vi.unstubAllGlobals())

describe('next reservation', () => {
  it('prioritizes in-use then nearest approved and ignores expired completed records', () => {
    expect(nextReservation([reservation(1, 'APPROVED', '2026-11-08T14:00:00'), reservation(2, 'IN_USE', '2026-11-07T08:00:00')], '2026-11-07T09:00:00')?.resvId).toBe(2)
    expect(nextReservation([reservation(1, 'APPROVED', '2026-11-08T14:00:00'), reservation(2, 'APPROVED', '2026-11-07T10:00:00')], '2026-11-07T09:00:00')?.resvId).toBe(2)
    expect(nextReservation([reservation(3, 'COMPLETED', '2026-11-07T10:00:00')], '2026-11-07T09:00:00')).toBeNull()
  })
})

describe('home page', () => {
  it('loads real reservation statuses and formats the next card and pending demand', async () => {
    vi.mocked(myReservations).mockImplementation(async status => {
      if (status === 'IN_USE') return page([reservation(2, 'IN_USE', '2026-11-07T08:00:00')])
      if (status === 'PENDING_SCHEDULE') return page([reservation(3, 'PENDING_SCHEDULE', null)])
      return page([reservation(1, 'APPROVED', '2026-11-08T14:00:00')])
    })
    const home = await loadHomePage()

    await home.onShow()

    expect(myReservations).toHaveBeenCalledWith('APPROVED', 0, 100)
    expect(myReservations).toHaveBeenCalledWith('IN_USE', 0, 20)
    expect(myReservations).toHaveBeenCalledWith('PENDING_SCHEDULE', 0, 5)
    expect(home.data).toMatchObject({ userName: '演示学生', nextTime: '2026-11-07 08:00–11:00', nextSeats: 'A-01', nextStatus: '使用中', loading: false, error: '' })
    expect(home.data.next?.resvId).toBe(2)
    expect(home.data.recent[0]).toMatchObject({ resvId: 3, statusText: '待排期' })
  })

  it('keeps the newest load when an older request finishes later', async () => {
    const oldApproved = deferred<PageView<Reservation>>()
    const newApproved = deferred<PageView<Reservation>>()
    vi.mocked(myReservations).mockImplementation(status => {
      if (status === 'APPROVED') return vi.mocked(myReservations).mock.calls.length === 1 ? oldApproved.promise : newApproved.promise
      return Promise.resolve(page([]))
    })
    const home = await loadHomePage()
    const initial = home.load()
    const latest = home.load()

    newApproved.resolve(page([reservation(9, 'APPROVED', '2099-11-08T14:00:00')]))
    await latest
    oldApproved.resolve(page([reservation(1, 'APPROVED', '2099-11-07T14:00:00')]))
    await initial

    expect(home.data.next?.resvId).toBe(9)
    expect(home.data).toMatchObject({ loading: false, error: '' })
  })

  it('shows a retryable error instead of an empty state when loading offline', async () => {
    vi.mocked(myReservations).mockRejectedValue(new Error('网络连接失败'))
    const home = await loadHomePage()

    await home.onShow()

    expect(home.data).toMatchObject({ error: '网络连接失败', loading: false, next: null })
    const { readFileSync } = require('node:fs') as { readFileSync(path: string, encoding: string): string }
    const wxml = readFileSync('miniprogram/pages/home/index.wxml', 'utf8')
    expect(wxml).toContain('bindtap="load">重试</button>')
    expect(wxml).toContain('wx:if="{{!next && !loading && !error}}"')
  })

  it('locks scan synchronously and reloads only after successful check-in', async () => {
    const scanning = deferred<boolean>()
    vi.mocked(scanAndCheckin).mockReturnValue(scanning.promise)
    vi.mocked(myReservations).mockResolvedValue(page([reservation(2, 'IN_USE', '2026-11-07T08:00:00')]))
    const home = await loadHomePage()
    home.setData({ next: reservation(2, 'APPROVED', '2026-11-07T08:00:00') })

    const first = home.scanNext()
    const second = home.scanNext()
    expect(scanAndCheckin).toHaveBeenCalledOnce()
    expect(home.data.actionLoading).toBe(true)

    scanning.resolve(true)
    await Promise.all([first, second])
    expect(scanAndCheckin).toHaveBeenCalledWith(2)
    expect(myReservations).toHaveBeenCalledTimes(3)
    expect(home.data.actionLoading).toBe(false)
  })
})

interface ProfileDefinition {
  data: { user: User | null; roleText: string; development: boolean; generation: number }
  onShow(): Promise<void>
  reservations(): void
  logout(): Promise<void>
}

async function loadProfilePage() {
  vi.resetModules()
  let definition: ProfileDefinition | undefined
  vi.stubGlobal('Page', (pageDefinition: ProfileDefinition) => { definition = pageDefinition })
  await import('../miniprogram/pages/profile/index')
  return instantiate(definition!)
}

describe('profile page', () => {
  it('shows the restored public user, account, roles and development environment', async () => {
    const { isDevelopmentLoginVisible } = await import('../miniprogram/config/index')
    vi.mocked(isDevelopmentLoginVisible).mockReturnValue(true)
    vi.mocked(readSession).mockReturnValue({ token: 'not-rendered', user: { ...user, roles: ['STUDENT', 'TEACHER'] } })
    const profile = await loadProfilePage()

    await profile.onShow()

    expect(profile.data).toMatchObject({ roleText: '学生 / 教师', development: true })
    expect(profile.data.user).toMatchObject({ name: '演示学生', account: '20240001', roles: ['STUDENT', 'TEACHER'] })
  })

  it('does not let an older session restoration replace newer identity state', async () => {
    const oldRestore = deferred<User>()
    const newRestore = deferred<User>()
    vi.mocked(restoreSession).mockReturnValueOnce(oldRestore.promise).mockReturnValueOnce(newRestore.promise)
    vi.mocked(readSession)
      .mockReturnValueOnce({ token: 'new', user: { ...user, name: '新用户' } })
      .mockReturnValueOnce({ token: 'old', user: { ...user, name: '旧用户' } })
    const profile = await loadProfilePage()
    const initial = profile.onShow()
    const latest = profile.onShow()

    newRestore.resolve(user)
    await latest
    oldRestore.resolve(user)
    await initial

    expect(profile.data.user?.name).toBe('新用户')
  })

  it('clears the session and relaunches login only after logout confirmation', async () => {
    const { clearSession } = await import('../miniprogram/auth/storage')
    const showModal = vi.fn().mockResolvedValue({ confirm: true })
    const reLaunch = vi.fn()
    const switchTab = vi.fn()
    vi.stubGlobal('wx', { showModal, reLaunch, switchTab })
    const profile = await loadProfilePage()

    profile.reservations()
    await profile.logout()

    expect(switchTab).toHaveBeenCalledWith({ url: '/pages/reservations/index' })
    expect(showModal).toHaveBeenCalledWith(expect.objectContaining({ title: '退出登录', confirmText: '确认退出' }))
    expect(clearSession).toHaveBeenCalledOnce()
    expect(reLaunch).toHaveBeenCalledWith({ url: '/pages/login/index' })
  })

  it('keeps the session when logout is cancelled', async () => {
    const { clearSession } = await import('../miniprogram/auth/storage')
    const reLaunch = vi.fn()
    vi.stubGlobal('wx', { showModal: vi.fn().mockResolvedValue({ confirm: false }), reLaunch })
    const profile = await loadProfilePage()

    await profile.logout()

    expect(clearSession).not.toHaveBeenCalled()
    expect(reLaunch).not.toHaveBeenCalled()
  })

  it('provides visible controls and 96rpx targets without exposing credentials', () => {
    const { readFileSync } = require('node:fs') as { readFileSync(path: string, encoding: string): string }
    const wxml = readFileSync('miniprogram/pages/profile/index.wxml', 'utf8')
    const wxss = readFileSync('miniprogram/pages/profile/index.wxss', 'utf8')

    for (const text of ['我的预约', '退出登录', '当前为开发登录环境']) expect(wxml).toContain(text)
    expect(wxml).not.toMatch(/password|openid|token/i)
    expect(wxss).toContain('min-height: 96rpx')
  })
})

describe('Task 13 review regressions', () => {
  it('keeps an approved reservation before and during its active period', () => {
    const upcoming = reservation(1, 'APPROVED', '2026-11-07T08:00:00')
    expect(nextReservation([upcoming], '2026-11-07T07:59:30')?.resvId).toBe(1)
    expect(nextReservation([upcoming], '2026-11-07T09:00:00')?.resvId).toBe(1)
    expect(nextReservation([{ ...upcoming, endTime: null }], '2026-11-07T08:00:30')?.resvId).toBe(1)
  })

  it('ignores ended or unscheduled approved records and sorts same-day reservations', () => {
    expect(nextReservation([reservation(1, 'APPROVED', '2026-11-07T08:00:00')], '2026-11-07T11:01:00')).toBeNull()
    expect(nextReservation([reservation(2, 'APPROVED', null)], '2026-11-07T09:00:00')).toBeNull()
    expect(nextReservation([
      reservation(4, 'APPROVED', '2026-11-07T14:00:00'),
      reservation(3, 'APPROVED', '2026-11-07T10:00:00'),
    ], '2026-11-07T09:00:00')?.resvId).toBe(3)
  })

  it('keeps home loading from initialization through session restoration', async () => {
    const restoring = deferred<User>()
    vi.mocked(restoreSession).mockReturnValue(restoring.promise)
    vi.mocked(myReservations).mockResolvedValue(page([]))
    const home = await loadHomePage()
    expect(home.data.loading).toBe(true)

    const showing = home.onShow()
    expect(home.data.loading).toBe(true)
    restoring.resolve(user)
    await showing

    expect(home.data.loading).toBe(false)
  })

  it('lets only the latest home session restoration update identity and load', async () => {
    const oldRestore = deferred<User>()
    const newRestore = deferred<User>()
    vi.mocked(restoreSession).mockReturnValueOnce(oldRestore.promise).mockReturnValueOnce(newRestore.promise)
    vi.mocked(readSession)
      .mockReturnValueOnce({ token: 'new', user: { ...user, name: '新用户' } })
      .mockReturnValueOnce({ token: 'old', user: { ...user, name: '旧用户' } })
    vi.mocked(myReservations).mockResolvedValue(page([]))
    const home = await loadHomePage()
    const initial = home.onShow()
    const latest = home.onShow()

    newRestore.resolve(user)
    await latest
    oldRestore.resolve(user)
    await initial

    expect(home.data.userName).toBe('新用户')
    expect(myReservations).toHaveBeenCalledTimes(3)
  })

  it('ignores an old scan failure after a newer home load succeeds', async () => {
    const scanning = deferred<boolean>()
    vi.mocked(scanAndCheckin).mockReturnValue(scanning.promise)
    vi.mocked(myReservations).mockResolvedValue(page([reservation(9, 'IN_USE', '2026-11-07T08:00:00')]))
    const home = await loadHomePage()
    home.setData({ next: reservation(2, 'APPROVED', '2026-11-07T08:00:00') })
    const oldScan = home.scanNext()

    await home.load()
    scanning.reject(new Error('旧扫码失败'))
    await oldScan

    expect(home.data.next?.resvId).toBe(9)
    expect(home.data).toMatchObject({ loading: false, error: '' })
  })

  it('ignores an old scan failure after a newer home load fails', async () => {
    const scanning = deferred<boolean>()
    vi.mocked(scanAndCheckin).mockReturnValue(scanning.promise)
    vi.mocked(myReservations).mockRejectedValue(new Error('新加载失败'))
    const home = await loadHomePage()
    home.setData({ next: reservation(2, 'APPROVED', '2026-11-07T08:00:00') })
    const oldScan = home.scanNext()

    await home.load()
    expect(home.data.error).toBe('新加载失败')
    scanning.reject(new Error('旧扫码失败'))
    await oldScan

    expect(home.data).toMatchObject({ loading: false, error: '新加载失败' })
  })

  it('reloads a successful scan even when a newer load completed while scanning', async () => {
    const scanning = deferred<boolean>()
    vi.mocked(scanAndCheckin).mockReturnValue(scanning.promise)
    vi.mocked(myReservations).mockResolvedValue(page([]))
    const home = await loadHomePage()
    home.setData({ next: reservation(2, 'APPROVED', '2026-11-07T08:00:00') })
    const pendingScan = home.scanNext()

    await home.load()
    scanning.resolve(true)
    await pendingScan

    expect(vi.mocked(myReservations).mock.calls.map(call => call[0])).toEqual([
      'APPROVED', 'IN_USE', 'PENDING_SCHEDULE',
      'APPROVED', 'IN_USE', 'PENDING_SCHEDULE',
    ])
  })

  it('invalidates an in-flight profile restoration after confirmed logout', async () => {
    const restoring = deferred<User>()
    vi.mocked(restoreSession).mockReturnValue(restoring.promise)
    vi.stubGlobal('wx', { showModal: vi.fn().mockResolvedValue({ confirm: true }), reLaunch: vi.fn() })
    const profile = await loadProfilePage()
    const showing = profile.onShow()

    await profile.logout()
    restoring.resolve(user)
    await showing

    expect(profile.data.user).toBeNull()
    expect(readSession).not.toHaveBeenCalled()
  })
})
