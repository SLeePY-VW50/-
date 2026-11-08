package com.campus.lab.credit.dto;

import com.campus.lab.credit.ReviewDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewAppealRequest(
        @NotNull ReviewDecision decision,
        @NotBlank @Size(max = 1000) String opinion) {
}
