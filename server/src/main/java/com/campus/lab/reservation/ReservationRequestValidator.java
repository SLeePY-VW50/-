package com.campus.lab.reservation;

import com.campus.lab.common.BusinessException;
import com.campus.lab.common.DayTimes;
import com.campus.lab.period.PeriodCatalog;
import com.campus.lab.period.PeriodSchedule;
import com.campus.lab.reservation.dto.CreateReservationRequest;
import com.campus.lab.reservation.dto.ReservationPreferences;
import java.time.LocalTime;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ReservationRequestValidator {

    private static final Set<String> SPEC_LEVELS = Set.of("STANDARD", "HIGH", "GPU");

    private final PeriodCatalog periodCatalog;

    public ReservationRequestValidator(PeriodCatalog periodCatalog) {
        this.periodCatalog = periodCatalog;
    }

    public NormalizedReservation normalize(CreateReservationRequest request) {
        if (request == null || request.allocationMode() == null) {
            throw badRequest("预约分配方式不能为空");
        }
        return switch (request.allocationMode()) {
            case FIXED -> normalizeFixed(request);
            case SCHEDULABLE -> normalizeSchedulable(request);
        };
    }

    private NormalizedReservation normalizeFixed(CreateReservationRequest request) {
        requireCommonFields(request);
        if (request.labId() == null) {
            throw badRequest("固定预约必须指定实验室");
        }
        LocalTime start = parseTime(request.startTime(), false);
        LocalTime end = parseTime(request.endTime(), true);
        PeriodSchedule schedule = scheduleFor(request.labId());
        TimeSlotMask mask = maskOf(schedule, start, end);
        validateSeatCount(request);
        ReservationPreferences preferences = preferences(request);
        if (!preferences.allowedLabIds().isEmpty()
                && !preferences.allowedLabIds().contains(request.labId())) {
            throw badRequest("指定实验室不在allowedLabIds候选范围内");
        }
        int minutes = DayTimes.minuteOfDay(end, true) - DayTimes.minuteOfDay(start, false);
        return new NormalizedReservation(
                request.allocationMode(), request.labId(), request.date(), start, end, minutes,
                mask.periodCount(), request.reservationMode(), request.attendeeCount(),
                request.seatCount(), request.purpose().trim(), preferences, mask);
    }

    private NormalizedReservation normalizeSchedulable(CreateReservationRequest request) {
        requireCommonFields(request);
        Integer periods = request.durationPeriods();
        if (periods == null || periods < 1 || periods > TimeSlotMask.MAX_BITS) {
            throw badRequest("可调度预约节数必须为正整数");
        }
        LocalTime start = parseTime(request.windowStart(), false);
        LocalTime end = parseTime(request.windowEnd(), true);
        PeriodSchedule schedule = request.labId() == null
                ? periodCatalog.defaultSchedule() : scheduleFor(request.labId());
        if (schedule.indexOfStart(start) < 0 || schedule.indexOfEnd(end) < 0
                || !end.isAfter(start)) {
            throw badRequest("可调度请求窗口必须落在节次的开始和结束");
        }
        if (periods > schedule.between(start, end).periodCount()) {
            throw badRequest("预约节数不能超过可调度请求窗口");
        }
        validateSeatCount(request);
        ReservationPreferences preferences = preferences(request);
        if (preferences.minSpecLevel() != null && !SPEC_LEVELS.contains(preferences.minSpecLevel())) {
            throw badRequest("未知最低配置等级");
        }
        if (request.labId() != null && !preferences.allowedLabIds().isEmpty()
                && !preferences.allowedLabIds().contains(request.labId())) {
            throw badRequest("指定实验室不在allowedLabIds候选范围内");
        }
        Integer storedSeatCount = request.reservationMode() == ReservationMode.SEAT
                ? request.seatCount() : null;
        return new NormalizedReservation(
                request.allocationMode(), request.labId(), request.date(), start, end,
                periods * 45, periods, request.reservationMode(), request.attendeeCount(),
                storedSeatCount, request.purpose().trim(), preferences, null);
    }

    private PeriodSchedule scheduleFor(long labId) {
        try {
            return periodCatalog.forLab(labId);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    private static TimeSlotMask maskOf(PeriodSchedule schedule, LocalTime start, LocalTime end) {
        try {
            return schedule.between(start, end);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    private static void requireCommonFields(CreateReservationRequest request) {
        if (request.date() == null || request.reservationMode() == null
                || request.attendeeCount() == null || request.attendeeCount() <= 0
                || request.purpose() == null || request.purpose().isBlank()
                || request.purpose().length() > 500) {
            throw badRequest("预约参数不完整或无效");
        }
    }

    private static void validateSeatCount(CreateReservationRequest request) {
        Integer seatCount = request.seatCount();
        if (request.reservationMode() == ReservationMode.SEAT
                && (seatCount == null || seatCount <= 0
                || !seatCount.equals(request.attendeeCount()))) {
            throw badRequest("SEAT预约的seatCount必须等于attendeeCount且大于0");
        }
    }

    private static ReservationPreferences preferences(CreateReservationRequest request) {
        return request.preferences() == null ? ReservationPreferences.empty() : request.preferences();
    }

    private static LocalTime parseTime(String value, boolean endOfDayAllowed) {
        try {
            return DayTimes.parse(value, endOfDayAllowed);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
