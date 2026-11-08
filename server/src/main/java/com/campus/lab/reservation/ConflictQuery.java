package com.campus.lab.reservation;

import java.util.Objects;

public record ConflictQuery(
        TimeSlotMask timeMask,
        ReservationMode mode,
        int seatCount,
        String minSpecLevel,
        String preferredZone,
        boolean preferNearWindow) {

    public ConflictQuery {
        Objects.requireNonNull(timeMask, "timeMask");
        Objects.requireNonNull(mode, "mode");
        if (seatCount < 0 || (mode == ReservationMode.SEAT && seatCount == 0)) {
            throw new IllegalArgumentException("工位预约数量必须大于0");
        }
    }
}
