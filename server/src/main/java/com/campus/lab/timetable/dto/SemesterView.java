package com.campus.lab.timetable.dto;

import java.time.LocalDate;

public record SemesterView(
        long semesterId,
        String code,
        String name,
        LocalDate week1Monday,
        int weekCount,
        String status) {
}
