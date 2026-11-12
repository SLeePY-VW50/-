import { chooseSlot, type SlotRange } from '../../utils/slots'
import type { SlotAvailability } from '../../api/types'

function todayText(date = new Date()) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

Component({
  properties: {
    slots: { type: Array, value: [] as SlotAvailability[] },
    mode: { type: String, value: 'SEAT' },
    openTime: { type: String, value: '00:00' },
    closeTime: { type: String, value: '24:00' },
    selection: { type: Object, value: null as SlotRange | null },
    date: { type: String, value: '' },
  },
  data: {
    nowPeriod: -1,
    nowTop: '0%',
    nowLabel: '',
  },
  lifetimes: {
    attached() { this.syncNow() },
  },
  observers: {
    date() { this.syncNow() },
  },
  methods: {
    syncNow() {
      const date = this.data.date
      if (!date || date !== todayText()) {
        this.setData({ nowPeriod: -1, nowTop: '0%', nowLabel: '' })
        return
      }
      const current = new Date()
      const mins = current.getHours() * 60 + current.getMinutes()
      this.setData({
        nowPeriod: Math.min(3, Math.floor(mins / 360)),
        nowTop: `${((mins % 360) / 360) * 100}%`,
        nowLabel: `${String(current.getHours()).padStart(2, '0')}:${String(current.getMinutes()).padStart(2, '0')}`,
      })
    },
    select(event: WechatMiniprogram.TouchEvent) {
      const index = Number(event.currentTarget.dataset.index)
      const result = chooseSlot(this.data.slots as SlotAvailability[], this.data.selection as SlotRange | null,
        index, this.data.mode as 'ROOM' | 'SEAT', this.data.openTime, this.data.closeTime)
      if (result.error) {
        wx.showToast({ title: result.error, icon: 'none' })
        return
      }
      this.triggerEvent('change', result.range)
    },
  },
})
