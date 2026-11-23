package com.campus.lab.schedule;

import com.campus.lab.period.PeriodSchedule;
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
        EffectiveTimeRule effectiveRule,
        PeriodSchedule periodSchedule) {

    public ScheduleLabResource {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(seats, "seats");
        Objects.requireNonNull(periodSchedule, "periodSchedule");
        seats = List.copyOf(seats);
        if (capacity <= 0) {
            throw new IllegalArgumentException("实验室容量必须大于0");
        }
        long dayMask = periodSchedule.slotCount() == 63
                ? Long.MAX_VALUE : (1L << periodSchedule.slotCount()) - 1L;
        if ((openMask & ~dayMask) != 0 || (roomMask & ~dayMask) != 0
                || seats.stream().anyMatch(seat -> (seat.mask() & ~dayMask) != 0)) {
            throw new IllegalArgumentException("时间掩码只能使用节次表中的节");
        }
    }

    public ResourceSnapshot snapshot() {
        return new ResourceSnapshot(roomMask, seats);
    }
}
