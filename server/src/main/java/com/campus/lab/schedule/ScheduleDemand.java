package com.campus.lab.schedule;

import com.campus.lab.reservation.ReservationMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

public record ScheduleDemand(
        long resvId,
        long userId,
        LocalDateTime createdAt,
        LocalDate date,
        int windowStart,
        int windowEnd,
        int durationMinutes,
        ReservationMode mode,
        int attendeeCount,
        int seatCount,
        int credit,
        String minSpecLevel,
        String preferredZone,
        boolean preferNearWindow,
        Set<Long> allowedLabIds) {

    public ScheduleDemand {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(allowedLabIds, "allowedLabIds");
        allowedLabIds = Set.copyOf(allowedLabIds);
        if (windowStart < 0 || windowEnd > 1440 || windowEnd <= windowStart
                || windowStart % 30 != 0 || windowEnd % 30 != 0) {
            throw new IllegalArgumentException("预约窗口必须按30分钟对齐且位于0..1440内");
        }
        if (durationMinutes < 30 || durationMinutes > 240 || durationMinutes % 30 != 0
                || durationMinutes > windowEnd - windowStart) {
            throw new IllegalArgumentException("预约时长必须为30..240内的30分钟倍数且不超过窗口");
        }
        if (attendeeCount <= 0 || seatCount < 0
                || (mode == ReservationMode.SEAT && seatCount == 0)
                || (mode == ReservationMode.ROOM && seatCount != 0)) {
            throw new IllegalArgumentException("人数或工位数量无效");
        }
    }
}
