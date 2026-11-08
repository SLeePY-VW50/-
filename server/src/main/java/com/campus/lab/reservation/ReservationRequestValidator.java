package com.campus.lab.reservation;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.dto.CreateReservationRequest;
import com.campus.lab.reservation.dto.ReservationPreferences;
import java.time.LocalTime;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ReservationRequestValidator {

    private static final Set<String> SPEC_LEVELS = Set.of("STANDARD", "HIGH", "GPU");

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
        TimeSlotMask mask;
        try {
            mask = TimeSlotMask.between(start, end);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
        validateSeatCount(request);
        ReservationPreferences preferences = preferences(request);
        if (!preferences.allowedLabIds().isEmpty()
                && !preferences.allowedLabIds().contains(request.labId())) {
            throw badRequest("指定实验室不在allowedLabIds候选范围内");
        }
        int duration = (mask.endSlot() - mask.startSlot()) * 30;
        return new NormalizedReservation(
                request.allocationMode(), request.labId(), request.date(), start, end, duration,
                request.reservationMode(), request.attendeeCount(), request.seatCount(),
                request.purpose().trim(), preferences, mask);
    }

    private NormalizedReservation normalizeSchedulable(CreateReservationRequest request) {
        requireCommonFields(request);
        if (request.durationMinutes() == null || request.durationMinutes() < 30
                || request.durationMinutes() > 240 || request.durationMinutes() % 30 != 0) {
            throw badRequest("可调度预约时长必须为30的倍数且满足30到240分钟");
        }
        LocalTime start = parseTime(request.windowStart(), false);
        LocalTime end = parseTime(request.windowEnd(), true);
        int startMinute = alignedMinute(start, false);
        int endMinute = alignedMinute(end, true);
        if (endMinute <= startMinute) {
            throw badRequest("可调度请求窗口无效");
        }
        if (request.durationMinutes() > endMinute - startMinute) {
            throw badRequest("预约时长不能超过可调度请求窗口");
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
                request.durationMinutes(), request.reservationMode(), request.attendeeCount(),
                storedSeatCount, request.purpose().trim(), preferences, null);
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

    private static int alignedMinute(LocalTime time, boolean end) {
        if (time.getSecond() != 0 || time.getNano() != 0
                || (time.getMinute() != 0 && time.getMinute() != 30)) {
            throw badRequest("时间必须按30分钟对齐，且秒和纳秒必须为0");
        }
        return com.campus.lab.common.DayTimes.minuteOfDay(time, end);
    }

    private static LocalTime parseTime(String value, boolean endOfDayAllowed) {
        try {
            return com.campus.lab.common.DayTimes.parse(value, endOfDayAllowed);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
