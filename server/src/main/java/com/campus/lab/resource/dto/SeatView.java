package com.campus.lab.resource.dto;

import com.campus.lab.reservation.ResourceStatus;

public record SeatView(
        long seatId,
        long labId,
        String seatCode,
        String zoneCode,
        int seatIndex,
        String specLevel,
        boolean nearWindow,
        ResourceStatus status) {
}
