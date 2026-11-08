package com.campus.lab.statistics;

import com.campus.lab.common.BusinessException;
import com.campus.lab.common.DayTimes;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatisticsService {

    private static final Set<ReservationStatus> PLANNED_STATUSES = Set.of(
            ReservationStatus.APPROVED, ReservationStatus.IN_USE, ReservationStatus.COMPLETED,
            ReservationStatus.NO_SHOW, ReservationStatus.VIOLATION_TERMINATED);

    private final StatisticsRepository repository;
    private final Clock clock;

    public StatisticsService(StatisticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StatisticsViews.DailyMetric computeDaily(long labId, LocalDate date) {
        StatisticsRepository.Facts facts = repository.loadFacts(labId, null, date, date);
        requireLab(facts, labId);
        DayTotals totals = calculate(facts, date, date, false).get(new LabDate(labId, date));
        return dailyMetric(labId, date, totals);
    }

    @Transactional(rollbackFor = Exception.class)
    public StatisticsViews.DailyMetric refreshDaily(long labId, LocalDate date) {
        StatisticsViews.DailyMetric metric = computeDaily(labId, date);
        repository.upsertDaily(metric);
        return metric;
    }

    @Transactional(rollbackFor = Exception.class)
    public void refreshDailyBatch(List<StatisticsViews.LabDateTarget> targets) {
        List<StatisticsViews.LabDateTarget> ordered = targets.stream()
                .sorted(java.util.Comparator.comparing(StatisticsViews.LabDateTarget::date)
                        .thenComparingLong(StatisticsViews.LabDateTarget::labId))
                .toList();
        int index = 0;
        while (index < ordered.size()) {
            LocalDate start = ordered.get(index).date();
            LocalDate chunkLimit = start.plusDays(30);
            LocalDate end = start;
            List<StatisticsViews.LabDateTarget> chunk = new ArrayList<>();
            while (index < ordered.size() && !ordered.get(index).date().isAfter(chunkLimit)) {
                end = ordered.get(index).date();
                chunk.add(ordered.get(index++));
            }
            try {
                processDailyChunk(chunk, start, end);
            } catch (BusinessException exception) {
                if (exception.getStatus() != HttpStatus.PAYLOAD_TOO_LARGE) {
                    throw exception;
                }
                org.slf4j.LoggerFactory.getLogger(StatisticsService.class).warn(
                        "日统计分块超限跳过: start={} end={} targets={}",
                        start, end, chunk.size());
            }
        }
    }

    private void processDailyChunk(
            List<StatisticsViews.LabDateTarget> targets, LocalDate start, LocalDate end) {
        StatisticsRepository.Facts facts = repository.loadFacts(null, null, start, end);
        List<StatisticsViews.DailyMetric> metrics = new ArrayList<>();
        for (StatisticsViews.LabDateTarget target : targets) {
            try {
                metrics.add(dailyMetric(target.labId(), target.date(),
                        calculateDay(facts, target.labId(), target.date())));
            } catch (RuntimeException exception) {
                org.slf4j.LoggerFactory.getLogger(StatisticsService.class).warn(
                        "日统计单实验室计算失败: labId={} date={} errorType={}",
                        target.labId(), target.date(), exception.getClass().getSimpleName());
            }
        }
        if (metrics.isEmpty()) {
            return;
        }
        try {
            repository.upsertDailyBatch(metrics);
        } catch (org.springframework.dao.DataAccessException exception) {
            for (StatisticsViews.DailyMetric metric : metrics) {
                try {
                    repository.upsertDaily(metric);
                } catch (org.springframework.dao.DataAccessException singleFailure) {
                    org.slf4j.LoggerFactory.getLogger(StatisticsService.class).warn(
                            "日统计单实验室写入失败: labId={} date={} errorType={}",
                            metric.labId(), metric.date(), singleFailure.getClass().getSimpleName());
                }
            }
        }
    }

    private static StatisticsViews.DailyMetric dailyMetric(
            long labId, LocalDate date, DayTotals totals) {
        return new StatisticsViews.DailyMetric(
                labId, date, ratio(totals.plannedMinutes, totals.availableMinutes),
                ratio(totals.actualMinutes, totals.availableMinutes),
                ratio(totals.noShowCount, totals.reservationCount), totals.reservationCount,
                totals.availableSeatMinutes());
    }

    @Transactional(readOnly = true)
    public StatisticsViews.Dashboard dashboard(
            long userId, boolean administrator, LocalDate requestedDate) {
        LocalDate date = requestedDate == null ? LocalDate.now(clock) : requestedDate;
        LocalDate start = date.minusDays(6);
        Long ownerId = administrator ? null : userId;
        StatisticsRepository.Facts facts = repository.loadFacts(null, ownerId, start, date);
        Map<LabDate, DayTotals> calculated = calculate(facts, start, date, ownerId != null);
        Map<LocalDate, StatisticsRepository.CountRow> counts =
                repository.countReservations(ownerId, start, date);
        List<StatisticsViews.TrendDay> trend = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate current = start.plusDays(offset);
            StatisticsRepository.CountRow count = counts.getOrDefault(
                    current, StatisticsRepository.CountRow.empty());
            DayTotals total = aggregate(calculated, current);
            trend.add(new StatisticsViews.TrendDay(
                    current, count.reservationCount(), count.noShowCount(),
                    ratio(total.plannedMinutes, total.availableMinutes),
                    ratio(total.actualMinutes, total.availableMinutes),
                    ratio(count.noShowCount(), count.reservationCount())));
        }
        StatisticsRepository.CountRow selected = counts.getOrDefault(
                date, StatisticsRepository.CountRow.empty());
        DayTotals selectedTotals = aggregate(calculated, date);
        return new StatisticsViews.Dashboard(
                date, repository.countLabs(ownerId, date), selected.reservationCount(),
                selected.pendingScheduleCount(), selected.inUseCount(), selected.approvedCount(),
                selected.completedCount(), selected.noShowCount(), selected.cancelledCount(),
                ratio(selectedTotals.plannedMinutes, selectedTotals.availableMinutes),
                ratio(selectedTotals.actualMinutes, selectedTotals.availableMinutes),
                ratio(selected.noShowCount(), selected.reservationCount()),
                repository.creditScore(userId), List.copyOf(trend));
    }

    @Transactional(readOnly = true)
    public List<StatisticsViews.HeatmapDay> heatmap(
            long labId, LocalDate start, LocalDate end) {
        validateRange(start, end);
        StatisticsRepository.Facts facts = repository.loadFacts(labId, null, start, end);
        requireLab(facts, labId);
        Map<LabDate, DayTotals> calculated = calculate(facts, start, end, false);
        List<StatisticsViews.HeatmapDay> days = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            DayTotals totals = calculated.get(new LabDate(labId, date));
            List<StatisticsViews.HeatmapSlot> slots = new ArrayList<>();
            for (int slot = 0; slot < 48; slot++) {
                double denominator = totals.openSeatCounts[slot] * 30.0;
                slots.add(new StatisticsViews.HeatmapSlot(
                        slot, time(slot * 30), time((slot + 1) * 30),
                        ratio(totals.plannedSlots[slot], denominator),
                        ratio(totals.actualSlots[slot], denominator)));
            }
            days.add(new StatisticsViews.HeatmapDay(labId, date, List.copyOf(slots)));
        }
        return List.copyOf(days);
    }

    private Map<LabDate, DayTotals> calculate(
            StatisticsRepository.Facts facts, LocalDate start, LocalDate end,
            boolean dailyOwnerScope) {
        Set<LabDate> reservedLabDates = dailyOwnerScope
                ? facts.reservations().stream()
                        .map(row -> new LabDate(row.labId(), row.date()))
                        .collect(java.util.stream.Collectors.toSet()) : null;
        Map<LabDate, DayTotals> totals = new HashMap<>();
        for (long labId : facts.labIds()) {
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                LabDate key = new LabDate(labId, date);
                if (reservedLabDates != null && !reservedLabDates.contains(key)) {
                    continue;
                }
                totals.put(key, calculateDay(facts, labId, date));
            }
        }
        return totals;
    }

    private DayTotals calculateDay(
            StatisticsRepository.Facts facts, long labId, LocalDate date) {
        Set<Long> assignedSeatIds = facts.assignments().values().stream()
                .flatMap(List::stream)
                .filter(row -> row.labId() == labId && row.date().equals(date))
                .map(StatisticsRepository.AssignmentRow::seatId)
                .collect(java.util.stream.Collectors.toSet());
        int availableSeats = (int) facts.seats().getOrDefault(labId, List.of()).stream()
                .filter(seat -> seat.active() || assignedSeatIds.contains(seat.seatId()))
                .count();
        StatisticsRepository.RuleRow rule = facts.rules().get(
                new StatisticsRepository.LabDay(labId, date.getDayOfWeek().getValue()));
        Long baseline = facts.dailyBaselines().get(
                new StatisticsRepository.LabStatDate(labId, date));
        DayTotals day = new DayTotals(availableSeats, openMask(rule), baseline);
        LocalDateTime now = LocalDateTime.now(clock);
        for (StatisticsRepository.ReservationRow reservation : facts.reservations()) {
            if (reservation.labId() != labId || !reservation.date().equals(date)) {
                continue;
            }
            day.reservationCount++;
            if (reservation.status() == ReservationStatus.NO_SHOW) {
                day.noShowCount++;
            }
            if (!PLANNED_STATUSES.contains(reservation.status())) {
                continue;
            }
            List<StatisticsRepository.AssignmentRow> assignments =
                    facts.assignments().getOrDefault(reservation.reservationId(), List.of());
            if (reservation.mode() == ReservationMode.ROOM) {
                if (assignments.isEmpty()) {
                    day.apply(assignmentMask(reservation), reservation, now, day.roomSeatUnits());
                } else {
                    for (StatisticsRepository.AssignmentRow assignment : assignments) {
                        day.apply(assignment.mask(), reservation, now, 1);
                    }
                }
                continue;
            }
            for (StatisticsRepository.AssignmentRow assignment : assignments) {
                day.apply(assignment.mask(), reservation, now, 1);
            }
        }
        return day;
    }

    private static DayTotals aggregate(Map<LabDate, DayTotals> calculated, LocalDate date) {
        DayTotals aggregate = new DayTotals(0, 0, null);
        calculated.forEach((key, value) -> {
            if (key.date().equals(date)) {
                aggregate.plannedMinutes += value.plannedMinutes;
                aggregate.actualMinutes += value.actualMinutes;
                aggregate.availableMinutes += value.availableMinutes;
            }
        });
        return aggregate;
    }

    private static long assignmentMask(StatisticsRepository.ReservationRow reservation) {
        long mask = 0;
        for (int slot = 0; slot < 48; slot++) {
            LocalDateTime slotStart = reservation.date().atStartOfDay().plusMinutes(slot * 30L);
            LocalDateTime slotEnd = slotStart.plusMinutes(30);
            if (reservation.startTime().isBefore(slotEnd)
                    && reservation.endTime().isAfter(slotStart)) {
                mask |= 1L << slot;
            }
        }
        return mask;
    }

    private static long openMask(StatisticsRepository.RuleRow rule) {
        if (rule == null) {
            return 0;
        }
        int start = DayTimes.minuteOfDay(DayTimes.parseDatabaseTime(rule.openTime()), false) / 30;
        int end = DayTimes.minuteOfDay(DayTimes.parseDatabaseTime(rule.closeTime()), true) / 30;
        long mask = 0;
        for (int slot = start; slot < end; slot++) {
            mask |= 1L << slot;
        }
        return mask;
    }

    private static void requireLab(StatisticsRepository.Facts facts, long labId) {
        if (!facts.labIds().contains(labId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "实验室不存在");
        }
    }

    static void validateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)
                || ChronoUnit.DAYS.between(start, end) >= 31) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "统计日期范围必须为1到31天");
        }
    }

    private static double ratio(double numerator, double denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private static String time(int minuteOfDay) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    private record LabDate(long labId, LocalDate date) {
    }

    private static final class DayTotals {
        private final double[] openSeatCounts = new double[48];
        private final double[] plannedSlots = new double[48];
        private final double[] actualSlots = new double[48];
        private double plannedMinutes;
        private double actualMinutes;
        private double availableMinutes;
        private long reservationCount;
        private long noShowCount;
        private int openSlotCount;

        private DayTotals(int availableSeats, long openMask, Long baselineMinutes) {
            for (int slot = 0; slot < 48; slot++) {
                if ((openMask & (1L << slot)) != 0) {
                    openSlotCount++;
                }
            }
            availableMinutes = baselineMinutes == null
                    ? availableSeats * openSlotCount * 30.0 : baselineMinutes;
            for (int slot = 0; slot < 48; slot++) {
                if ((openMask & (1L << slot)) != 0) {
                    openSeatCounts[slot] = baselineMinutes == null
                            ? availableSeats : roomSeatUnits();
                }
            }
        }

        private long availableSeatMinutes() {
            return Math.round(availableMinutes);
        }

        private double roomSeatUnits() {
            return openSlotCount == 0 ? 0 : availableMinutes / (openSlotCount * 30.0);
        }

        private void apply(
                long originalMask, StatisticsRepository.ReservationRow reservation,
                LocalDateTime now, double weight) {
            if (reservation.checkinTime() == null) {
                addPlannedOnly(originalMask, weight);
                return;
            }
            LocalDateTime actualEnd = reservation.checkoutTime() != null
                    ? reservation.checkoutTime()
                    : now.isBefore(reservation.endTime()) ? now : reservation.endTime();
            for (int slot = 0; slot < 48; slot++) {
                if ((originalMask & (1L << slot)) == 0 || openSeatCounts[slot] == 0) {
                    continue;
                }
                plannedMinutes += 30 * weight;
                plannedSlots[slot] += 30 * weight;
                LocalDateTime slotStart = reservation.date().atStartOfDay().plusMinutes(slot * 30L);
                LocalDateTime slotEnd = slotStart.plusMinutes(30);
                LocalDateTime start = reservation.checkinTime().isAfter(slotStart)
                        ? reservation.checkinTime() : slotStart;
                LocalDateTime end = actualEnd.isBefore(slotEnd) ? actualEnd : slotEnd;
                if (end.isAfter(start)) {
                    double minutes = Duration.between(start, end).toMillis() / 60000.0;
                    actualMinutes += minutes * weight;
                    actualSlots[slot] += minutes * weight;
                }
            }
        }

        private void addPlannedOnly(long originalMask, double weight) {
            for (int slot = 0; slot < 48; slot++) {
                if ((originalMask & (1L << slot)) != 0 && openSeatCounts[slot] > 0) {
                    plannedMinutes += 30 * weight;
                    plannedSlots[slot] += 30 * weight;
                }
            }
        }
    }
}
