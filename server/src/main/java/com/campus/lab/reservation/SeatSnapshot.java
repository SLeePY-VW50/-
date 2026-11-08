package com.campus.lab.reservation;

public record SeatSnapshot(
        long seatId,
        String seatCode,
        String zoneCode,
        int seatIndex,
        String specLevel,
        boolean nearWindow,
        ResourceStatus status,
        long mask) {
}
