package com.campus.lab.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 32) String account,
        @NotBlank @Size(max = 64) String name,
        @NotBlank String password) {
}
