import type { CreateReservationPayload, SchedulableReservationDraft } from './types'
import { timeOnly } from './utils'

export function buildConfirmedSchedulablePayload(
  draft: SchedulableReservationDraft,
  confirmed: boolean,
  purpose: string,
): CreateReservationPayload {
  if (draft.allocationMode !== 'SCHEDULABLE') throw new Error('仅可调度草稿可以一键提交')
  if (!confirmed) throw new Error('未经确认不能提交')
  const trimmed = purpose.trim()
  if (!trimmed) throw new Error('请填写使用用途')
  if (trimmed.length > 500) throw new Error('用途不能超过500字')
  if (!Number.isInteger(draft.attendeeCount) || draft.attendeeCount < 1) throw new Error('人数必须为有限正整数')
  if (draft.durationMinutes < 30 || draft.durationMinutes > 240 || draft.durationMinutes % 30 !== 0) {
    throw new Error('可调度时长必须为30到240分钟之间的30分钟倍数')
  }
  const windowStart = timeOnly(draft.windowStart)
  const windowEnd = timeOnly(draft.windowEnd, true)
  if (windowStart === '—' || windowEnd === '—') throw new Error('可调度窗口不完整')
  return {
    allocationMode: 'SCHEDULABLE',
    labId: draft.labId ?? null,
    date: draft.date,
    startTime: null,
    endTime: null,
    windowStart,
    windowEnd,
    durationMinutes: draft.durationMinutes,
    reservationMode: draft.reservationMode,
    attendeeCount: draft.attendeeCount,
    seatCount: draft.reservationMode === 'SEAT' ? draft.attendeeCount : null,
    purpose: trimmed,
    preferences: {
      zoneCode: draft.preferences?.zoneCode ?? null,
      nearWindow: draft.preferences?.nearWindow ?? null,
      minSpecLevel: draft.preferences?.minSpecLevel ?? null,
      allowedLabIds: draft.preferences?.allowedLabIds ?? [],
    },
  }
}
