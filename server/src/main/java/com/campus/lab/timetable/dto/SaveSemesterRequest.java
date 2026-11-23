package com.campus.lab.timetable.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record SaveSemesterRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 128) String name,
        @NotNull LocalDate week1Monday,
        @NotNull @Min(1) @Max(30) Integer weekCount) {
}
