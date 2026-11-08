package com.campus.lab.schedule.dto;

public record ScheduleExperimentView(
        double allocationSuccessRate,
        double averageSeatUtilization,
        double seatPreferenceMismatchRate,
        double timeFragmentationRate,
        Double seatAllocationFailureRate,
        long phaseOneDurationMs,
        long phaseTwoDurationMs,
        long totalDurationMs) {
}
