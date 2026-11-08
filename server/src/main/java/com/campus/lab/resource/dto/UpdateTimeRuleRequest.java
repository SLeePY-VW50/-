package com.campus.lab.resource.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateTimeRuleRequest(
        @NotBlank String openTime,
        @NotBlank String closeTime,
        @Positive Integer minDurationMinutes,
        @Positive Integer maxDurationMinutes,
        @PositiveOrZero Integer advanceMinDays,
        @PositiveOrZero Integer advanceMaxDays) {
}
