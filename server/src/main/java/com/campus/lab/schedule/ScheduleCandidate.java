package com.campus.lab.schedule;

import com.campus.lab.reservation.TimeSlotMask;
import java.time.LocalDate;

record ScheduleCandidate(long labId, LocalDate date, int startSlot, int endSlot) {

    TimeSlotMask timeMask() {
        return TimeSlotMask.of(startSlot, endSlot);
    }

    ScheduleAssignment assignment(long resvId) {
        return new ScheduleAssignment(resvId, labId, startSlot, endSlot);
    }
}
