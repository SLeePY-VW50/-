import type { SlotAvailability } from '../api/types'

export interface SlotRange {
  start: number
  end: number | null
}

export interface SlotChoice {
  range: SlotRange | null
  error: string
}

export function slotAvailable(slot: SlotAvailability, mode: 'ROOM' | 'SEAT') {
  return slot.bookable !== false
    && (mode === 'ROOM' ? !slot.roomOccupied : slot.availableSeatCount > 0)
}

export function chooseSlot(slots: SlotAvailability[], current: SlotRange | null, index: number,
  mode: 'ROOM' | 'SEAT', maxPeriods = 5): SlotChoice {
  const target = slots[index]
  if (!target || target.bookable === false) return { range: current, error: '当前节次不可预约' }
  if (!slotAvailable(target, mode)) return { range: current, error: '当前节次不可预约' }
  if (!current || current.end !== null) return { range: { start: index, end: null }, error: '' }
  const start = Math.min(current.start, index)
  const end = Math.max(current.start, index)
  if (end - start + 1 > maxPeriods) return { range: current, error: `连续选择最多${maxPeriods}节` }
  for (let cursor = start; cursor <= end; cursor++) {
    const item = slots[cursor]
    if (!item || !slotAvailable(item, mode)) {
      return { range: current, error: '所选区间包含不可预约节次' }
    }
  }
  return { range: { start, end }, error: '' }
}
