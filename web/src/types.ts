export type Role = 'STUDENT' | 'TEACHER' | 'LAB_ADMIN' | 'SYS_ADMIN'
export type ResourceStatus = 'ACTIVE' | 'DISABLED' | 'MAINTENANCE'
export type ReservationStatus = 'PENDING_SCHEDULE' | 'APPROVED' | 'IN_USE' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'EXPIRED' | 'VIOLATION_TERMINATED'
export type ScheduleStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'PUBLISHED' | 'PUBLISH_FAILED'

export interface User {
  userId: number
  account: string
  name: string
  roles: Role[]
  status: 'ACTIVE' | 'DISABLED'
}

export interface Lab {
  labId: number
  code: string
  name: string
  location: string
  capacity: number
  status: ResourceStatus
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface TimeRule {
  ruleId: number
  labId: number
  dayOfWeek: number
  openTime: string
  closeTime: string
  minDurationMinutes: number
  maxDurationMinutes: number
  advanceMinDays: number
  advanceMaxDays: number
}

export interface LabRules {
  labId: number
  weeklyRules: TimeRule[]
  cancellation: { freeBeforeHours: number }
  checkin: {
    beforeMinutes: number
    graceMinutes: number
    choiceExpireSeconds: number
    maxFailedAttempts: number
    lockoutMinutes: number
  }
}

export interface SlotAvailability {
  index: number
  start: string
  end: string
  roomOccupied: boolean
  occupiedSeatCount: number
  availableSeatCount: number
}

export interface SeatAvailability {
  seatId: number
  seatCode: string
  zoneCode: string
  seatIndex: number
  specLevel: string
  nearWindow: boolean
  status: ResourceStatus
  mask: string
}

export interface LabAvailability {
  labId: number
  date: string
  labStatus: ResourceStatus
  roomMask: string
  seats: SeatAvailability[]
  slots: SlotAvailability[]
}

export interface ReservationPreferences {
  zoneCode?: string | null
  nearWindow?: boolean | null
  minSpecLevel?: string | null
  allowedLabIds?: number[]
}

export interface ReservationDraft {
  labId: number
  date: string
  startTime: string
  endTime: string
  allocationMode: 'FIXED'
  reservationMode: 'ROOM' | 'SEAT'
  seatCount: number
  preferences: ReservationPreferences
}

export interface CreateReservationPayload {
  allocationMode: 'FIXED' | 'SCHEDULABLE'
  labId: number | null
  date: string
  startTime: string | null
  endTime: string | null
  windowStart: string | null
  windowEnd: string | null
  durationMinutes: number | null
  reservationMode: 'ROOM' | 'SEAT'
  attendeeCount: number
  seatCount: number | null
  purpose: string
  preferences: ReservationPreferences
}

export interface ReservationSeat {
  seatId: number
  seatCode: string
  zoneCode: string
  seatIndex: number
  specLevel: string
  mask: string
  zoneMismatch: boolean
  nearWindowMismatch: boolean
}

export interface Reservation {
  resvId: number
  batchId: number | null
  batchNo: string | null
  labId: number | null
  labName: string | null
  date: string
  windowStart: string
  windowEnd: string
  durationMinutes: number
  startTime: string | null
  endTime: string | null
  allocationMode: 'FIXED' | 'SCHEDULABLE'
  reservationMode: 'ROOM' | 'SEAT'
  status: ReservationStatus
  attendeeCount: number
  seatCount: number | null
  purpose: string
  preferences: ReservationPreferences
  seats: ReservationSeat[]
  checkinChannel: string | null
  checkinTime: string | null
  checkoutChannel: string | null
  checkoutTime: string | null
  createdAt: string
}

export interface TrendDay {
  date: string
  reservationCount: number
  noShowCount: number
  plannedUtilization: number
  actualUtilization: number
  noShowRate: number
}

export interface DashboardData {
  date: string
  labCount: number
  reservationCount: number
  pendingScheduleCount: number
  inUseCount: number
  approvedCount: number
  completedCount: number
  noShowCount: number
  cancelledCount: number
  plannedUtilization: number
  actualUtilization: number
  noShowRate: number
  creditScore: number
  trend: TrendDay[]
}

export interface HeatmapSlot {
  slot: number
  start: string
  end: string
  plannedRatio: number
  actualRatio: number
}

export interface HeatmapDay {
  labId: number
  date: string
  slots: HeatmapSlot[]
}

export interface CreditRecord {
  recordId: number
  violationId: number | null
  changeType: string
  changeAmount: number
  balanceBefore: number
  balanceAfter: number
  operatorId: number | null
  createdAt: string
}

export interface Violation {
  violationId: number
  resvId: number
  violationType: string
  deductPoints: number
  status: string
  createdAt: string
  revokedAt?: string
  appealId?: number
  appealStatus?: string
}

export interface Appeal {
  appealId: number
  violationId: number
  userId: number
  reason: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  reviewerId: number | null
  reviewOpinion: string | null
  reviewedAt: string | null
  createdAt: string
}

export interface ScheduleExperiment {
  allocationSuccessRate: number
  averageSeatUtilization: number
  seatPreferenceMismatchRate: number
  timeFragmentationRate: number
  seatAllocationFailureRate: number | null
  phaseOneDurationMs: number
  phaseTwoDurationMs: number
  totalDurationMs: number
}

export interface ScheduleResult {
  resultId: number
  resvId: number
  assignedLabId: number | null
  assignedStartTime: string | null
  assignedEndTime: string | null
  objectiveValue: number | null
  candidateSeatAllocations: unknown
  published: boolean
  publishedAt: string | null
}

export interface ScheduleTask {
  taskId: number
  batchNo: string
  algorithmType: 'FCFS' | 'GREEDY' | 'GENETIC'
  primary: boolean
  datasetId: string
  status: ScheduleStatus
  progress: number
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  errorMessage: string | null
  randomSeed: number | null
  repeatIndex: number
  parameters: unknown
  runtimeEnvironment: unknown
  snapshotMetadata: { datasetId: string; requestDate: string; demandCount: number; resourceCount: number } | null
  experiment: ScheduleExperiment | null
  results: ScheduleResult[]
  publishedAt: string | null
}

export interface ScheduleBatch {
  batchNo: string
  datasetId: string
  requestDate: string
  primaryTaskId: number
  primaryStatus: ScheduleStatus
  taskCount: number
  createdAt: string
  publishedAt: string | null
}

export interface ScheduleBatchDetail extends Omit<ScheduleBatch, 'taskCount'> {
  tasks: ScheduleTask[]
}

export interface ExperimentTask {
  taskId: number
  algorithmType: 'FCFS' | 'GREEDY' | 'GENETIC'
  primary: boolean
  status: ScheduleStatus
  randomSeed: number
  repeatIndex: number
  parameters: unknown
  runtimeEnvironment: unknown
  demandSnapshot: unknown
  resourceSnapshot: unknown
  experiment: ScheduleExperiment | null
}

export interface ExperimentBatch {
  batchNo: string
  datasetId: string
  dataScale: number
  seed: number
  status: ScheduleStatus
  tasks: ExperimentTask[]
  geneticSummary: Record<string, number | null> | null
}

export interface TerminalOption {
  reservationId: number
  startTime: string
  endTime: string
  reservationMode: 'ROOM' | 'SEAT'
  attendeeCount: number
  seatCount: number | null
  seats: ReservationSeat[]
}

export interface TerminalCheckin {
  choiceRequired: boolean
  choiceToken: string | null
  expiresIn: number | null
  reservations: TerminalOption[]
  reservationId: number | null
  status: ReservationStatus | null
  checkinChannel: string | null
  checkinTime: string | null
  alreadyCheckedIn: boolean
}
