package com.campus.lab.resource.dto;

import com.campus.lab.reservation.ResourceStatus;

public record SeatAvailabilityView(
        long seatId,
        String seatCode,
        String zoneCode,
        int seatIndex,
        String specLevel,
        boolean nearWindow,
        ResourceStatus status,
        String mask) {
}
