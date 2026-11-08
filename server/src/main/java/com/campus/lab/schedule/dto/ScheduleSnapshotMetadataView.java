package com.campus.lab.schedule.dto;

import java.time.LocalDate;

public record ScheduleSnapshotMetadataView(
        String datasetId, LocalDate requestDate, int demandCount, int resourceCount) {
}
