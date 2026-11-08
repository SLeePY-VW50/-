package com.campus.lab.reservation;

import com.campus.lab.reservation.dto.ReservationPreferences;
import java.time.LocalDate;
import java.time.LocalTime;

public record NormalizedReservation(
        AllocationMode allocationMode,
        Long labId,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        int durationMinutes,
        ReservationMode reservationMode,
        int attendeeCount,
        Integer seatCount,
        String purpose,
        ReservationPreferences preferences,
        TimeSlotMask timeMask) {
}
