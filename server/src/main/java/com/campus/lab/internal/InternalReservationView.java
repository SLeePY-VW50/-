package com.campus.lab.internal;

import com.campus.lab.reservation.AllocationMode;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationStatus;
import com.campus.lab.reservation.dto.ReservationSeatView;
import com.campus.lab.reservation.dto.ReservationView;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record InternalReservationView(
        long resvId,
        Long labId,
        String labName,
        LocalDate date,
        LocalDateTime startTime,
        LocalDateTime endTime,
        AllocationMode allocationMode,
        ReservationMode reservationMode,
        ReservationStatus status,
        Integer seatCount,
        List<ReservationSeatView> seats,
        LocalDateTime createdAt) {

    static InternalReservationView from(ReservationView view) {
        return new InternalReservationView(
                view.resvId(), view.labId(), view.labName(), view.date(), view.startTime(), view.endTime(),
                view.allocationMode(), view.reservationMode(), view.status(), view.seatCount(),
                view.seats(), view.createdAt());
    }
}
