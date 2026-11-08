package com.campus.lab.reservation.dto;

import java.util.List;

public record BatchReservationRequest(
        String courseName,
        Long labId,
        Integer attendeeCount,
        String purpose,
        List<BatchItemRequest> items) {
}
