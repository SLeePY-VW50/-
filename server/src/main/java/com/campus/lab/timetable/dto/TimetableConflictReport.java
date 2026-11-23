package com.campus.lab.timetable.dto;

import java.util.List;

public record TimetableConflictReport(List<TimetableConflictView> conflicts) {

    public TimetableConflictReport {
        conflicts = List.copyOf(conflicts);
    }
}
