import { describe, expect, it } from 'vitest'
import { reservationTime } from '../src/utils'
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
