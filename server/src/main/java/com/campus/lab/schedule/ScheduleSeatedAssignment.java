package com.campus.lab.schedule;

import com.campus.lab.reservation.SeatAssignment;
import com.campus.lab.reservation.TimeSlotMask;
import java.util.List;

public record ScheduleSeatedAssignment(
        long resvId,
        long labId,
        int startSlot,
        int endSlot,
        List<SeatAssignment> candidateSeatAllocations) {

    public ScheduleSeatedAssignment {
        TimeSlotMask.of(startSlot, endSlot);
        candidateSeatAllocations = List.copyOf(candidateSeatAllocations);
    }

    public TimeSlotMask timeMask() {
        return TimeSlotMask.of(startSlot, endSlot);
    }
}
