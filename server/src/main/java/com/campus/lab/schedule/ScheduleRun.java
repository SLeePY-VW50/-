package com.campus.lab.schedule;

public record ScheduleRun(
        EvaluationResult evaluation,
        ScheduleSeatingPlan seating,
        double seatAllocationFailureRate) {
}
