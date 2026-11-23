package com.campus.lab.period.dto;

import jakarta.validation.constraints.NotNull;

public record AssignPeriodSetRequest(@NotNull Long periodSetId) {
}
