package com.campus.lab.schedule.dto;

import com.campus.lab.schedule.ScheduleTaskStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ScheduleBatchDetailView(
        String batchNo,
        String datasetId,
        LocalDate requestDate,
        long primaryTaskId,
        ScheduleTaskStatus primaryStatus,
        LocalDateTime createdAt,
        LocalDateTime publishedAt,
        List<ScheduleTaskView> tasks) {

    public ScheduleBatchDetailView {
        tasks = List.copyOf(tasks);
    }
}
