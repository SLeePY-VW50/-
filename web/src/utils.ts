import type { Reservation, Role } from './types'

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
  if (endOfDay && time.startsWith('24:00')) return '24:00'
  if (endOfDay && time.startsWith('00:00:00')) return '24:00'
  return time.slice(0, 5)
}

export function formatDateTime(value: string | null | undefined) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

export function reservationTime(item: Reservation) {
  if (item.startTime && item.endTime) {
    return `${formatDateTime(item.startTime).slice(0, 16)}–${timeOnly(item.endTime, true)}`
  }
  return `${item.date} ${timeOnly(item.windowStart)}–${timeOnly(item.windowEnd, true)}（${item.durationMinutes}分钟）`
}

export const roleLabels: Record<Role, string> = {
  STUDENT: '学生',
  TEACHER: '教师',
  LAB_ADMIN: '实验室管理员',
  SYS_ADMIN: '系统管理员',
}

export const statusLabels: Record<string, string> = {
  ACTIVE: '正常',
  DISABLED: '停用',
  MAINTENANCE: '维护中',
  PENDING_SCHEDULE: '待排程',
  APPROVED: '已批准',
  IN_USE: '使用中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  NO_SHOW: '未到场',
  EXPIRED: '已过期',
  VIOLATION_TERMINATED: '违规终止',
  PENDING: '待处理',
  RUNNING: '运行中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  PUBLISHED: '已发布',
  PUBLISH_FAILED: '发布失败',
  REJECTED: '已驳回',
  ROOM: '整室',
  SEAT: '工位',
  FIXED: '固定时段',
  SCHEDULABLE: '可调度',
}

export function percent(value: number | null | undefined) {
  return `${((value ?? 0) * 100).toFixed(1)}%`
}

export function getErrorMessage(error: unknown) {
  if (error instanceof Error && error.message) return error.message
  return '请求失败，请稍后重试'
}
