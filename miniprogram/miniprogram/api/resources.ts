import { request } from './http'
import type { Lab, LabAvailability, LabRules } from './types'

export function listLabs() {
  return request<Lab[]>('/api/labs')
}

export function labRules(labId: number) {
  return request<LabRules>(`/api/labs/${labId}/rules`)
}

export function labAvailability(labId: number, date: string) {
  return request<LabAvailability>(`/api/labs/${labId}/availability`, { query: { date } })
}
