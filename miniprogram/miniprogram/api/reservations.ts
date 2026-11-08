import { request } from './http'
import type { CreateReservationPayload, PageView, QrCheckinResponse, Reservation, ReservationStatus } from './types'

export function createReservation(data: CreateReservationPayload) {
  return request<Reservation>('/api/reservations', { method: 'POST', data })
}

export function myReservations(status: ReservationStatus | undefined, page: number, size = 10) {
  return request<PageView<Reservation>>('/api/reservations/my', { query: { status, page, size } })
}

export function reservationDetail(id: number) {
  return request<Reservation>(`/api/reservations/${id}`)
}

export function cancelReservation(id: number, reason: string | null = null) {
  return request<Reservation>(`/api/reservations/${id}/cancel`, { method: 'POST', data: { reason } })
}

export function qrCheckin(id: number, content: string) {
  return request<QrCheckinResponse>('/api/checkin/qr', { method: 'POST', data: { reservationId: id, content } })
}

export function wechatCheckout(id: number) {
  return request<Reservation>(`/api/checkin/${id}/checkout`, { method: 'POST', data: { channel: 'WECHAT_QR' } })
}
