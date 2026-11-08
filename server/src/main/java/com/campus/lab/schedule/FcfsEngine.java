package com.campus.lab.schedule;

import java.util.Objects;

public final class FcfsEngine implements ScheduleEngine {

    @Override
    public AlgorithmType algorithmType() {
        return AlgorithmType.FCFS;
    }

    @Override
    public SchedulePlan schedule(ScheduleProblem problem, ScheduleParameters parameters) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(parameters, "parameters");
        return DeterministicScheduler.fcfs(CandidateCatalog.create(problem));
    }
}
