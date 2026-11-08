package com.campus.lab.reservation;

public record SeatAssignment(
        long seatId,
        String seatCode,
        int seatIndex,
        boolean zoneMismatch,
        boolean nearWindowMismatch) {
}
