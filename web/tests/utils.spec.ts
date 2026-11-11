import { describe, expect, it } from 'vitest'
import { reservationTime, seatLabel } from '../src/utils'
import type { Reservation } from '../src/types'

describe('预约时间展示', () => {
  it('从ISO开始和结束时间中展示正式时段', () => {
    const reservation = {
      startTime: '2026-11-02T08:00:00',
      endTime: '2026-11-02T10:00:00',
    } as Reservation

    expect(reservationTime(reservation)).toBe('2026-11-02 08:00–10:00')
  })
})

describe('工位短标签', () => {
  it('去掉实验室前缀和 SEAT- 命名前缀，保留编号', () => {
    expect(seatLabel('LAB-A-SEAT-01', 'LAB-A')).toBe('01')
    expect(seatLabel('LAB-A-SEAT-15', 'LAB-A')).toBe('15')
    expect(seatLabel('LAB-B-SEAT-03', 'LAB-B')).toBe('03')
    expect(seatLabel('LAB-CHECKIN-DEMO-SEAT-01', 'LAB-CHECKIN-DEMO')).toBe('01')
    expect(seatLabel('CHECKIN-SEAT-01')).toBe('01')
    expect(seatLabel('SEAT-15')).toBe('15')
    expect(seatLabel('LAB-A-01', 'LAB-A')).toBe('01')
  })

  it('没有这些前缀时保持原编号', () => {
    expect(seatLabel('A-01', 'LAB-A')).toBe('A-01')
    expect(seatLabel('01')).toBe('01')
  })
})
