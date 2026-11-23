import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import type { SlotAvailability } from '../miniprogram/api/types'
import { chooseSlot } from '../miniprogram/utils/slots'

const slot = (index: number, bookable = true, availableSeatCount = 5, roomOccupied = false): SlotAvailability => ({
  index,
  periodNo: index + 1,
  session: index < 5 ? 'MORNING' : index < 10 ? 'AFTERNOON' : 'EVENING',
  label: `第${index + 1}节`,
  start: '08:30',
  end: '09:15',
  bookable,
  roomOccupied,
  occupiedSeatCount: 0,
  availableSeatCount,
})

describe('continuous period selection', () => {
  it('selects a bookable period and extends backward', () => {
    const slots = Array.from({ length: 10 }, (_, i) => slot(i))
    expect(chooseSlot(slots, null, 1, 'SEAT')).toEqual({ range: { start: 1, end: null }, error: '' })
    expect(chooseSlot(slots, { start: 1, end: null }, 0, 'SEAT')).toEqual({ range: { start: 0, end: 1 }, error: '' })
  })

  it('rejects a closed evening period and a gap', () => {
    const slots = Array.from({ length: 10 }, (_, i) => slot(i, i < 8))
    slots[2]!.roomOccupied = true
    expect(chooseSlot(slots, { start: 1, end: null }, 3, 'ROOM').error).toBe('所选区间包含不可预约节次')
    expect(chooseSlot(slots, null, 9, 'SEAT').error).toBe('当前节次不可预约')
  })

  it('caps a continuous selection at five periods', () => {
    const slots = Array.from({ length: 10 }, (_, i) => slot(i))
    expect(chooseSlot(slots, { start: 0, end: null }, 5, 'SEAT').error).toBe('连续选择最多5节')
  })
})

describe('slot-grid WXML bindings', () => {
  it('groups periods by session and shows the period label', () => {
    const wxml = readFileSync('miniprogram/components/slot-grid/index.wxml', 'utf8')
    expect(wxml).toContain('第{{slot.periodNo}}节')
    expect(wxml).toContain("MORNING: '上午'")
    expect(wxml).not.toContain('凌晨')
  })
})
