package com.campus.lab.schedule;

import com.campus.lab.reservation.TimeSlotMask;

public record ScheduleAssignment(
        long resvId,
        long labId,
        int startSlot,
        int endSlot) {

    public ScheduleAssignment {
        TimeSlotMask.of(startSlot, endSlot);
    }

    public TimeSlotMask timeMask() {
        return TimeSlotMask.of(startSlot, endSlot);
    }
}
