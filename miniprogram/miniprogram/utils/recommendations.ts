import type { SlotRecommendations } from '../api/types'

export function recommendationFormQuery(input: {
  labId: number
  date: string
  mode: 'ROOM' | 'SEAT'
  attendeeCount: number
  startTime: string
  endTime: string
}) {
  return [
    `labId=${input.labId}`,
    `date=${input.date}`,
    `startTime=${input.startTime}`,
    `endTime=${input.endTime}`,
    `mode=${input.mode}`,
    `attendeeCount=${input.attendeeCount}`,
    'allocationMode=FIXED',
  ].join('&')
}

export function recommendationNote(outcome: SlotRecommendations['outcome'] | '') {
  if (outcome === 'CREDIT_BLOCKED') return '信用分不足，无法推荐可预约时段'
  if (outcome === 'RULE_BLOCKED') return '当前实验室、日期或人数不满足预约规则'
  if (outcome === 'DURATION_UNFIT') return '此时长无法放入开放时间'
  if (outcome === 'NONE_FREE') return '没有足够的连续空闲时段'
  return ''
}
