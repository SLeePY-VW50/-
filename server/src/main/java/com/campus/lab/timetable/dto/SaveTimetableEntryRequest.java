package com.campus.lab.timetable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SaveTimetableEntryRequest(
        @NotNull Long semesterId,
        @NotNull Long labId,
        @NotBlank @Size(max = 128) String courseName,
        @NotBlank @Size(max = 32) String teacherAccount,
        @NotBlank @Size(max = 128) String className,
        @NotNull Integer dayOfWeek,
        @NotNull Integer startPeriod,
        @NotNull Integer endPeriod,
        @NotNull Integer weekStart,
        @NotNull Integer weekEnd,
        List<@NotBlank String> studentAccounts) {
}
