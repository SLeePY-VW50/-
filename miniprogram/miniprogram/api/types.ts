export type Role = 'STUDENT' | 'TEACHER' | 'LAB_ADMIN' | 'SYS_ADMIN'
export type ReservationStatus = 'PENDING_SCHEDULE' | 'APPROVED' | 'IN_USE' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'EXPIRED' | 'VIOLATION_TERMINATED'
export interface User { userId: number; account: string; name: string; roles: Role[]; status: 'ACTIVE' | 'DISABLED' }
export interface ApiEnvelope<T> { code: number; message: string; data: T }
export interface LoginResponse { token: string; user: User }
export interface WeChatLoginResponse { bound: boolean; token: string | null; user: User | null; bindTicket: string | null; expiresIn: number | null }
export interface Lab { labId: number; code: string; name: string; location: string; capacity: number; status: 'ACTIVE' | 'DISABLED' | 'MAINTENANCE' }
export interface TimeRule { ruleId: number; labId: number; dayOfWeek: number; openTime: string; closeTime: string; minDurationMinutes: number; maxDurationMinutes: number; advanceMinDays: number; advanceMaxDays: number }
export interface LabRules { labId: number; weeklyRules: TimeRule[]; cancellation: { freeBeforeHours: number }; checkin: { beforeMinutes: number; graceMinutes: number; choiceExpireSeconds: number; maxFailedAttempts: number; lockoutMinutes: number } }
export interface SlotAvailability { index: number; start: string; end: string; roomOccupied: boolean; occupiedSeatCount: number; availableSeatCount: number }
export interface LabAvailability { labId: number; date: string; labStatus: string; roomMask: string; seats: unknown[]; slots: SlotAvailability[] }
export interface ReservationPreferences { zoneCode?: string | null; nearWindow?: boolean | null; minSpecLevel?: string | null; allowedLabIds?: number[] }
export interface ReservationSeat { seatId: number; seatCode: string; zoneCode: string; seatIndex: number; specLevel: string; mask: string; zoneMismatch: boolean; nearWindowMismatch: boolean }
export interface Reservation { resvId: number; batchId: number | null; batchNo: string | null; labId: number | null; labName: string | null; date: string; windowStart: string; windowEnd: string; durationMinutes: number; startTime: string | null; endTime: string | null; allocationMode: 'FIXED' | 'SCHEDULABLE'; reservationMode: 'ROOM' | 'SEAT'; status: ReservationStatus; attendeeCount: number; seatCount: number | null; purpose: string; preferences: ReservationPreferences; seats: ReservationSeat[]; checkinChannel: string | null; checkinTime: string | null; checkoutChannel: string | null; checkoutTime: string | null; createdAt: string }
export interface PageView<T> { items: T[]; page: number; size: number; total: number }
export interface CreateReservationPayload { allocationMode: 'FIXED' | 'SCHEDULABLE'; labId: number | null; date: string; startTime: string | null; endTime: string | null; windowStart: string | null; windowEnd: string | null; durationMinutes: number | null; reservationMode: 'ROOM' | 'SEAT'; attendeeCount: number; seatCount: number | null; purpose: string; preferences: ReservationPreferences }
export interface QrCheckinResponse { reservation: Reservation; alreadyCheckedIn: boolean }
