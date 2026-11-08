package com.campus.lab.reservation;

import java.util.List;

public record NormalizedBatch(
        String courseName,
        long labId,
        int attendeeCount,
        String purpose,
        List<NormalizedReservation> items) {

    public NormalizedBatch {
        items = List.copyOf(items);
    }
}
