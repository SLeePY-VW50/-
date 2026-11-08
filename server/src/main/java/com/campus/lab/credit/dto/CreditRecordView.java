package com.campus.lab.credit.dto;

import java.time.LocalDateTime;

public record CreditRecordView(
        long recordId,
        Long violationId,
        String changeType,
        int changeAmount,
        int balanceBefore,
        int balanceAfter,
        Long operatorId,
        LocalDateTime createdAt) {
}
