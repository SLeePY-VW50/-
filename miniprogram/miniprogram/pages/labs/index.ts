import { labAvailability, labRules, listLabs, slotRecommendations } from '../../api/resources'
import { errorMessage } from '../../api/http'
import type { Lab, LabAvailability, LabRules, SlotRecommendation, TimeRule } from '../../api/types'
import { recommendationFormQuery, recommendationNote } from '../../utils/recommendations'
import { addDays, today, timeOnly } from '../../utils/format'
import { restoreSession } from '../../auth/session'
import type { SlotRange } from '../../utils/slots'

Page({
  data: {
    labs: [] as Lab[],
    labNames: [] as string[],
    labIndex: 0,
    labId: 0,
    date: addDays(today(), 1),
    mode: 'SEAT' as 'ROOM' | 'SEAT',
    rules: null as LabRules | null,
    availability: null as LabAvailability | null,
    dayRule: null as TimeRule | null,
    openTime: '00:00',
    closeTime: '24:00',
    selection: null as SlotRange | null,
    selectedText: '',
    durationMinutes: 60,
    durationOptions: [30, 60, 90, 120, 150, 180, 210, 240],
    durationIndex: 1,
    attendeeCount: 1,
    recommendations: [] as SlotRecommendation[],
    recommendationNote: '',
    recommending: false,
    loading: false,
    error: '',
    errorSource: '' as '' | 'labs' | 'availability',
  },

  requestGeneration: 0,

  async onShow() {
    if (await restoreSession()) await this.loadLabs()
  },

  async loadLabs() {
    const generation = ++this.requestGeneration
    this.setData({ loading: true, error: '', errorSource: '' })
    try {
      const labs = await listLabs()
      if (generation !== this.requestGeneration) return
      this.setData({
        labs,
        labNames: labs.map(lab => `${lab.code} · ${lab.name}`),
        labIndex: 0,
        labId: labs[0]?.labId ?? 0,
        rules: null,
        availability: null,
        dayRule: null,
        selection: null,
        selectedText: '',
      })
      if (labs[0]) await this.loadAvailability()
    } catch (error) {
      if (generation !== this.requestGeneration) return
      this.setData({ error: errorMessage(error), errorSource: 'labs' })
    } finally {
      if (generation === this.requestGeneration) this.setData({ loading: false })
    }
  },

  async loadAvailability() {
    const generation = ++this.requestGeneration
    const labId = this.data.labId
    const date = this.data.date
    if (!labId) {
      this.setData({ loading: false })
      return
    }
    this.setData({
      loading: true,
      error: '',
      errorSource: '',
      selection: null,
      selectedText: '',
      recommendations: [],
      recommendationNote: '',
    })
    try {
      const [rules, availability] = await Promise.all([
        labRules(labId),
        labAvailability(labId, date),
      ])
      if (generation !== this.requestGeneration) return
      const jsDay = new Date(`${date}T00:00:00`).getDay()
      const dayRule = rules.weeklyRules.find(rule => rule.dayOfWeek === (jsDay || 7)) ?? null
      this.setData({
        rules,
        availability,
        dayRule,
        openTime: timeOnly(dayRule?.openTime ?? '00:00'),
        closeTime: timeOnly(dayRule?.closeTime ?? '24:00', true),
      })
    } catch (error) {
      if (generation !== this.requestGeneration) return
      this.setData({ error: errorMessage(error), errorSource: 'availability', rules: null, availability: null, dayRule: null })
    } finally {
      if (generation === this.requestGeneration) this.setData({ loading: false })
    }
  },

  retry() {
    if (this.data.errorSource === 'labs') {
      void this.loadLabs()
    } else if (this.data.errorSource === 'availability') {
      void this.loadAvailability()
    }
  },

  onLab(event: WechatMiniprogram.PickerChange) {
    const labIndex = Number(event.detail.value)
    this.setData({ labIndex, labId: this.data.labs[labIndex]?.labId ?? 0 })
    void this.loadAvailability()
  },

  onDate(event: WechatMiniprogram.PickerChange) {
    this.setData({ date: String(event.detail.value) })
    void this.loadAvailability()
  },

  onMode(event: WechatMiniprogram.TouchEvent) {
    this.setData({
      mode: event.currentTarget.dataset.mode,
      selection: null,
      selectedText: '',
      recommendations: [],
      recommendationNote: '',
    })
  },

  onDuration(event: WechatMiniprogram.PickerChange) {
    const durationIndex = Number(event.detail.value)
    this.setData({
      durationIndex,
      durationMinutes: this.data.durationOptions[durationIndex],
      recommendations: [],
      recommendationNote: '',
    })
  },

  onAttendees(event: WechatMiniprogram.Input) {
    this.setData({ attendeeCount: Number(event.detail.value), recommendations: [], recommendationNote: '' })
  },

  async loadRecommendations() {
    const attendees = Number(this.data.attendeeCount)
    if (!this.data.labId || !Number.isInteger(attendees) || attendees < 1) {
      this.setData({ recommendations: [], recommendationNote: '请填写至少 1 人' })
      return
    }
    this.setData({ recommending: true, recommendationNote: '' })
    try {
      const data = await slotRecommendations(
        this.data.labId,
        this.data.date,
        this.data.durationMinutes,
        this.data.mode,
        attendees,
      )
      this.setData({
        recommendations: data.recommendations,
        recommendationNote: recommendationNote(data.outcome),
      })
    } catch (error) {
      this.setData({ recommendations: [], recommendationNote: errorMessage(error) })
    } finally {
      this.setData({ recommending: false })
    }
  },

  useRecommendation(event: WechatMiniprogram.TouchEvent) {
    const item = this.data.recommendations[Number(event.currentTarget.dataset.index)]
    if (!item) return
    const query = recommendationFormQuery({
      labId: this.data.labId,
      date: this.data.date,
      mode: this.data.mode,
      attendeeCount: Number(this.data.attendeeCount),
      startTime: item.startTime,
      endTime: item.endTime,
    })
    wx.navigateTo({ url: `/pages/reservation-form/index?${query}` })
  },

  onSelection(event: WechatMiniprogram.CustomEvent<SlotRange>) {
    const range = event.detail
    const slots = this.data.availability?.slots ?? []
    this.setData({
      selection: range,
      selectedText: range.end === null ? '' : `${slots[range.start]?.start}–${slots[range.end]?.end}`,
    })
  },

  goReserve() {
    const range = this.data.selection
    const slots = this.data.availability?.slots ?? []
    if (!range || range.end === null) {
      wx.showToast({ title: '请选择完整连续时段', icon: 'none' })
      return
    }
    const query = `labId=${this.data.labId}&date=${this.data.date}&startTime=${slots[range.start]?.start}&endTime=${slots[range.end]?.end}&mode=${this.data.mode}`
    wx.navigateTo({ url: `/pages/reservation-form/index?${query}` })
  },
})
