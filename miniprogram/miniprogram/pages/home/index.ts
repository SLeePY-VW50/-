import { myReservations } from '../../api/reservations'
import { errorMessage } from '../../api/http'
import { restoreSession } from '../../auth/session'
import { readSession } from '../../auth/storage'
import { scanAndCheckin } from '../../actions/reservations'
import { nextReservation } from '../../utils/reservations'
import { reservationTime, statusLabels, timeOnly, toDateInput } from '../../utils/format'
import type { Reservation } from '../../api/types'

function clockRange(item: Reservation) {
  if (item.startTime && item.endTime) return `${timeOnly(item.startTime)}–${timeOnly(item.endTime, true)}`
  return `${timeOnly(item.windowStart)}–${timeOnly(item.windowEnd, true)}`
}

function placeSummary(item: Reservation) {
  if (item.reservationMode === 'ROOM') return '整室'
  const count = item.seats?.length || item.seatCount || 0
  return count > 0 ? `${count} 个工位` : '等待分配'
}

Page({
  data: {
    userName: '',
    next: null as Reservation | null,
    nextTime: '',
    nextClock: '',
    nextSeats: '',
    nextPlace: '',
    nextStatus: '',
    recent: [] as Array<Reservation & { timeText: string; statusText: string }>,
    loading: true,
    actionLoading: false,
    error: '',
    generation: 0,
  },
  async onShow() {
    const generation = this.data.generation + 1
    this.setData({ generation, loading: true, error: '' })
    const restored = await restoreSession()
    if (generation !== this.data.generation) return
    if (!restored) {
      this.setData({ loading: false })
      return
    }
    this.setData({ userName: readSession()?.user.name ?? '' })
    await this.load()
  },
  async load() {
    const generation = this.data.generation + 1
    this.setData({ generation, loading: true, error: '' })
    try {
      const [approved, inUse, pending] = await Promise.all([
        myReservations('APPROVED', 0, 100),
        myReservations('IN_USE', 0, 20),
        myReservations('PENDING_SCHEDULE', 0, 5),
      ])
      if (generation !== this.data.generation) return
      const date = new Date()
      const now = `${toDateInput(date)}T${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
      const next = nextReservation([...inUse.items, ...approved.items], now)
      this.setData({
        next,
        nextTime: next ? reservationTime(next) : '',
        nextClock: next ? clockRange(next) : '',
        nextSeats: next?.seats.map(seat => seat.seatCode).join('、') ?? '',
        nextPlace: next ? placeSummary(next) : '',
        nextStatus: next ? statusLabels[next.status] : '',
        recent: pending.items.map(item => ({ ...item, timeText: reservationTime(item), statusText: statusLabels[item.status] })),
      })
    } catch (error) {
      if (generation === this.data.generation) this.setData({ error: errorMessage(error) })
    } finally {
      if (generation === this.data.generation) this.setData({ loading: false })
    }
  },
  labs() {
    wx.switchTab({ url: '/pages/labs/index' })
  },
  reservations() {
    wx.navigateTo({ url: '/pages/reservations/index' })
  },
  openNext() {
    if (this.data.next) wx.navigateTo({ url: `/pages/reservation-detail/index?id=${this.data.next.resvId}` })
  },
  async scanNext() {
    const next = this.data.next
    if (!next || this.data.actionLoading) return
    const generation = this.data.generation
    this.setData({ actionLoading: true, error: '' })
    try {
      const checkedIn = await scanAndCheckin(next.resvId)
      if (checkedIn) await this.load()
    } catch (error) {
      if (generation === this.data.generation) this.setData({ error: errorMessage(error) })
    } finally {
      this.setData({ actionLoading: false })
    }
  },
})
