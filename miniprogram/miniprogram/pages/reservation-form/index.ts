import { createReservation } from '../../api/reservations'
import { errorMessage } from '../../api/http'
import { restoreSession } from '../../auth/session'
import { buildReservationPayload } from '../../utils/reservation-form'

Page({
  data: {
    allocationMode: 'FIXED' as 'FIXED' | 'SCHEDULABLE',
    labId: 0,
    date: '',
    startTime: '09:00',
    endTime: '10:00',
    windowStart: '08:00',
    windowEnd: '12:00',
    durationMinutes: 60,
    reservationMode: 'SEAT' as 'ROOM' | 'SEAT',
    attendeeCount: 1,
    purpose: '',
    zoneCode: '',
    nearWindow: '' as '' | 'true' | 'false',
    minSpecLevel: '',
    specOptions: ['不限', 'STANDARD', 'HIGH', 'GPU'],
    specIndex: 0,
    windowOptions: ['不限', '靠窗', '不靠窗'],
    windowIndex: 0,
    durationOptions: [30, 60, 90, 120, 150, 180, 210, 240],
    durationIndex: 1,
    submitting: false,
    error: '',
    success: '',
  },

  async onLoad(query: Record<string, string>) {
    if (!await restoreSession()) return
    this.setData({
      labId: Number(query.labId),
      date: query.date ?? '',
      startTime: (query.startTime ?? '09:00').slice(0, 5),
      endTime: (query.endTime ?? '10:00').slice(0, 5),
      reservationMode: query.mode === 'ROOM' ? 'ROOM' : 'SEAT',
    })
  },

  onAllocation(event: WechatMiniprogram.TouchEvent) {
    this.setData({ allocationMode: event.currentTarget.dataset.value })
  },

  onMode(event: WechatMiniprogram.TouchEvent) {
    this.setData({ reservationMode: event.currentTarget.dataset.value })
  },

  onText(event: WechatMiniprogram.Input) {
    this.setData({ [event.currentTarget.dataset.field]: event.detail.value })
  },

  onCount(event: WechatMiniprogram.Input) {
    this.setData({ attendeeCount: Number(event.detail.value) })
  },

  onSpec(event: WechatMiniprogram.PickerChange) {
    const specIndex = Number(event.detail.value)
    this.setData({ specIndex, minSpecLevel: specIndex === 0 ? '' : this.data.specOptions[specIndex] })
  },

  onWindow(event: WechatMiniprogram.PickerChange) {
    const windowIndex = Number(event.detail.value)
    this.setData({ windowIndex, nearWindow: windowIndex === 0 ? '' : windowIndex === 1 ? 'true' : 'false' })
  },

  onDuration(event: WechatMiniprogram.PickerChange) {
    const durationIndex = Number(event.detail.value)
    this.setData({ durationIndex, durationMinutes: this.data.durationOptions[durationIndex] })
  },

  async submit() {
    if (this.data.submitting) return

    let payload: ReturnType<typeof buildReservationPayload>
    try {
      payload = buildReservationPayload(this.data)
    } catch (error) {
      this.setData({ error: errorMessage(error), success: '' })
      return
    }

    this.setData({ submitting: true, error: '', success: '' })
    try {
      const created = await createReservation(payload)
      this.setData({ success: `预约 #${created.resvId} 已提交` })
      wx.redirectTo({ url: `/pages/reservation-detail/index?id=${created.resvId}` })
    } catch (error) {
      this.setData({ error: errorMessage(error) })
    } finally {
      this.setData({ submitting: false })
    }
  },
})
