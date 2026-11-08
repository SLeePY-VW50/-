package com.campus.lab.reservation.dto;

import java.time.LocalDate;

public record BatchItemRequest(LocalDate date, String startTime, String endTime) {
}
