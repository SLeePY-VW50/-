package com.campus.lab.checkin.qr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record QrCheckinRequest(
        @NotNull Long reservationId,
        @NotBlank @Size(max = 2048) String content) {
}
