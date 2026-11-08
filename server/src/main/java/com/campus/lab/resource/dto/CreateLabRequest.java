package com.campus.lab.resource.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateLabRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 200) String location,
        @NotNull @Positive Integer capacity) {
}
