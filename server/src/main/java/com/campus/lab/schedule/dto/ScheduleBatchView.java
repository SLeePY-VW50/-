package com.campus.lab.schedule.dto;

import com.campus.lab.schedule.ScheduleTaskStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ScheduleBatchView(
        String batchNo,
        String datasetId,
        LocalDate requestDate,
        long primaryTaskId,
        ScheduleTaskStatus primaryStatus,
        int taskCount,
        LocalDateTime createdAt,
        LocalDateTime publishedAt) {
}
