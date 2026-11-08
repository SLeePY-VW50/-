package com.campus.lab.schedule;

import java.util.Objects;

public final class GreedyEngine implements ScheduleEngine {

    @Override
    public AlgorithmType algorithmType() {
        return AlgorithmType.GREEDY;
    }

    @Override
    public SchedulePlan schedule(ScheduleProblem problem, ScheduleParameters parameters) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(parameters, "parameters");
        return DeterministicScheduler.greedy(CandidateCatalog.create(problem), parameters);
    }
}
