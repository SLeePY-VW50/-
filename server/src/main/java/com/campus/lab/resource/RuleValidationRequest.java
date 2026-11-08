package com.campus.lab.resource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record RuleValidationRequest(
        long labId,
        LocalDate requestDate,
        LocalTime startTime,
        LocalTime endTime,
        List<Long> seatIds) {

    public RuleValidationRequest {
        seatIds = List.copyOf(seatIds);
    }
}
