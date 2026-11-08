package com.campus.lab.schedule.dto;

import com.campus.lab.schedule.AlgorithmType;
import com.campus.lab.schedule.ScheduleTaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ScheduleTaskView(
        long taskId,
        String batchNo,
        AlgorithmType algorithmType,
        boolean primary,
        String datasetId,
        ScheduleTaskStatus status,
        BigDecimal progress,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs,
        String errorMessage,
        Long randomSeed,
        int repeatIndex,
        JsonNode parameters,
        JsonNode runtimeEnvironment,
        ScheduleSnapshotMetadataView snapshotMetadata,
        ScheduleExperimentView experiment,
        List<ScheduleResultView> results,
        LocalDateTime publishedAt) {

    public ScheduleTaskView {
        results = List.copyOf(results);
    }
}
