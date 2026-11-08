package com.campus.lab.credit.dto;

import java.time.LocalDateTime;

public record AppealView(
        long appealId,
        long violationId,
        long userId,
        String reason,
        String status,
        Long reviewerId,
        String reviewOpinion,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt) {
}
