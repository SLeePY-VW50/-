package com.campus.lab.schedule;

import com.campus.lab.period.DongfangPeriodTable;
import com.campus.lab.period.PeriodSchedule;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatSnapshot;
import com.campus.lab.resource.EffectiveTimeRule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

final class ScheduleTestFixtures {

    static final LocalDate DATE = LocalDate.of(2026, 10, 26);
    static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 1, 8, 0);
    static final PeriodSchedule PERIODS = DongfangPeriodTable.schedule();
    /** Periods 1–10, 08:30–17:30. */
    static final int OPEN_START = minute(LocalTime.of(8, 30));
    static final int OPEN_END = minute(LocalTime.of(17, 30));

    private ScheduleTestFixtures() {
    }

    static ScheduleDemand demand(long resvId) {
        return demand(resvId, resvId, CREATED_AT.plusSeconds(resvId), ReservationMode.SEAT, 2);
    }

    static ScheduleDemand demand(
            long resvId, long userId, LocalDateTime createdAt,
            ReservationMode mode, int durationPeriods) {
        return new ScheduleDemand(
                resvId, userId, createdAt, DATE, OPEN_START, OPEN_END, durationPeriods,
                mode, 1, mode == ReservationMode.SEAT ? 1 : 0, 80,
                null, null, false, Set.of());
    }

    static ScheduleDemand withWindow(ScheduleDemand demand, int start, int end, int duration) {
        return copy(demand, start, end, duration, demand.mode(), demand.attendeeCount(),
                demand.seatCount(), demand.credit(), demand.minSpecLevel(),
                demand.preferredZone(), demand.preferNearWindow(), demand.allowedLabIds());
    }

    static ScheduleDemand withCredit(ScheduleDemand demand, int credit) {
        return copy(demand, demand.windowStart(), demand.windowEnd(), demand.durationPeriods(),
                demand.mode(), demand.attendeeCount(), demand.seatCount(), credit,
                demand.minSpecLevel(), demand.preferredZone(), demand.preferNearWindow(),
                demand.allowedLabIds());
    }

    static ScheduleDemand withSpec(ScheduleDemand demand, String minSpecLevel) {
        return copy(demand, demand.windowStart(), demand.windowEnd(), demand.durationPeriods(),
                demand.mode(), demand.attendeeCount(), demand.seatCount(), demand.credit(),
                minSpecLevel, demand.preferredZone(), demand.preferNearWindow(), demand.allowedLabIds());
    }

    static ScheduleDemand withPreferences(ScheduleDemand demand, String zone, boolean nearWindow) {
        return copy(demand, demand.windowStart(), demand.windowEnd(), demand.durationPeriods(),
                demand.mode(), demand.attendeeCount(), demand.seatCount(), demand.credit(),
                demand.minSpecLevel(), zone, nearWindow, demand.allowedLabIds());
    }

    static ScheduleDemand withAllowedLabs(ScheduleDemand demand, Set<Long> allowedLabIds) {
        return copy(demand, demand.windowStart(), demand.windowEnd(), demand.durationPeriods(),
                demand.mode(), demand.attendeeCount(), demand.seatCount(), demand.credit(),
                demand.minSpecLevel(), demand.preferredZone(), demand.preferNearWindow(), allowedLabIds);
    }

    static ScheduleDemand withCapacityNeeds(ScheduleDemand demand, int attendeeCount, int seatCount) {
        return copy(demand, demand.windowStart(), demand.windowEnd(), demand.durationPeriods(),
                demand.mode(), attendeeCount, seatCount, demand.credit(), demand.minSpecLevel(),
                demand.preferredZone(), demand.preferNearWindow(), demand.allowedLabIds());
    }

    static ScheduleDemand withDate(ScheduleDemand demand, LocalDate date) {
        return new ScheduleDemand(
                demand.resvId(), demand.userId(), demand.createdAt(), date,
                demand.windowStart(), demand.windowEnd(), demand.durationPeriods(), demand.mode(),
                demand.attendeeCount(), demand.seatCount(), demand.credit(), demand.minSpecLevel(),
                demand.preferredZone(), demand.preferNearWindow(), demand.allowedLabIds());
    }

    private static ScheduleDemand copy(
            ScheduleDemand demand, int start, int end, int duration, ReservationMode mode,
            int attendeeCount, int seatCount, int credit, String spec, String zone,
            boolean nearWindow, Set<Long> allowedLabIds) {
        return new ScheduleDemand(
                demand.resvId(), demand.userId(), demand.createdAt(), demand.date(), start, end,
                duration, mode, attendeeCount, seatCount, credit, spec, zone, nearWindow, allowedLabIds);
    }

    static ScheduleLabResource lab(long labId) {
        return new ScheduleLabResource(
                labId, DATE, ResourceStatus.ACTIVE, 20, mask(0, 10), 0,
                List.of(
                        seat(labId * 100 + 1, 1, "STANDARD", "A", true, 0),
                        seat(labId * 100 + 2, 2, "STANDARD", "A", true, 0)),
                rule(labId, 1, 5), PERIODS);
    }

    static ScheduleLabResource lab(
            long labId, int capacity, long openMask, long roomMask, List<SeatSnapshot> seats) {
        return new ScheduleLabResource(
                labId, DATE, ResourceStatus.ACTIVE, capacity, openMask, roomMask,
                seats, rule(labId, 1, 5), PERIODS);
    }

    static ScheduleLabResource withRule(ScheduleLabResource resource, int minPeriods, int maxPeriods) {
        return new ScheduleLabResource(
                resource.labId(), resource.date(), resource.status(), resource.capacity(),
                resource.openMask(), resource.roomMask(), resource.seats(),
                rule(resource.labId(), minPeriods, maxPeriods), resource.periodSchedule());
    }

    static ScheduleLabResource withStatus(ScheduleLabResource resource, ResourceStatus status) {
        return new ScheduleLabResource(
                resource.labId(), resource.date(), status, resource.capacity(),
                resource.openMask(), resource.roomMask(), resource.seats(),
                resource.effectiveRule(), resource.periodSchedule());
    }

    static ScheduleLabResource withDate(ScheduleLabResource resource, LocalDate date) {
        return new ScheduleLabResource(
                resource.labId(), date, resource.status(), resource.capacity(),
                resource.openMask(), resource.roomMask(), resource.seats(),
                resource.effectiveRule(), resource.periodSchedule());
    }

    static EffectiveTimeRule rule(long labId, int minPeriods, int maxPeriods) {
        return new EffectiveTimeRule(
                labId, labId, DATE.getDayOfWeek().getValue(),
                LocalTime.of(8, 30), LocalTime.of(17, 30), 1, 10,
                minPeriods, maxPeriods, 0, 365);
    }

    static SeatSnapshot seat(
            long seatId, int seatIndex, String spec, String zone, boolean nearWindow, long mask) {
        return new SeatSnapshot(
                seatId, "S" + seatId, zone, seatIndex, spec, nearWindow, ResourceStatus.ACTIVE, mask);
    }

    static SeatSnapshot seat(
            long seatId, int seatIndex, String spec, String zone, boolean nearWindow,
            ResourceStatus status, long mask) {
        return new SeatSnapshot(
                seatId, "S" + seatId, zone, seatIndex, spec, nearWindow, status, mask);
    }

    static long mask(int startSlot, int endSlot) {
        if (startSlot < 0 || endSlot > PERIODS.slotCount() || endSlot <= startSlot) {
            throw new IllegalArgumentException("Invalid test mask");
        }
        return ((1L << (endSlot - startSlot)) - 1) << startSlot;
    }

    static int minute(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    static ScheduleAssignment assignment(SchedulePlan plan, long resvId) {
        return plan.assignments().stream()
                .filter(candidate -> candidate.resvId() == resvId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing assignment for " + resvId));
    }
}
