export const pad = (value: number) => String(value).padStart(2, '0')

export function toDateInput(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function today() {
  return toDateInput(new Date())
}

export function addDays(date: string, amount: number) {
  const value = new Date(`${date}T00:00:00`)
  value.setDate(value.getDate() + amount)
  return toDateInput(value)
}

export function timeOnly(value: string | null | undefined, endOfDay = false) {
  if (!value) return '—'
  const time = value.includes('T') ? value.slice(value.indexOf('T') + 1) : value
  if (endOfDay && (time.startsWith('24:00') || time.startsWith('00:00:00'))) return '24:00'
  return time.slice(0, 5)
}

export function formatDateTime(value: string | null | undefined) {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

export function reservationTime(item: { startTime: string | null; endTime: string | null; date: string; windowStart: string; windowEnd: string; durationMinutes: number }) {
  return item.startTime && item.endTime
    ? `${formatDateTime(item.startTime).slice(0, 16)}–${timeOnly(item.endTime, true)}`
    : `${item.date} ${timeOnly(item.windowStart)}–${timeOnly(item.windowEnd, true)}（${item.durationMinutes}分钟）`
}

export const statusLabels: Record<string, string> = { ACTIVE: '正常', DISABLED: '停用', MAINTENANCE: '维护中', PENDING_SCHEDULE: '待排期', APPROVED: '已通过', IN_USE: '使用中', COMPLETED: '已完成', CANCELLED: '已取消', NO_SHOW: '未到场', EXPIRED: '已过期', VIOLATION_TERMINATED: '违规终止', ROOM: '整室', SEAT: '工位', FIXED: '固定时段', SCHEDULABLE: '可调度' }
