package com.campus.lab.schedule;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ScheduleAlgorithmExecutor {

    private final Map<AlgorithmType, ScheduleEngine> engines = Map.of(
            AlgorithmType.FCFS, new FcfsEngine(),
            AlgorithmType.GREEDY, new GreedyEngine(),
            AlgorithmType.GENETIC, new GeneticEngine());
    private final ScheduleSeatingService seatingService = new ScheduleSeatingService();
    private final Evaluator evaluator = new Evaluator();

    public ScheduleRun execute(
            AlgorithmType algorithmType, ScheduleProblem problem, ScheduleParameters parameters) {
        long started = System.nanoTime();
        SchedulePlan firstStage = engines.get(algorithmType).schedule(problem, parameters);
        long phaseOneEnded = System.nanoTime();
        ScheduleSeatingPlan seated = seatingService.allocate(problem, firstStage);
        long phaseTwoEnded = System.nanoTime();
        EvaluationResult evaluation = evaluator.evaluate(
                problem, seated, phaseOneEnded - started, phaseTwoEnded - phaseOneEnded);
        int firstStageCount = firstStage.assignments().size();
        double seatFailureRate = firstStageCount == 0
                ? 0 : (double) (firstStageCount - seated.assignments().size()) / firstStageCount;
        return new ScheduleRun(evaluation, seated, seatFailureRate);
    }
}
