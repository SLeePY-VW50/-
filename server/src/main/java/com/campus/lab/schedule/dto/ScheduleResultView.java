package com.campus.lab.schedule.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

public record ScheduleResultView(
        long resultId,
        long resvId,
        Long assignedLabId,
        LocalDateTime assignedStartTime,
        LocalDateTime assignedEndTime,
        Double objectiveValue,
        JsonNode candidateSeatAllocations,
        boolean published,
        LocalDateTime publishedAt) {
}
