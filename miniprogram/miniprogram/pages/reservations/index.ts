import { myReservations } from '../../api/reservations'
import { errorMessage } from '../../api/http'
import { restoreSession } from '../../auth/session'
import type { Reservation, ReservationStatus } from '../../api/types'
import { reservationTime, statusLabels } from '../../utils/format'

Page({
  data: {
    items: [] as Array<Reservation & { timeText: string; statusText: string; canCancel: boolean }>,
    labels: statusLabels,
    status: '' as ReservationStatus | '',
    filters: [
      { value: '', label: '全部' },
      { value: 'APPROVED', label: '待使用' },
      { value: 'IN_USE', label: '使用中' },
      { value: 'COMPLETED', label: '已完成' },
    ] as Array<{ value: ReservationStatus | ''; label: string }>,
    page: 0,
    total: 0,
    loading: false,
    error: '',
    generation: 0,
  },
  async onShow() {
    if (await restoreSession()) await this.reload()
  },
  async reload() {
    const generation = this.data.generation + 1
    this.setData({ generation, page: 0, total: 0, items: [], loading: false, error: '' })
    await this.loadMore()
  },
  async loadMore() {
    if (this.data.loading || (this.data.page > 0 && this.data.items.length >= this.data.total)) return
    const generation = this.data.generation
    const status = this.data.status || undefined
    const page = this.data.page
    this.setData({ loading: true, error: '' })
    try {
      const result = await myReservations(status, page)
      if (generation !== this.data.generation) return
      const items = result.items.map(item => ({
        ...item,
        timeText: reservationTime(item),
        statusText: statusLabels[item.status],
        canCancel: ['PENDING_SCHEDULE', 'APPROVED'].includes(item.status),
      }))
      this.setData({
        items: this.data.items.concat(items),
        page: page + 1,
        total: result.total,
      })
    } catch (error) {
      if (generation === this.data.generation) this.setData({ error: errorMessage(error) })
    } finally {
      if (generation === this.data.generation) this.setData({ loading: false })
    }
  },
  onReachBottom() {
    return this.loadMore()
  },
  onFilter(event: WechatMiniprogram.TouchEvent) {
    const status = event.currentTarget.dataset.value as ReservationStatus | ''
    this.setData({ status })
    return this.reload()
  },
  open(event: WechatMiniprogram.TouchEvent) {
    wx.navigateTo({ url: `/pages/reservation-detail/index?id=${event.currentTarget.dataset.id}` })
  },
})
