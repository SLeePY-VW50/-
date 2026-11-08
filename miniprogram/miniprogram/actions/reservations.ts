import { cancelReservation, qrCheckin, wechatCheckout } from '../api/reservations'

export async function checkoutFromWeChat(reservationId: number) {
  const result = await wechatCheckout(reservationId)
  wx.showToast({ title: '已签退', icon: 'success' })
  return result
}

export async function cancelReservationAction(reservationId: number, reason: string | null = null) {
  const result = await cancelReservation(reservationId, reason)
  wx.showToast({ title: '已取消', icon: 'success' })
  return result
}

export function scanAndCheckin(reservationId: number): Promise<boolean> {
  return new Promise((resolve, reject) => {
    wx.scanCode({
      onlyFromCamera: true,
      scanType: ['qrCode'],
      success: async ({ result }) => {
        try {
          const response = await qrCheckin(reservationId, result)
          wx.showToast({ title: response.alreadyCheckedIn ? '已完成签到' : '签到成功', icon: 'success' })
          resolve(true)
        } catch (error) {
          reject(error)
        }
      },
      fail: ({ errMsg }) => errMsg.includes('cancel') ? resolve(false) : reject(new Error('扫码失败，请重试')),
    })
  })
}
