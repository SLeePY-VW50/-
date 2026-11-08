package com.campus.lab.schedule;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleTaskTransactionService {

    private final ScheduleRepository repository;
    private final Clock clock;

    public ScheduleTaskTransactionService(ScheduleRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void failInterruptedTasks(java.time.LocalDate date) {
        repository.failInterruptedTasks(
                date, "INTERRUPTED_BY_SUCCESSOR", LocalDateTime.now(clock));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void failBatch(String batchNo, String reason) {
        repository.failBatchTasks(batchNo, reason, LocalDateTime.now(clock));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public List<ScheduleRepository.ScheduleTask> createTasks(
            ScheduleRepository.ScheduleSnapshot snapshot, ScheduleParameters parameters,
            String runtimeEnvironment, AlgorithmType primaryAlgorithm) {
        String parametersJson = repository.toJson(parameters);
        for (AlgorithmType algorithmType : List.of(
                AlgorithmType.FCFS, AlgorithmType.GREEDY, AlgorithmType.GENETIC)) {
            repository.insertTask(
                    snapshot, algorithmType, algorithmType == primaryAlgorithm,
                    parametersJson, parameters.seed(), runtimeEnvironment);
        }
        return repository.listTasks(snapshot.batchNo());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean markRunning(long taskId) {
        return repository.markRunning(taskId, LocalDateTime.now(clock));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveSuccess(
            ScheduleRepository.ScheduleTask task, ScheduleProblem problem,
            ScheduleRun run, long durationMs) {
        Map<Long, ScheduleSeatedAssignment> assignments = run.seating().assignments().stream()
                .collect(Collectors.toMap(ScheduleSeatedAssignment::resvId, Function.identity()));
        for (ScheduleDemand demand : problem.demands()) {
            ScheduleSeatedAssignment assignment = assignments.get(demand.resvId());
            repository.insertResult(
                    task.taskId(), demand.resvId(), assignment, demand.date(),
                    assignment == null ? null : repository.toJson(assignment.candidateSeatAllocations()));
        }
        repository.insertExperiment(task.taskId(), task.algorithmType(), problem.demands().size(), run);
        boolean primaryUnresolved = task.primary() && !run.seating().unresolvedReasons().isEmpty();
        repository.completeTask(
                task.taskId(), primaryUnresolved ? ScheduleTaskStatus.FAILED : ScheduleTaskStatus.SUCCEEDED,
                primaryUnresolved
                        ? "UNRESOLVED_DEMANDS:" + run.seating().unresolvedReasons().size() : null,
                LocalDateTime.now(clock), durationMs);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveFailure(long taskId, String reason, long durationMs) {
        repository.completeTask(
                taskId, ScheduleTaskStatus.FAILED, reason, LocalDateTime.now(clock), durationMs);
    }
}
