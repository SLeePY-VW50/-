export interface TeachingPeriod {
  periodNo: number
  session: 'MORNING' | 'AFTERNOON' | 'EVENING'
  start: string
  end: string
}

/** Official Dongfang College 2026–2027 periods. The server period table is authoritative. */
export const DONGFANG_PERIODS: TeachingPeriod[] = [
  { periodNo: 1, session: 'MORNING', start: '08:30', end: '09:15' },
  { periodNo: 2, session: 'MORNING', start: '09:15', end: '10:00' },
  { periodNo: 3, session: 'MORNING', start: '10:05', end: '10:50' },
  { periodNo: 4, session: 'MORNING', start: '11:00', end: '11:45' },
  { periodNo: 5, session: 'MORNING', start: '11:45', end: '12:30' },
  { periodNo: 6, session: 'AFTERNOON', start: '13:30', end: '14:15' },
  { periodNo: 7, session: 'AFTERNOON', start: '14:15', end: '15:00' },
  { periodNo: 8, session: 'AFTERNOON', start: '15:05', end: '15:50' },
  { periodNo: 9, session: 'AFTERNOON', start: '16:00', end: '16:45' },
  { periodNo: 10, session: 'AFTERNOON', start: '16:45', end: '17:30' },
  { periodNo: 11, session: 'EVENING', start: '18:30', end: '19:15' },
  { periodNo: 12, session: 'EVENING', start: '19:15', end: '20:00' },
  { periodNo: 13, session: 'EVENING', start: '20:05', end: '20:50' },
]

export const PERIOD_STARTS = DONGFANG_PERIODS.map((period) => period.start)
export const PERIOD_ENDS = DONGFANG_PERIODS.map((period) => period.end)
export const DEFAULT_MAX_PERIODS = 5
