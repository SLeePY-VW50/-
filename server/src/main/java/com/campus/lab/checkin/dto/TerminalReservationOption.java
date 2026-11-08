package com.campus.lab.checkin.dto;

import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.dto.ReservationSeatView;
import com.campus.lab.reservation.dto.ReservationView;
import java.time.LocalDateTime;
import java.util.List;

public record TerminalReservationOption(
        long reservationId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        ReservationMode reservationMode,
        int attendeeCount,
        Integer seatCount,
        List<ReservationSeatView> seats) {

    public TerminalReservationOption {
        seats = List.copyOf(seats);
    }

    public static TerminalReservationOption from(ReservationView view) {
        return new TerminalReservationOption(
                view.resvId(), view.startTime(), view.endTime(), view.reservationMode(),
                view.attendeeCount(), view.seatCount(), view.seats());
    }
}
