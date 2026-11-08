import { describe, expect, it, vi } from 'vitest'
import { cancelReservation, createReservation, myReservations, qrCheckin, reservationDetail, wechatCheckout } from '../miniprogram/api/reservations'
import { buildReservationPayload, type ReservationFormState } from '../miniprogram/utils/reservation-form'

declare const require: (id: string) => { readFileSync(path: string, encoding: string): string }

const state: ReservationFormState = {
  allocationMode: 'FIXED',
  labId: 1,
  date: '2026-11-08',
  startTime: '14:00',
  endTime: '16:00',
  windowStart: '08:00',
  windowEnd: '12:00',
  durationMinutes: 60,
  reservationMode: 'SEAT',
  attendeeCount: 2,
  purpose: ' 项目实验 ',
  zoneCode: 'NORTH',
  nearWindow: '',
  minSpecLevel: '',
}

describe('reservation payload', () => {
  it('builds fixed and schedulable requests with matching seat count', () => {
    expect(buildReservationPayload(state)).toEqual({
      allocationMode: 'FIXED',
      labId: 1,
      date: '2026-11-08',
      startTime: '14:00',
      endTime: '16:00',
      windowStart: null,
      windowEnd: null,
      durationMinutes: null,
      reservationMode: 'SEAT',
      attendeeCount: 2,
      seatCount: 2,
      purpose: '项目实验',
      preferences: { zoneCode: 'NORTH', nearWindow: null, minSpecLevel: null, allowedLabIds: [] },
    })
    expect(buildReservationPayload({
      ...state,
      allocationMode: 'SCHEDULABLE',
      reservationMode: 'ROOM',
      nearWindow: 'true',
      minSpecLevel: 'GPU',
    })).toMatchObject({
      startTime: null,
      endTime: null,
      windowStart: '08:00',
      windowEnd: '12:00',
      durationMinutes: 60,
      seatCount: null,
      preferences: { nearWindow: true, minSpecLevel: 'GPU' },
    })
  })

  it('rejects missing required fields, excessive duration and invalid windows', () => {
    expect(() => buildReservationPayload({ ...state, purpose: ' ' })).toThrow('请完整填写实验室、日期、人数和用途')
    expect(() => buildReservationPayload({ ...state, attendeeCount: 0 })).toThrow('请完整填写实验室、日期、人数和用途')
    expect(() => buildReservationPayload({ ...state, startTime: '08:00', endTime: '13:00' })).toThrow('固定预约一次最多选择8个时间片')
    expect(() => buildReservationPayload({ ...state, startTime: '16:00', endTime: '14:00' })).toThrow('固定预约一次最多选择8个时间片')
    expect(() => buildReservationPayload({ ...state, allocationMode: 'SCHEDULABLE', durationMinutes: 45 })).toThrow('可调度时长必须为30到240分钟之间的30分钟倍数')
    expect(() => buildReservationPayload({ ...state, allocationMode: 'SCHEDULABLE', windowEnd: '08:30' })).toThrow('预约时长不能超过可调度请求窗口')
    expect(() => buildReservationPayload({ ...state, allocationMode: 'SCHEDULABLE', durationMinutes: 240, windowEnd: '11:30' })).toThrow('预约时长不能超过可调度请求窗口')
  })

  it('accepts exact 240-minute ranges ending at 24:00', () => {
    expect(buildReservationPayload({ ...state, startTime: '20:00', endTime: '24:00' })).toMatchObject({ startTime: '20:00', endTime: '24:00' })
    expect(buildReservationPayload({
      ...state,
      allocationMode: 'SCHEDULABLE',
      windowStart: '20:00',
      windowEnd: '24:00',
      durationMinutes: 240,
    })).toMatchObject({ windowStart: '20:00', windowEnd: '24:00', durationMinutes: 240 })
  })

  it('accepts exactly 500 purpose characters and rejects longer text', () => {
    const purpose = '用'.repeat(500)
    expect(buildReservationPayload({ ...state, purpose })).toMatchObject({ purpose })
    expect(() => buildReservationPayload({ ...state, purpose: '用'.repeat(501) })).toThrow('用途不能超过500字')
  })
})

describe('reservation API', () => {
  it('uses the existing request layer and reservation endpoints', async () => {
    const request = vi.fn((options: WechatMiniprogram.RequestOption) => options.success?.({
      statusCode: 200,
      data: { code: 0, message: 'success', data: {} },
    } as never))
    vi.stubGlobal('wx', { request, getStorageSync: () => '' })
    const payload = buildReservationPayload(state)

    await createReservation(payload)
    await myReservations('APPROVED', 2)
    await reservationDetail(7)
    await cancelReservation(7, '计划变更')
    await qrCheckin(7, 'qr-content')
    await wechatCheckout(7)

    expect(request).toHaveBeenNthCalledWith(1, expect.objectContaining({ url: 'http://127.0.0.1:8080/api/reservations', method: 'POST', data: payload }))
    expect(request).toHaveBeenNthCalledWith(2, expect.objectContaining({ url: 'http://127.0.0.1:8080/api/reservations/my?status=APPROVED&page=2&size=10', method: 'GET' }))
    expect(request).toHaveBeenNthCalledWith(3, expect.objectContaining({ url: 'http://127.0.0.1:8080/api/reservations/7', method: 'GET' }))
    expect(request).toHaveBeenNthCalledWith(4, expect.objectContaining({ url: 'http://127.0.0.1:8080/api/reservations/7/cancel', method: 'POST', data: { reason: '计划变更' } }))
    expect(request).toHaveBeenNthCalledWith(5, expect.objectContaining({ url: 'http://127.0.0.1:8080/api/checkin/qr', method: 'POST', data: { reservationId: 7, content: 'qr-content' } }))
    expect(request).toHaveBeenNthCalledWith(6, expect.objectContaining({ url: 'http://127.0.0.1:8080/api/checkin/7/checkout', method: 'POST', data: { channel: 'WECHAT_QR' } }))
  })
})

interface ReservationPageDefinition {
  data: ReservationFormState & {
    specOptions: string[]
    specIndex: number
    windowOptions: string[]
    windowIndex: number
    durationOptions: number[]
    durationIndex: number
    submitting: boolean
    error: string
    success: string
  }
  onLoad(query: Record<string, string>): Promise<void>
  onAllocation(event: WechatMiniprogram.TouchEvent): void
  onMode(event: WechatMiniprogram.TouchEvent): void
  onText(event: WechatMiniprogram.Input): void
  onCount(event: WechatMiniprogram.Input): void
  onSpec(event: WechatMiniprogram.PickerChange): void
  onWindow(event: WechatMiniprogram.PickerChange): void
  onDuration(event: WechatMiniprogram.PickerChange): void
  submit(): Promise<void>
}

function instantiateReservationPage(definition: ReservationPageDefinition) {
  const page = {
    ...definition,
    data: { ...definition.data },
    setData(patch: Partial<ReservationPageDefinition['data']>) { Object.assign(page.data, patch) },
  }
  return page
}

async function loadReservationPage() {
  vi.resetModules()
  let definition: ReservationPageDefinition | undefined
  vi.stubGlobal('Page', (page: ReservationPageDefinition) => { definition = page })
  await import('../miniprogram/pages/reservation-form/index')
  return instantiateReservationPage(definition!)
}

function fillReservationPage(page: ReturnType<typeof instantiateReservationPage>, overrides: Partial<ReservationFormState> = {}) {
  page.setData({ ...state, ...overrides })
}

describe('reservation form page', () => {
  it('restores the session and applies lab navigation query values', async () => {
    const user = { userId: 1, account: '20240001', name: '演示学生', roles: ['STUDENT'], status: 'ACTIVE' }
    const values = new Map<string, unknown>([['campus.lab.token', 'jwt-1'], ['campus.lab.user', user]])
    vi.stubGlobal('wx', {
      getStorageSync: (key: string) => values.get(key) ?? '',
      setStorageSync: (key: string, value: unknown) => values.set(key, value),
      removeStorageSync: (key: string) => values.delete(key),
      request: (options: WechatMiniprogram.RequestOption) => options.success?.({ statusCode: 200, data: { code: 0, message: 'success', data: user } } as never),
    })
    const page = await loadReservationPage()

    await page.onLoad({ labId: '3', date: '2026-11-09', startTime: '09:00:00', endTime: '11:00:00', mode: 'ROOM' })

    expect(page.data).toMatchObject({ labId: 3, date: '2026-11-09', startTime: '09:00', endTime: '11:00', reservationMode: 'ROOM' })
  })

  it('submits once while a valid request is pending and redirects to its detail', async () => {
    let deferred: WechatMiniprogram.RequestOption | undefined
    const request = vi.fn((options: WechatMiniprogram.RequestOption) => { deferred = options })
    const redirectTo = vi.fn()
    vi.stubGlobal('wx', { request, redirectTo, getStorageSync: () => '' })
    const page = await loadReservationPage()
    fillReservationPage(page)

    const first = page.submit()
    const duplicate = page.submit()
    expect(request).toHaveBeenCalledTimes(1)
    expect(page.data.submitting).toBe(true)
    deferred!.success?.({ statusCode: 200, data: { code: 0, message: 'success', data: { resvId: 42 } } } as never)
    await Promise.all([first, duplicate])

    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      url: 'http://127.0.0.1:8080/api/reservations',
      method: 'POST',
      data: buildReservationPayload(state),
    }))
    expect(redirectTo).toHaveBeenCalledWith({ url: '/pages/reservation-detail/index?id=42' })
    expect(page.data).toMatchObject({ purpose: ' 项目实验 ', submitting: false, success: '预约 #42 已提交', error: '' })
  })

  it('shows invalid input errors without sending or disabling the form', async () => {
    const request = vi.fn()
    vi.stubGlobal('wx', { request, getStorageSync: () => '' })
    const page = await loadReservationPage()
    fillReservationPage(page, { purpose: ' ' })

    await page.submit()

    expect(request).not.toHaveBeenCalled()
    expect(page.data.error).toBe('请完整填写实验室、日期、人数和用途')
    expect(page.data.submitting).toBe(false)
  })

  it.each([
    { name: 'NaN attendee count', attendeeCount: Number.NaN },
    { name: 'fractional attendee count', attendeeCount: 1.5 },
  ])('rejects $name without submitting', async ({ attendeeCount }) => {
    const request = vi.fn((options: WechatMiniprogram.RequestOption) => options.success?.({
      statusCode: 200,
      data: { code: 0, message: 'success', data: { resvId: 99 } },
    } as never))
    vi.stubGlobal('wx', { request, redirectTo: vi.fn(), getStorageSync: () => '' })
    const page = await loadReservationPage()
    fillReservationPage(page, { attendeeCount })

    await page.submit()

    expect(request).not.toHaveBeenCalled()
    expect(page.data.error).toBe('人数必须为有限正整数')
    expect(page.data.submitting).toBe(false)
  })

  it.each([
    { name: 'fixed start', form: { ...state, startTime: '12:15', endTime: '13:00' } },
    { name: 'fixed end', form: { ...state, startTime: '11:00', endTime: '12:15' } },
    { name: 'schedulable window start', form: { ...state, allocationMode: 'SCHEDULABLE' as const, windowStart: '12:15', windowEnd: '14:00' } },
    { name: 'schedulable window end', form: { ...state, allocationMode: 'SCHEDULABLE' as const, windowStart: '08:00', windowEnd: '12:15' } },
  ])('rejects a 12:15 $name without submitting', async ({ form }) => {
    const request = vi.fn((options: WechatMiniprogram.RequestOption) => options.success?.({
      statusCode: 200,
      data: { code: 0, message: 'success', data: { resvId: 99 } },
    } as never))
    vi.stubGlobal('wx', { request, redirectTo: vi.fn(), getStorageSync: () => '' })
    const page = await loadReservationPage()
    fillReservationPage(page, form)

    await page.submit()

    expect(request).not.toHaveBeenCalled()
    expect(page.data.error).toBe('时间必须按30分钟对齐')
    expect(page.data.submitting).toBe(false)
  })

  it.each([
    { name: 'FIXED malformed start', form: { ...state, startTime: 'abc' } },
    { name: 'FIXED out-of-range end', form: { ...state, endTime: '24:30' } },
    { name: 'FIXED missing end', form: { ...state, endTime: '' } },
    { name: 'FIXED midnight start', form: { ...state, startTime: '24:00', endTime: '24:00' } },
    { name: 'SCHEDULABLE malformed window start', form: { ...state, allocationMode: 'SCHEDULABLE' as const, windowStart: 'abc' } },
    { name: 'SCHEDULABLE out-of-range window end', form: { ...state, allocationMode: 'SCHEDULABLE' as const, windowEnd: '24:30' } },
    { name: 'SCHEDULABLE missing window end', form: { ...state, allocationMode: 'SCHEDULABLE' as const, windowEnd: '' } },
    { name: 'SCHEDULABLE midnight window start', form: { ...state, allocationMode: 'SCHEDULABLE' as const, windowStart: '24:00', windowEnd: '24:00' } },
  ])('rejects $name without submitting', async ({ form }) => {
    const request = vi.fn((options: WechatMiniprogram.RequestOption) => options.success?.({
      statusCode: 200,
      data: { code: 0, message: 'success', data: { resvId: 99 } },
    } as never))
    vi.stubGlobal('wx', { request, redirectTo: vi.fn(), getStorageSync: () => '' })
    const page = await loadReservationPage()
    fillReservationPage(page, form)

    await page.submit()

    expect(request).not.toHaveBeenCalled()
    expect(page.data.error).toBe('时间必须为HH:mm格式，24:00仅允许作为结束时间')
    expect(page.data.submitting).toBe(false)
    expect(page.data).toMatchObject(form)
  })

  it('keeps every form value after a 409 conflict', async () => {
    const redirectTo = vi.fn()
    vi.stubGlobal('wx', {
      getStorageSync: () => '',
      redirectTo,
      request: (options: WechatMiniprogram.RequestOption) => options.success?.({ statusCode: 409, data: { code: 409, message: '所选时段已被预约', data: null } } as never),
    })
    const page = await loadReservationPage()
    const retained = { ...state, purpose: '冲突后仍需保留', zoneCode: ' NORTH ', nearWindow: 'false' as const, minSpecLevel: 'HIGH' }
    fillReservationPage(page, retained)

    await page.submit()

    expect(page.data).toMatchObject(retained)
    expect(page.data).toMatchObject({ error: '所选时段已被预约', submitting: false, success: '' })
    expect(redirectTo).not.toHaveBeenCalled()
  })

  it('keeps every form value after a network failure', async () => {
    vi.stubGlobal('wx', {
      getStorageSync: () => '',
      request: (options: WechatMiniprogram.RequestOption) => options.fail?.({ errMsg: 'offline' } as never),
    })
    const page = await loadReservationPage()
    const retained = { ...state, allocationMode: 'SCHEDULABLE' as const, purpose: '网络失败后仍需保留', attendeeCount: 4 }
    fillReservationPage(page, retained)

    await page.submit()

    expect(page.data).toMatchObject(retained)
    expect(page.data).toMatchObject({ error: '网络连接失败，请检查服务是否启动', submitting: false })
  })

  it('updates allocation, mode, preferences, duration and text controls', async () => {
    vi.stubGlobal('wx', {})
    const page = await loadReservationPage()

    page.onAllocation({ currentTarget: { dataset: { value: 'SCHEDULABLE' } } } as unknown as WechatMiniprogram.TouchEvent)
    page.onMode({ currentTarget: { dataset: { value: 'ROOM' } } } as unknown as WechatMiniprogram.TouchEvent)
    page.onText({ currentTarget: { dataset: { field: 'purpose' } }, detail: { value: '新用途' } } as unknown as WechatMiniprogram.Input)
    page.onCount({ detail: { value: '5' } } as WechatMiniprogram.Input)
    page.onSpec({ detail: { value: '3' } } as WechatMiniprogram.PickerChange)
    page.onWindow({ detail: { value: '1' } } as WechatMiniprogram.PickerChange)
    page.onDuration({ detail: { value: '7' } } as WechatMiniprogram.PickerChange)

    expect(page.data).toMatchObject({ allocationMode: 'SCHEDULABLE', reservationMode: 'ROOM', purpose: '新用途', attendeeCount: 5, specIndex: 3, minSpecLevel: 'GPU', windowIndex: 1, nearWindow: 'true', durationIndex: 7, durationMinutes: 240 })
  })

  it('provides associated labels, live errors, large targets and app registration', () => {
    const { readFileSync } = require('node:fs')
    const wxml = readFileSync('miniprogram/pages/reservation-form/index.wxml', 'utf8')
    const wxss = readFileSync('miniprogram/pages/reservation-form/index.wxss', 'utf8')
    const app = JSON.parse(readFileSync('miniprogram/app.json', 'utf8')) as { pages: string[]; tabBar: { list: { pagePath: string }[] } }

    for (const [id, label] of [['window-start', '窗口开始'], ['window-end', '窗口结束'], ['attendee-count', '使用人数'], ['zone-code', '偏好区域'], ['reservation-purpose', '使用用途']]) {
      expect(wxml).toContain(`for="${id}"`)
      expect(wxml).toContain(`id="${id}"`)
      expect(wxml).toContain(label)
    }
    expect(wxml).toContain('maxlength="500"')
    expect(wxml).toContain('aria-role="alert"')
    expect(wxml).toContain('aria-live="assertive"')
    expect(wxml).toContain('disabled="{{submitting}}"')
    expect(wxss).toContain('min-height: 96rpx')
    expect(app.pages).toContain('pages/reservation-form/index')
    expect(app.tabBar.list.map(item => item.pagePath)).not.toContain('pages/reservation-form/index')
  })
})
