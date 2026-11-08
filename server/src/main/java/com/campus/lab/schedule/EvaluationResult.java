package com.campus.lab.schedule;

import java.util.Objects;

public record EvaluationResult(
        double assignmentSuccessRate,
        double averageSeatUtilization,
        double softPreferenceMismatchRate,
        double timeFragmentationRate,
        ScheduleTiming solveTime) {

    public EvaluationResult {
        Objects.requireNonNull(solveTime, "solveTime");
    }
}
