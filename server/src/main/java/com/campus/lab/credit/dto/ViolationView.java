package com.campus.lab.credit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViolationView(
        long violationId,
        long resvId,
        String violationType,
        int deductPoints,
        String status,
        LocalDateTime createdAt,
        LocalDateTime revokedAt,
        Long appealId,
        String appealStatus) {
}
