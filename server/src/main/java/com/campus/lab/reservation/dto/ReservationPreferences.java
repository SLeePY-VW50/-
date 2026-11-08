package com.campus.lab.reservation.dto;

import java.util.List;

public record ReservationPreferences(
        String zoneCode,
        Boolean nearWindow,
        String minSpecLevel,
        List<Long> allowedLabIds) {

    public ReservationPreferences {
        allowedLabIds = allowedLabIds == null ? List.of() : List.copyOf(allowedLabIds);
    }

    public static ReservationPreferences empty() {
        return new ReservationPreferences(null, null, null, List.of());
    }
}
