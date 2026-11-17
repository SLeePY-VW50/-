// @ts-expect-error Vitest provides the Node runtime without Node type declarations.
import { readFileSync } from 'node:fs'
import { describe, expect, it, vi } from 'vitest'
import { chooseSlot, type SlotRange } from '../miniprogram/utils/slots'
import type { SlotAvailability } from '../miniprogram/api/types'

const slot = (index: number, availableSeatCount = 5, roomOccupied = false): SlotAvailability => ({
  index,
  start: `${String(Math.floor(index / 2)).padStart(2, '0')}:${index % 2 ? '30' : '00'}`,
  end: '',
  roomOccupied,
  occupiedSeatCount: 0,
  availableSeatCount,
})

describe('continuous slot selection', () => {
  it('selects a start and swaps a reverse end', () => {
    const slots = Array.from({ length: 48 }, (_, i) => slot(i))
    expect(chooseSlot(slots, null, 16, 'SEAT', '06:00', '22:00')).toEqual({ range: { start: 16, end: null }, error: '' })
    expect(chooseSlot(slots, { start: 16, end: null }, 14, 'SEAT', '06:00', '22:00')).toEqual({ range: { start: 14, end: 16 }, error: '' })
  })

  it('rejects occupied or out-of-hours ranges', () => {
    const slots = Array.from({ length: 48 }, (_, i) => slot(i))
    slots[17]!.roomOccupied = true
    expect(chooseSlot(slots, { start: 16, end: null }, 18, 'ROOM', '08:00', '22:00').error).toBe('所选区间包含不可预约时段')
    expect(chooseSlot(slots, null, 2, 'SEAT', '08:00', '22:00').error).toBe('所选时段不在开放时间内')
  })

  it('starts a new selection when an existing range is clicked', () => {
    const slots = Array.from({ length: 48 }, (_, i) => slot(i))
    const current: SlotRange = { start: 14, end: 16 }
    expect(chooseSlot(slots, current, 20, 'SEAT', '08:00', '22:00').range).toEqual({ start: 20, end: null })
  })

  it('accepts opening and closing boundaries including 24:00', () => {
    const slots = Array.from({ length: 48 }, (_, i) => slot(i))
    expect(chooseSlot(slots, null, 16, 'SEAT', '08:00', '22:00').error).toBe('')
    expect(chooseSlot(slots, null, 43, 'SEAT', '08:00', '22:00').error).toBe('')
    expect(chooseSlot(slots, null, 44, 'SEAT', '08:00', '22:00').error).toBe('所选时段不在开放时间内')
    expect(chooseSlot(slots, null, 47, 'SEAT', '00:00', '24:00').error).toBe('')
  })

  it('uses seat availability and rejects an invalid slot index', () => {
    const slots = Array.from({ length: 48 }, (_, i) => slot(i))
    slots[16]!.availableSeatCount = 0
    expect(chooseSlot(slots, null, 16, 'SEAT', '08:00', '22:00')).toEqual({ range: null, error: '当前时段不可预约' })
    expect(chooseSlot(slots, null, 48, 'SEAT', '08:00', '22:00')).toEqual({ range: null, error: '当前时段不可预约' })
  })
})

describe('slot-grid WXML bindings', () => {
  it('qualifies current entry fields with an explicit slot alias', () => {
    const wxml = readFileSync('miniprogram/components/slot-grid/index.wxml', 'utf8')
    expect(wxml).toContain('wx:for-item="slot"')
    expect(wxml).toContain('data-index="{{slot.index}}"')
    expect(wxml).toContain('{{slot.start}}')
    expect(wxml).toContain('slot.roomOccupied')
    expect(wxml).toContain('slot.availableSeatCount')
    expect(wxml).not.toContain('data-index="{{index}}"')
    expect(wxml).not.toContain('{{start}}')
    expect(wxml).toContain('slots.length / 12')
    expect(wxml).toContain('cell--conflict')
    expect(wxml).toContain('hover-class="cell--pressed"')
  })
})

describe('slot-grid conflict highlight', () => {
  it('marks blocked cells in a rejected range and clears them after a valid tap', async () => {
    vi.resetModules()
    interface GridData {
      conflict: number[]
      slots: SlotAvailability[]
      selection: SlotRange | null
      mode: 'ROOM' | 'SEAT'
      openTime: string
      closeTime: string
    }
    interface GridHost {
      data: GridData
      setData(patch: Partial<GridData>): void
      triggerEvent(name: string, value: unknown): void
    }
    interface GridComponent {
      data: { conflict: number[] }
      methods: {
        select(this: GridHost, event: { currentTarget: { dataset: { index: number } } }): void
      }
    }
    let definition: GridComponent | undefined
    const showToast = vi.fn()
    vi.stubGlobal('Component', (component: GridComponent) => { definition = component })
    vi.stubGlobal('wx', { showToast })
    await import('../miniprogram/components/slot-grid/index')
    const slots = Array.from({ length: 48 }, (_, index) => slot(index))
    slots[17]!.roomOccupied = true
    const grid: GridHost = {
      data: { ...definition!.data, slots, selection: { start: 16, end: null }, mode: 'ROOM', openTime: '08:00', closeTime: '22:00' },
      setData(patch) { Object.assign(grid.data, patch) },
      triggerEvent: vi.fn(),
    }

    definition!.methods.select.call(grid, { currentTarget: { dataset: { index: 18 } } })

    expect(showToast).toHaveBeenCalledWith(expect.objectContaining({ title: '所选区间包含不可预约时段' }))
    expect(grid.data.conflict).toEqual([17])
    expect(grid.triggerEvent).not.toHaveBeenCalled()

    grid.data.selection = null
    grid.data.mode = 'SEAT'
    definition!.methods.select.call(grid, { currentTarget: { dataset: { index: 20 } } })

    expect(grid.data.conflict).toEqual([])
    expect(grid.triggerEvent).toHaveBeenCalledWith('change', { start: 20, end: null })
  })
})
