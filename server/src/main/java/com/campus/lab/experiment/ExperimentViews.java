package com.campus.lab.experiment;

import com.campus.lab.schedule.AlgorithmType;
import com.campus.lab.schedule.ScheduleTaskStatus;
import com.campus.lab.schedule.dto.ScheduleExperimentView;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

final class ExperimentViews {

    private ExperimentViews() {
    }

    record RunRequest(Integer dataScale, Long seed) {
    }

    record RunView(
            String batchNo, String datasetId, int dataScale, long seed, String status) {
    }

    record BatchView(
            String batchNo, String datasetId, int dataScale, long seed, ScheduleTaskStatus status,
            List<TaskView> tasks, GeneticSummary geneticSummary) {
    }

    record TaskView(
            long taskId, AlgorithmType algorithmType, boolean primary, ScheduleTaskStatus status,
            long randomSeed, int repeatIndex, JsonNode parameters, JsonNode runtimeEnvironment,
            JsonNode demandSnapshot, JsonNode resourceSnapshot,
            ScheduleExperimentView experiment) {
    }

    record GeneticSummary(
            Double allocationSuccessRateMean, Double allocationSuccessRateStandardDeviation,
            Double averageSeatUtilizationMean, Double averageSeatUtilizationStandardDeviation,
            Double seatPreferenceMismatchRateMean,
            Double seatPreferenceMismatchRateStandardDeviation,
            Double timeFragmentationRateMean, Double timeFragmentationRateStandardDeviation,
            Double seatAllocationFailureRateMean,
            Double seatAllocationFailureRateStandardDeviation) {

        static GeneticSummary incomplete() {
            return new GeneticSummary(
                    null, null, null, null, null, null, null, null, null, null);
        }
    }
}
