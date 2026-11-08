package com.campus.lab.resource.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateTerminalRequest(
        @NotNull @Positive Long labId,
        @NotBlank @Size(max = 100) String deviceName,
        @Size(max = 100) String model,
        @NotBlank @Size(max = 64) String terminalCode,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank @Size(min = 8, max = 72) String initialSecret) {
}
