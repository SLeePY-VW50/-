import type { SlotAvailability } from '../api/types'

export interface SlotRange {
  start: number
  end: number | null
}

export interface SlotChoice {
  range: SlotRange | null
  error: string
}

function minute(value: string) {
  const [hour, minutePart] = value.split(':').map(Number)
  return hour === 24 ? 1440 : (hour ?? 0) * 60 + (minutePart ?? 0)
}

export function slotAvailable(slot: SlotAvailability, mode: 'ROOM' | 'SEAT', openTime: string, closeTime: string) {
  const start = slot.index * 30
  return start >= minute(openTime) && start + 30 <= minute(closeTime)
    && (mode === 'ROOM' ? !slot.roomOccupied : slot.availableSeatCount > 0)
}

export function chooseSlot(slots: SlotAvailability[], current: SlotRange | null, index: number,
  mode: 'ROOM' | 'SEAT', openTime: string, closeTime: string): SlotChoice {
  const target = slots[index]
  if (!target) return { range: current, error: '当前时段不可预约' }
  const startMinute = target.index * 30
  if (startMinute < minute(openTime) || startMinute + 30 > minute(closeTime)) {
    return { range: current, error: '所选时段不在开放时间内' }
  }
  if (!slotAvailable(target, mode, openTime, closeTime)) return { range: current, error: '当前时段不可预约' }
  if (!current || current.end !== null) return { range: { start: index, end: null }, error: '' }
  const start = Math.min(current.start, index)
  const end = Math.max(current.start, index)
  for (let cursor = start; cursor <= end; cursor++) {
    const item = slots[cursor]
    if (!item || !slotAvailable(item, mode, openTime, closeTime)) {
      return { range: current, error: '所选区间包含不可预约时段' }
    }
  }
  return { range: { start, end }, error: '' }
}
