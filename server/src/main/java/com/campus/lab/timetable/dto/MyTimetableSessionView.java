package com.campus.lab.timetable.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MyTimetableSessionView(
        long sessionId,
        long entryId,
        long reservationId,
        long labId,
        String labName,
        String courseName,
        String className,
        LocalDate date,
        int weekNo,
        int startPeriod,
        int endPeriod,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String status,
        boolean checkedIn) {
}
