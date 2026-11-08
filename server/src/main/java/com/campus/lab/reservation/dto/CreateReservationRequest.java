package com.campus.lab.reservation.dto;

import com.campus.lab.reservation.AllocationMode;
import com.campus.lab.reservation.ReservationMode;
import java.time.LocalDate;

public record CreateReservationRequest(
        AllocationMode allocationMode,
        Long labId,
        LocalDate date,
        String startTime,
        String endTime,
        String windowStart,
        String windowEnd,
        Integer durationMinutes,
        ReservationMode reservationMode,
        Integer attendeeCount,
        Integer seatCount,
        String purpose,
        ReservationPreferences preferences) {
}
