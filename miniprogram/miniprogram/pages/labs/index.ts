import { labAvailability, labRules, listLabs } from '../../api/resources'
import { errorMessage } from '../../api/http'
import type { Lab, LabAvailability, LabRules, TimeRule } from '../../api/types'
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
    this.setData({ loading: true, error: '', errorSource: '', selection: null, selectedText: '' })
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
    this.setData({ mode: event.currentTarget.dataset.mode, selection: null, selectedText: '' })
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
