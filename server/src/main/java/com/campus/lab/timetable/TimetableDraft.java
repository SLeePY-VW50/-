package com.campus.lab.timetable;

import com.campus.lab.reservation.TimeSlotMask;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record TimetableDraft(
        int rowNumber,
        long semesterId,
        long labId,
        String courseName,
        long teacherUserId,
        String className,
        int dayOfWeek,
        int startPeriod,
        int endPeriod,
        int weekStart,
        int weekEnd,
        List<Long> studentIds,
        List<Session> sessions) {

    public TimetableDraft {
        studentIds = List.copyOf(studentIds);
        sessions = List.copyOf(sessions);
    }

    public record Session(
            int weekNo, LocalDate date, TimeSlotMask mask, LocalTime start, LocalTime end) {
    }
}
