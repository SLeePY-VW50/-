import { chooseSlot, slotAvailable, type SlotRange } from '../../utils/slots'
import type { SlotAvailability } from '../../api/types'

function todayText(date = new Date()) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function conflictIndexes(slots: SlotAvailability[], selection: SlotRange | null, index: number,
  mode: 'ROOM' | 'SEAT', openTime: string, closeTime: string) {
  if (!selection || selection.end !== null) return []
  const start = Math.min(selection.start, index)
  const end = Math.max(selection.start, index)
  const blocked: number[] = []
  for (let cursor = start; cursor <= end; cursor++) {
    const item = slots[cursor]
    if (!item || !slotAvailable(item, mode, openTime, closeTime)) blocked.push(item?.index ?? cursor)
  }
  return blocked
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
    conflict: [] as number[],
  },
  lifetimes: {
    attached() { this.syncNow() },
  },
  observers: {
    date() { this.syncNow() },
    'slots, mode, openTime, closeTime'() {
      if (this.data.conflict.length) this.setData({ conflict: [] })
    },
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
      const slots = this.data.slots as SlotAvailability[]
      const selection = this.data.selection as SlotRange | null
      const mode = this.data.mode as 'ROOM' | 'SEAT'
      const result = chooseSlot(slots, selection, index, mode, this.data.openTime, this.data.closeTime)
      if (result.error) {
        wx.showToast({ title: result.error, icon: 'none' })
        this.setData({ conflict: conflictIndexes(slots, selection, index, mode, this.data.openTime, this.data.closeTime) })
        return
      }
      this.setData({ conflict: [] })
      this.triggerEvent('change', result.range)
    },
  },
})
