package com.campus.lab.timetable.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record TimetableConflictView(
        int rowNumber,
        LocalDate date,
        int weekNo,
        int dayOfWeek,
        int startPeriod,
        int endPeriod,
        long reservationId,
        long userId,
        String account,
        String userName,
        String purpose,
        String reservationMode,
        String occupancySource,
        LocalDateTime startTime,
        LocalDateTime endTime) {
}
