package com.campus.lab.reservation.dto;

import com.campus.lab.reservation.AllocationMode;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ReservationView(
        @JsonIgnore long userId,
        long resvId,
        Long batchId,
        String batchNo,
        Long labId,
        String labName,
        LocalDate date,
        String windowStart,
        String windowEnd,
        int durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        AllocationMode allocationMode,
        ReservationMode reservationMode,
        ReservationStatus status,
        int attendeeCount,
        Integer seatCount,
        String purpose,
        JsonNode preferences,
        List<ReservationSeatView> seats,
        String checkinChannel,
        LocalDateTime checkinTime,
        String checkoutChannel,
        LocalDateTime checkoutTime,
        LocalDateTime createdAt) {

    public ReservationView {
        seats = List.copyOf(seats);
    }
}
