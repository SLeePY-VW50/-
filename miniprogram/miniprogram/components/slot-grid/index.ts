import { chooseSlot, type SlotRange } from '../../utils/slots'
import type { SlotAvailability } from '../../api/types'

Component({
  properties: {
    slots: { type: Array, value: [] as SlotAvailability[] },
    mode: { type: String, value: 'SEAT' },
    openTime: { type: String, value: '00:00' },
    closeTime: { type: String, value: '24:00' },
    selection: { type: Object, value: null as SlotRange | null },
  },
  methods: {
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
