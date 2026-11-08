import type { Reservation } from '../api/types'

export function nextReservation(items: Reservation[], nowIso: string): Reservation | null {
  const inUse = items.find(item => item.status === 'IN_USE')
  if (inUse) return inUse
  const nowMinute = nowIso.slice(0, 16)
  return items
    .filter(item => item.status === 'APPROVED' && item.startTime && (item.endTime ?? item.startTime) >= nowMinute)
    .sort((a, b) => (a.startTime ?? '').localeCompare(b.startTime ?? ''))[0] ?? null
}
