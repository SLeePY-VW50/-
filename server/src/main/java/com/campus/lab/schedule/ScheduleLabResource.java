package com.campus.lab.schedule;

import com.campus.lab.reservation.ResourceSnapshot;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatSnapshot;
import com.campus.lab.resource.EffectiveTimeRule;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record ScheduleLabResource(
        long labId,
        LocalDate date,
        ResourceStatus status,
        int capacity,
        long openMask,
        long roomMask,
        List<SeatSnapshot> seats,
        EffectiveTimeRule effectiveRule) {

    private static final long DAY_MASK = (1L << 48) - 1;

    public ScheduleLabResource {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(seats, "seats");
        seats = List.copyOf(seats);
        if (capacity <= 0) {
            throw new IllegalArgumentException("实验室容量必须大于0");
        }
        if ((openMask & ~DAY_MASK) != 0 || (roomMask & ~DAY_MASK) != 0
                || seats.stream().anyMatch(seat -> (seat.mask() & ~DAY_MASK) != 0)) {
            throw new IllegalArgumentException("时间掩码只能使用一天内的48个时间片");
        }
    }

    public ResourceSnapshot snapshot() {
        return new ResourceSnapshot(roomMask, seats);
    }
}
