package com.campus.lab.reservation;

public record ReservationBatchSummary(
        long userId,
        long batchId,
        String batchNo,
        String courseName,
        long labId,
        String labName,
        String purpose) {
}
