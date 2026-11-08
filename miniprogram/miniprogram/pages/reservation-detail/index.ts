import { reservationDetail } from '../../api/reservations'
import { errorMessage } from '../../api/http'
import { restoreSession } from '../../auth/session'
import { cancelReservationAction, checkoutFromWeChat, scanAndCheckin } from '../../actions/reservations'
import type { Reservation } from '../../api/types'
import { formatDateTime, reservationTime, statusLabels } from '../../utils/format'

Page({
  data: {
    id: 0,
    detail: null as Reservation | null,
    labels: statusLabels,
    timeText: '',
    statusText: '',
    loading: false,
    actionLoading: false,
    error: '',
    seatText: '',
    checkinText: '—',
    checkoutText: '—',
    canCancel: false,
    canScan: false,
    canCheckout: false,
  },
  async onLoad(query: Record<string, string>) {
    this.setData({ id: Number(query.id) })
    if (await restoreSession()) await this.load()
  },
  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const detail = await reservationDetail(this.data.id)
      this.setData({
        detail,
        timeText: reservationTime(detail),
        statusText: statusLabels[detail.status],
        seatText: detail.seats.map(seat => seat.seatCode).join('、'),
        checkinText: formatDateTime(detail.checkinTime),
        checkoutText: formatDateTime(detail.checkoutTime),
        canCancel: ['PENDING_SCHEDULE', 'APPROVED'].includes(detail.status),
        canScan: detail.status === 'APPROVED',
        canCheckout: detail.status === 'IN_USE',
      })
    } catch (error) {
      this.setData({ error: errorMessage(error) })
    } finally {
      this.setData({ loading: false })
    }
  },
  async scan() {
    if (this.data.actionLoading) return
    this.setData({ actionLoading: true, error: '' })
    try {
      if (await scanAndCheckin(this.data.id)) await this.load()
    } catch (error) {
      this.setData({ error: errorMessage(error) })
    } finally {
      this.setData({ actionLoading: false })
    }
  },
  async cancel() {
    if (this.data.actionLoading) return
    this.setData({ actionLoading: true, error: '' })
    try {
      const confirmed = await wx.showModal({
        title: '取消预约',
        content: '取消后时段将被释放，临近开始取消可能产生违规记录。',
        confirmText: '确认取消',
      })
      if (!confirmed.confirm) return
      await cancelReservationAction(this.data.id)
      await this.load()
    } catch (error) {
      this.setData({ error: errorMessage(error) })
    } finally {
      this.setData({ actionLoading: false })
    }
  },
  async checkout() {
    if (this.data.actionLoading) return
    this.setData({ actionLoading: true, error: '' })
    try {
      const confirmed = await wx.showModal({
        title: '提前签退',
        content: '仅释放当前之后尚未开始的完整时间片。',
        confirmText: '确认签退',
      })
      if (!confirmed.confirm) return
      await checkoutFromWeChat(this.data.id)
      await this.load()
    } catch (error) {
      this.setData({ error: errorMessage(error) })
    } finally {
      this.setData({ actionLoading: false })
    }
  },
})
