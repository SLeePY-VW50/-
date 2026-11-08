package com.campus.lab.resource;

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
                valueOrGlobal(laboratoryRule.minDurationMinutes(), global.minDurationMinutes()),
                valueOrGlobal(laboratoryRule.maxDurationMinutes(), global.maxDurationMinutes()),
                valueOrGlobal(laboratoryRule.advanceMinDays(), global.advanceMinDays()),
                valueOrGlobal(laboratoryRule.advanceMaxDays(), global.advanceMaxDays()));
    }

    public void validate(
            RuleValidationRequest request,
            ResourceStatus laboratoryStatus,
            EffectiveTimeRule rule,
            List<SeatSnapshot> laboratorySeats) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(laboratorySeats, "laboratorySeats");
        if (laboratoryStatus != ResourceStatus.ACTIVE) {
            throw new IllegalArgumentException("实验室当前不可预约");
        }
        if (request.labId() != rule.labId()
                || request.requestDate().getDayOfWeek().getValue() != rule.dayOfWeek()) {
            throw new IllegalArgumentException("实验室当日没有适用开放规则");
        }
        long advanceDays = ChronoUnit.DAYS.between(LocalDate.now(clock), request.requestDate());
        if (advanceDays < rule.advanceMinDays() || advanceDays > rule.advanceMaxDays()) {
            throw new IllegalArgumentException("请求日期不在允许的提前预约范围内");
        }

        TimeSlotMask requestMask = TimeSlotMask.between(request.startTime(), request.endTime());
        int durationMinutes = (requestMask.endSlot() - requestMask.startSlot()) * 30;
        if (durationMinutes < rule.minDurationMinutes()
                || durationMinutes > rule.maxDurationMinutes()) {
            throw new IllegalArgumentException("预约时长不在有效规则范围内");
        }
        int startMinute = minuteOfDay(request.startTime());
        int endMinute = com.campus.lab.common.DayTimes.minuteOfDay(request.endTime(), true);
        int closeMinute = com.campus.lab.common.DayTimes.minuteOfDay(rule.closeTime(), true);
        if (startMinute < minuteOfDay(rule.openTime()) || endMinute > closeMinute) {
            throw new IllegalArgumentException("预约时间超出实验室开放时间");
        }

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
            int durationMinutes,
            ResourceStatus laboratoryStatus,
            EffectiveTimeRule rule) {
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        Objects.requireNonNull(rule, "rule");
        if (laboratoryStatus != ResourceStatus.ACTIVE) {
            throw new IllegalArgumentException("实验室当前不可预约");
        }
        if (labId != rule.labId() || requestDate.getDayOfWeek().getValue() != rule.dayOfWeek()) {
            throw new IllegalArgumentException("实验室当日没有适用开放规则");
        }
        long advanceDays = ChronoUnit.DAYS.between(LocalDate.now(clock), requestDate);
        if (advanceDays < rule.advanceMinDays() || advanceDays > rule.advanceMaxDays()) {
            throw new IllegalArgumentException("请求日期不在允许的提前预约范围内");
        }
        if (durationMinutes < rule.minDurationMinutes() || durationMinutes > rule.maxDurationMinutes()) {
            throw new IllegalArgumentException("预约时长不在有效规则范围内");
        }
        int closeMinute = com.campus.lab.common.DayTimes.minuteOfDay(rule.closeTime(), true);
        int windowEndMinute = com.campus.lab.common.DayTimes.minuteOfDay(windowEnd, true);
        int earliestStart = Math.max(minuteOfDay(windowStart), minuteOfDay(rule.openTime()));
        int latestStart = Math.min(windowEndMinute, closeMinute) - durationMinutes;
        if (latestStart < earliestStart) {
            throw new IllegalArgumentException("请求窗口与开放时间无法容纳预约时长");
        }
    }

    public static void validateDurationRange(int minDurationMinutes, int maxDurationMinutes) {
        if (minDurationMinutes % 30 != 0 || maxDurationMinutes % 30 != 0
                || minDurationMinutes < 30
                || minDurationMinutes > maxDurationMinutes
                || maxDurationMinutes > 240) {
            throw new IllegalArgumentException(
                    "有效预约时长必须为30的倍数，且满足30<=min<=max<=240");
        }
    }

    private static int valueOrGlobal(Integer laboratoryValue, int globalValue) {
        return laboratoryValue == null ? globalValue : laboratoryValue;
    }

    private static int minuteOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
