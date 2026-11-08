package com.campus.lab.schedule;

public interface ScheduleEngine {

    AlgorithmType algorithmType();

    SchedulePlan schedule(ScheduleProblem problem, ScheduleParameters parameters);
}
