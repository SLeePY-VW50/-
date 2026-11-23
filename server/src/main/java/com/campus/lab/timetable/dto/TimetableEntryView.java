package com.campus.lab.timetable.dto;

import java.util.List;

public record TimetableEntryView(
        long entryId,
        long semesterId,
        String semesterCode,
        long labId,
        String labCode,
        String labName,
        String courseName,
        long teacherUserId,
        String teacherAccount,
        String teacherName,
        String className,
        int dayOfWeek,
        int startPeriod,
        int endPeriod,
        int weekStart,
        int weekEnd,
        String status,
        int sessionCount,
        List<RosterMemberView> roster) {

    public TimetableEntryView {
        roster = roster == null ? List.of() : List.copyOf(roster);
    }
}
