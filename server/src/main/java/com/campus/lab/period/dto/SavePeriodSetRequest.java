package com.campus.lab.period.dto;

import com.campus.lab.period.PeriodSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SavePeriodSetRequest(
        @NotBlank String code,
        @NotBlank String name,
        boolean makeDefault,
        @NotEmpty List<@Valid PeriodRequest> periods) {

    public record PeriodRequest(
            @NotNull Integer periodNo,
            @NotBlank String start,
            @NotBlank String end,
            @NotNull PeriodSession session) {
    }
}
