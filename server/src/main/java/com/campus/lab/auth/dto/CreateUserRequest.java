package com.campus.lab.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 32) String account,
        @NotBlank @Size(max = 64) String name,
        @NotBlank String password,
        @NotBlank String role) {
}
