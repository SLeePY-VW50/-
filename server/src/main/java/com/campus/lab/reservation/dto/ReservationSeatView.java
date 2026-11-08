package com.campus.lab.reservation.dto;

public record ReservationSeatView(
        long seatId,
        String seatCode,
        String zoneCode,
        int seatIndex,
        String specLevel,
        String mask,
        boolean zoneMismatch,
        boolean nearWindowMismatch) {
}
