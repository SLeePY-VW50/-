package com.campus.lab.credit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateAppealRequest(
        @NotNull @Positive Long violationId,
        @NotBlank @Size(max = 1000) String reason) {
}
