package com.campus.lab.schedule;

import com.campus.lab.reservation.SeatAssignment;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class Evaluator {

    private final ScheduleSeatingService seatingService;

    public Evaluator() {
        this(new ScheduleSeatingService());
    }

    Evaluator(ScheduleSeatingService seatingService) {
        this.seatingService = seatingService;
    }

    public EvaluationResult solve(
            ScheduleProblem problem, ScheduleParameters parameters, ScheduleEngine engine) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(engine, "engine");
        long start = System.nanoTime();
        SchedulePlan firstStage = engine.schedule(problem, parameters);
        long phase1End = System.nanoTime();
        ScheduleSeatingPlan seated = seatingService.allocate(problem, firstStage);
        long phase2End = System.nanoTime();
        return evaluate(problem, seated, phase1End - start, phase2End - phase1End);
    }

    public EvaluationResult evaluate(
            ScheduleProblem problem, ScheduleSeatingPlan seated,
            long phase1Nanos, long phase2Nanos) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(seated, "seated");
        ScheduleTiming timing = new ScheduleTiming(phase1Nanos, phase2Nanos);
        Map<Long, ScheduleDemand> demands = problem.demands().stream()
                .collect(Collectors.toMap(ScheduleDemand::resvId, Function.identity()));
        ScheduleResourceState finalState = new ScheduleResourceState(problem);
        int mismatchCount = 0;
        int allocatedSeatCount = 0;
        for (ScheduleSeatedAssignment assignment : seated.assignments()) {
            ScheduleDemand demand = demands.get(assignment.resvId());
            if (demand == null) {
                throw new IllegalArgumentException("落座结果包含未知需求");
            }
            ScheduleCandidate candidate = new ScheduleCandidate(
                    assignment.labId(), demand.date(), assignment.startSlot(), assignment.endSlot());
            finalState.occupyActual(demand, candidate, assignment.candidateSeatAllocations());
            for (SeatAssignment seat : assignment.candidateSeatAllocations()) {
                allocatedSeatCount++;
                boolean zoneMismatch = demand.preferredZone() != null && seat.zoneMismatch();
                boolean nearWindowMismatch = demand.preferNearWindow() && seat.nearWindowMismatch();
                if (zoneMismatch || nearWindowMismatch) {
                    mismatchCount++;
                }
            }
        }
        double success = problem.demands().isEmpty()
                ? 0 : (double) seated.assignments().size() / problem.demands().size();
        double mismatch = allocatedSeatCount == 0 ? 0 : (double) mismatchCount / allocatedSeatCount;
        return new EvaluationResult(
                success, finalState.utilization(), mismatch, finalState.actualFragmentation(), timing);
    }
}
