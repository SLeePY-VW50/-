package com.campus.lab.resource;

import com.campus.lab.period.PeriodSchedule;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatSnapshot;
import com.campus.lab.reservation.TimeSlotMask;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RuleValidator {

    private final Clock clock;

    public RuleValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EffectiveTimeRule merge(TimeRule laboratoryRule, ReservationRuleConfig global) {
        Objects.requireNonNull(laboratoryRule, "laboratoryRule");
        Objects.requireNonNull(global, "global");
        return new EffectiveTimeRule(
                laboratoryRule.ruleId(), laboratoryRule.labId(), laboratoryRule.dayOfWeek(),
                laboratoryRule.openTime(), laboratoryRule.closeTime(),
                laboratoryRule.openStartPeriod(), laboratoryRule.openEndPeriod(),
                valueOrGlobal(laboratoryRule.minPeriods(), global.minPeriods()),
                valueOrGlobal(laboratoryRule.maxPeriods(), global.maxPeriods()),
                valueOrGlobal(laboratoryRule.advanceMinDays(), global.advanceMinDays()),
                valueOrGlobal(laboratoryRule.advanceMaxDays(), global.advanceMaxDays()));
    }

    public void validate(
            RuleValidationRequest request,
            ResourceStatus laboratoryStatus,
            EffectiveTimeRule rule,
            List<SeatSnapshot> laboratorySeats,
            PeriodSchedule schedule) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(laboratorySeats, "laboratorySeats");
        Objects.requireNonNull(schedule, "schedule");
        requireActiveLab(laboratoryStatus);
        requireApplicableDay(request.labId(), request.requestDate(), rule);
        requireAdvance(request.requestDate(), rule);
        TimeSlotMask requestMask = covering(schedule, request.startTime(), request.endTime());
        requirePeriodCount(requestMask.periodCount(), rule);
        requireBookable(schedule, rule, requestMask);

        Map<Long, SeatSnapshot> seatsById = laboratorySeats.stream()
                .collect(Collectors.toMap(SeatSnapshot::seatId, Function.identity()));
        for (Long seatId : request.seatIds()) {
            SeatSnapshot seat = seatsById.get(seatId);
            if (seat == null) {
                throw new IllegalArgumentException("工位不存在或不属于该实验室");
            }
            if (seat.status() != ResourceStatus.ACTIVE) {
                throw new IllegalArgumentException("工位当前不可预约");
            }
        }
    }

    public void validateWindow(
            long labId,
            LocalDate requestDate,
            LocalTime windowStart,
            LocalTime windowEnd,
            int periodCount,
            ResourceStatus laboratoryStatus,
            EffectiveTimeRule rule,
            PeriodSchedule schedule) {
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(schedule, "schedule");
        requireActiveLab(laboratoryStatus);
        requireApplicableDay(labId, requestDate, rule);
        requireAdvance(requestDate, rule);
        requirePeriodCount(periodCount, rule);
        if (schedule.placements(
                minute(windowStart),
                com.campus.lab.common.DayTimes.minuteOfDay(windowEnd, true),
                periodCount,
                schedule.bookableMask(
                        rule.openTime(), rule.closeTime(), rule.openStartPeriod(), rule.openEndPeriod()))
                .isEmpty()) {
            throw new IllegalArgumentException("请求窗口与开放节次无法容纳预约节数");
        }
    }

    public static void validatePeriodRange(int minPeriods, int maxPeriods) {
        if (minPeriods < 1 || minPeriods > maxPeriods || maxPeriods > TimeSlotMask.MAX_BITS) {
            throw new IllegalArgumentException("有效预约节数必须满足1<=min<=max");
        }
    }

    private static TimeSlotMask covering(PeriodSchedule schedule, LocalTime start, LocalTime end) {
        try {
            return schedule.between(start, end);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("预约起止必须落在节次的开始和结束");
        }
    }

    private static void requireBookable(PeriodSchedule schedule, EffectiveTimeRule rule, TimeSlotMask mask) {
        long open = schedule.bookableMask(
                rule.openTime(), rule.closeTime(), rule.openStartPeriod(), rule.openEndPeriod());
        if ((open & mask.value()) != mask.value()) {
            throw new IllegalArgumentException("预约时间超出实验室开放节次");
        }
    }

    private static void requirePeriodCount(int periodCount, EffectiveTimeRule rule) {
        if (periodCount < rule.minPeriods() || periodCount > rule.maxPeriods()) {
            throw new IllegalArgumentException("预约节数不在有效规则范围内");
        }
    }

    private void requireAdvance(LocalDate requestDate, EffectiveTimeRule rule) {
        long advanceDays = ChronoUnit.DAYS.between(LocalDate.now(clock), requestDate);
        if (advanceDays < rule.advanceMinDays() || advanceDays > rule.advanceMaxDays()) {
            throw new IllegalArgumentException("请求日期不在允许的提前预约范围内");
        }
    }

    private static void requireApplicableDay(long labId, LocalDate requestDate, EffectiveTimeRule rule) {
        if (labId != rule.labId() || requestDate.getDayOfWeek().getValue() != rule.dayOfWeek()) {
            throw new IllegalArgumentException("实验室当日没有适用开放规则");
        }
    }

    private static void requireActiveLab(ResourceStatus laboratoryStatus) {
        if (laboratoryStatus != ResourceStatus.ACTIVE) {
            throw new IllegalArgumentException("实验室当前不可预约");
        }
    }

    private static int valueOrGlobal(Integer laboratoryValue, int globalValue) {
        return laboratoryValue == null ? globalValue : laboratoryValue;
    }

    private static int minute(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
