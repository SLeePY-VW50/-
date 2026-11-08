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

function minute(value: string, endBoundary = false) {
  const match = /^(\d{2}):(\d{2})$/.exec(value)
  if (!match) return null
  const hour = Number(match[1])
  const minutePart = Number(match[2])
  if (hour > 24 || minutePart > 59 || (hour === 24 && (!endBoundary || minutePart !== 0))) return null
  return hour * 60 + minutePart
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
    if (start % 30 !== 0 || end % 30 !== 0) throw new Error('时间必须按30分钟对齐')
    if (end <= start || end - start > 240) throw new Error('固定预约一次最多选择8个时间片')
  } else {
    if (form.durationMinutes < 30 || form.durationMinutes > 240 || form.durationMinutes % 30 !== 0) {
      throw new Error('可调度时长必须为30到240分钟之间的30分钟倍数')
    }
    const start = minute(form.windowStart)
    const end = minute(form.windowEnd, true)
    if (start === null || end === null) throw new Error('时间必须为HH:mm格式，24:00仅允许作为结束时间')
    if (start % 30 !== 0 || end % 30 !== 0) throw new Error('时间必须按30分钟对齐')
    if (end <= start || form.durationMinutes > end - start) throw new Error('预约时长不能超过可调度请求窗口')
  }

  return {
    allocationMode: form.allocationMode,
    labId: form.labId,
    date: form.date,
    startTime: fixed ? form.startTime : null,
    endTime: fixed ? form.endTime : null,
    windowStart: fixed ? null : form.windowStart,
    windowEnd: fixed ? null : form.windowEnd,
    durationMinutes: fixed ? null : form.durationMinutes,
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
