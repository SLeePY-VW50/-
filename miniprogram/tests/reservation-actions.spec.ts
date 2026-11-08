import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../miniprogram/api/reservations', () => ({
  cancelReservation: vi.fn(),
  qrCheckin: vi.fn(),
  wechatCheckout: vi.fn(),
}))

import { cancelReservation, qrCheckin, wechatCheckout } from '../miniprogram/api/reservations'
import { cancelReservationAction, checkoutFromWeChat, scanAndCheckin } from '../miniprogram/actions/reservations'

describe('cancel reservation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('wx', { showToast: vi.fn() })
  })

  afterEach(() => vi.unstubAllGlobals())

  it('cancels the selected reservation and returns the latest reservation', async () => {
    const reservation = { resvId: 12, status: 'CANCELLED' }
    vi.mocked(cancelReservation).mockResolvedValue(reservation as never)

    await expect(cancelReservationAction(12, '计划变更')).resolves.toBe(reservation)

    expect(cancelReservation).toHaveBeenCalledWith(12, '计划变更')
    expect(wx.showToast).toHaveBeenCalledWith({ title: '已取消', icon: 'success' })
  })

  it('surfaces cancellation rejection without showing a success toast', async () => {
    vi.mocked(cancelReservation).mockRejectedValue(new Error('当前状态不可取消'))

    await expect(cancelReservationAction(12, '计划变更')).rejects.toThrow('当前状态不可取消')

    expect(wx.showToast).not.toHaveBeenCalled()
  })
})

describe('WeChat checkout', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('wx', { showToast: vi.fn() })
  })

  afterEach(() => vi.unstubAllGlobals())

  it('checks out through WECHAT_QR and returns the latest reservation', async () => {
    const reservation = { resvId: 12, status: 'COMPLETED', checkoutChannel: 'WECHAT_QR' }
    vi.mocked(wechatCheckout).mockResolvedValue(reservation as never)

    await expect(checkoutFromWeChat(12)).resolves.toBe(reservation)

    expect(wechatCheckout).toHaveBeenCalledWith(12)
    expect(wx.showToast).toHaveBeenCalledWith({ title: '已签退', icon: 'success' })
  })

  it('surfaces checkout rejection without showing a success toast', async () => {
    vi.mocked(wechatCheckout).mockRejectedValue(new Error('当前状态不可签退'))

    await expect(checkoutFromWeChat(12)).rejects.toThrow('当前状态不可签退')

    expect(wx.showToast).not.toHaveBeenCalled()
  })
})

describe('scan and check-in', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('wx', { scanCode: vi.fn(), showToast: vi.fn() })
  })

  afterEach(() => vi.unstubAllGlobals())

  it('posts the raw QR result for the selected reservation using camera-only QR scanning', async () => {
    vi.mocked(wx.scanCode).mockImplementation(options => options.success?.({ result: 'signed-code' } as never))
    vi.mocked(qrCheckin).mockResolvedValue({ alreadyCheckedIn: false } as never)

    await expect(scanAndCheckin(12)).resolves.toBe(true)

    expect(wx.scanCode).toHaveBeenCalledWith(expect.objectContaining({ onlyFromCamera: true, scanType: ['qrCode'] }))
    expect(qrCheckin).toHaveBeenCalledWith(12, 'signed-code')
    expect(wx.showToast).toHaveBeenCalledWith({ title: '签到成功', icon: 'success' })
  })

  it('shows the accurate toast when the reservation was already checked in', async () => {
    vi.mocked(wx.scanCode).mockImplementation(options => options.success?.({ result: 'signed-code' } as never))
    vi.mocked(qrCheckin).mockResolvedValue({ alreadyCheckedIn: true } as never)

    await expect(scanAndCheckin(12)).resolves.toBe(true)

    expect(wx.showToast).toHaveBeenCalledWith({ title: '已完成签到', icon: 'success' })
  })

  it('does not call API when the user cancels scanning', async () => {
    vi.mocked(wx.scanCode).mockImplementation(options => options.fail?.({ errMsg: 'scanCode:fail cancel' } as never))

    await expect(scanAndCheckin(12)).resolves.toBe(false)

    expect(qrCheckin).not.toHaveBeenCalled()
    expect(wx.showToast).not.toHaveBeenCalled()
  })

  it('reports a clear error for non-cancellation scanning failures', async () => {
    vi.mocked(wx.scanCode).mockImplementation(options => options.fail?.({ errMsg: 'scanCode:fail camera unavailable' } as never))

    await expect(scanAndCheckin(12)).rejects.toThrow('扫码失败，请重试')

    expect(qrCheckin).not.toHaveBeenCalled()
  })

  it('surfaces server rejection without showing a success toast', async () => {
    vi.mocked(wx.scanCode).mockImplementation(options => options.success?.({ result: 'forged' } as never))
    vi.mocked(qrCheckin).mockRejectedValue(new Error('二维码无效'))

    await expect(scanAndCheckin(12)).rejects.toThrow('二维码无效')

    expect(wx.showToast).not.toHaveBeenCalled()
  })
})
