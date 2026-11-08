package com.campus.lab.reservation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

public record BatchReservationView(
        @JsonIgnore long userId,
        long batchId,
        String batchNo,
        String courseName,
        long labId,
        String labName,
        int attendeeCount,
        String purpose,
        List<ReservationView> items) {

    public BatchReservationView {
        items = List.copyOf(items);
    }
}
