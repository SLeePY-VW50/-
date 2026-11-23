import type { CreateReservationPayload } from '../api/types'

export interface ReservationFormState {
  allocationMode: 'FIXED' | 'SCHEDULABLE'
  labId: number
  date: string
  startTime: string
  endTime: string
  windowStart: string
  windowEnd: string
  durationMinutes: number
  reservationMode: 'ROOM' | 'SEAT'
  attendeeCount: number
  purpose: string
  zoneCode: string
  nearWindow: '' | 'true' | 'false'
  minSpecLevel: string
}

const PERIOD_STARTS = ['08:30', '09:15', '10:05', '11:00', '11:45', '13:30', '14:15', '15:05', '16:00', '16:45', '18:30', '19:15', '20:05']
const PERIOD_ENDS = ['09:15', '10:00', '10:50', '11:45', '12:30', '14:15', '15:00', '15:50', '16:45', '17:30', '19:15', '20:00', '20:50']

function minute(value: string, endBoundary = false) {
  const match = /^(\d{2}):(\d{2})$/.exec(value)
  if (!match) return null
  const hour = Number(match[1])
  const minutePart = Number(match[2])
  if (hour > 24 || minutePart > 59 || (hour === 24 && (!endBoundary || minutePart !== 0))) return null
  return hour * 60 + minutePart
}

export function prepareReservationSubmit(form: ReservationFormState, confirmed: boolean): CreateReservationPayload {
  const payload = buildReservationPayload(form)
  if (form.allocationMode === 'SCHEDULABLE' && !confirmed) {
    throw new Error('未经确认不能提交可调度预约')
  }
  return payload
}

export function buildReservationPayload(form: ReservationFormState): CreateReservationPayload {
  if (!Number.isFinite(form.attendeeCount) || !Number.isInteger(form.attendeeCount)) {
    throw new Error('人数必须为有限正整数')
  }
  if (!form.labId || !form.date || !form.purpose.trim() || form.attendeeCount < 1) {
    throw new Error('请完整填写实验室、日期、人数和用途')
  }
  if (form.purpose.trim().length > 500) throw new Error('用途不能超过500字')

  const fixed = form.allocationMode === 'FIXED'
  if (fixed) {
    const start = minute(form.startTime)
    const end = minute(form.endTime, true)
    if (start === null || end === null) throw new Error('时间必须为HH:mm格式，24:00仅允许作为结束时间')
    const startIndex = PERIOD_STARTS.indexOf(form.startTime)
    const endIndex = PERIOD_ENDS.indexOf(form.endTime)
    if (startIndex < 0 || endIndex < 0) throw new Error('预约起止必须落在节次的开始和结束')
    if (endIndex < startIndex || endIndex - startIndex + 1 > 5) throw new Error('固定预约一次最多选择5节')
  } else {
    if (!Number.isInteger(form.durationMinutes) || form.durationMinutes < 1 || form.durationMinutes > 5) {
      throw new Error('可调度节数必须为1到5节')
    }
    const start = minute(form.windowStart)
    const end = minute(form.windowEnd, true)
    if (start === null || end === null) throw new Error('时间必须为HH:mm格式，24:00仅允许作为结束时间')
    const startIndex = PERIOD_STARTS.indexOf(form.windowStart)
    const endIndex = PERIOD_ENDS.indexOf(form.windowEnd)
    if (startIndex < 0 || endIndex < 0) throw new Error('预约起止必须落在节次的开始和结束')
    if (end <= start || form.durationMinutes > endIndex - startIndex + 1) throw new Error('预约节数不能超过可调度请求窗口')
  }

  return {
    allocationMode: form.allocationMode,
    labId: form.labId,
    date: form.date,
    startTime: fixed ? form.startTime : null,
    endTime: fixed ? form.endTime : null,
    windowStart: fixed ? null : form.windowStart,
    windowEnd: fixed ? null : form.windowEnd,
    durationPeriods: fixed ? null : form.durationMinutes,
    reservationMode: form.reservationMode,
    attendeeCount: form.attendeeCount,
    seatCount: form.reservationMode === 'SEAT' ? form.attendeeCount : null,
    purpose: form.purpose.trim(),
    preferences: {
      zoneCode: form.zoneCode.trim() || null,
      nearWindow: form.nearWindow === '' ? null : form.nearWindow === 'true',
      minSpecLevel: form.minSpecLevel || null,
      allowedLabIds: [],
    },
  }
}
