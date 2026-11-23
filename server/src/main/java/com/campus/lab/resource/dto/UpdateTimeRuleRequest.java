package com.campus.lab.resource.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateTimeRuleRequest(
        @NotNull @Min(1) @Max(63) Integer openStartPeriod,
        @NotNull @Min(1) @Max(63) Integer openEndPeriod,
        @Min(1) @Max(63) Integer minPeriods,
        @Min(1) @Max(63) Integer maxPeriods,
        @Min(0) Integer advanceMinDays,
        @Min(0) Integer advanceMaxDays) {
}
