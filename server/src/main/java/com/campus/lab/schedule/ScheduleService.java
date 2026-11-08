package com.campus.lab.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    private static final List<AlgorithmType> EXECUTION_ORDER = List.of(
            AlgorithmType.FCFS, AlgorithmType.GREEDY, AlgorithmType.GENETIC);

    private final ScheduleRepository repository;
    private final ScheduleTaskTransactionService transactionService;
    private final ScheduleAlgorithmExecutor algorithmExecutor;
    private final PrimaryPlanPublisher primaryPlanPublisher;
    private final ScheduleBatchLockManager batchLockManager;
    private final SchedulePublicationFailureService publicationFailureService;
    private final int populationSize;
    private final int generations;
    private final long seed;

    public ScheduleService(
            ScheduleRepository repository,
            ScheduleTaskTransactionService transactionService,
            ScheduleAlgorithmExecutor algorithmExecutor,
            PrimaryPlanPublisher primaryPlanPublisher,
            ScheduleBatchLockManager batchLockManager,
            SchedulePublicationFailureService publicationFailureService,
            @Value("${app.schedule.genetic.population-size:80}") int populationSize,
            @Value("${app.schedule.genetic.generations:200}") int generations,
            @Value("${app.schedule.genetic.seed:0}") long seed) {
        this.repository = repository;
        this.transactionService = transactionService;
        this.algorithmExecutor = algorithmExecutor;
        this.primaryPlanPublisher = primaryPlanPublisher;
        this.batchLockManager = batchLockManager;
        this.publicationFailureService = publicationFailureService;
        this.populationSize = populationSize;
        this.generations = generations;
        this.seed = seed;
    }

    public void runDueBatches() {
        for (LocalDate date : repository.listScheduleDates()) {
            try {
                runBatch(date);
            } catch (RuntimeException exception) {
                log.warn("排期批次执行失败: date={} ({})",
                        date, exception.getClass().getSimpleName());
            }
        }
    }

    public void runBatch(LocalDate date) {
        Optional<ScheduleBatchLock> acquired = batchLockManager.tryAcquire(date);
        if (acquired.isEmpty()) {
            return;
        }
        String batchNo = null;
        try (ScheduleBatchLock lock = acquired.get()) {
            lock.ensureHeld();
            transactionService.failInterruptedTasks(date);
            ScheduleRepository.ScheduleSnapshot snapshot = repository.loadSnapshot(date);
            if (snapshot == null) {
                return;
            }
            lock.ensureHeld();
            batchNo = snapshot.batchNo();
            ScheduleParameters parameters = ScheduleParameters.builder()
                    .populationSize(populationSize).generations(generations).seed(seed).build();
            String runtimeEnvironment = repository.toJson(Map.of(
                    "javaVersion", System.getProperty("java.version"),
                    "osName", System.getProperty("os.name"),
                    "availableProcessors", Runtime.getRuntime().availableProcessors()));
            List<ScheduleRepository.ScheduleTask> tasks = transactionService.createTasks(
                    snapshot, parameters, runtimeEnvironment, repository.primaryAlgorithm());
            for (AlgorithmType algorithmType : EXECUTION_ORDER) {
                ScheduleRepository.ScheduleTask task = tasks.stream()
                        .filter(candidate -> candidate.algorithmType() == algorithmType)
                        .findFirst().orElseThrow();
                lock.ensureHeld();
                execute(task, snapshot.problem(), parameters);
            }
            ScheduleRepository.ScheduleTask primary = repository.listTasks(batchNo).stream()
                    .filter(ScheduleRepository.ScheduleTask::primary).findFirst().orElseThrow();
            if (primary.status() == ScheduleTaskStatus.SUCCEEDED) {
                lock.ensureHeld();
                try {
                    primaryPlanPublisher.publish(primary.taskId());
                } catch (RuntimeException exception) {
                    log.warn("主方案发布失败: taskId={} reason={}",
                            primary.taskId(), diagnostic(exception));
                }
            }
        } catch (RuntimeException exception) {
            if (batchNo != null && "BATCH_LOCK_LOST".equals(exception.getMessage())) {
                transactionService.failBatch(batchNo, "BATCH_LOCK_LOST");
                repository.listTasks(batchNo).stream()
                        .filter(ScheduleRepository.ScheduleTask::primary)
                        .filter(task -> task.status() == ScheduleTaskStatus.SUCCEEDED)
                        .findFirst()
                        .ifPresent(task -> publicationFailureService.markFailed(
                                task.taskId(), "BATCH_LOCK_LOST"));
            }
            throw exception;
        }
    }

    private void execute(
            ScheduleRepository.ScheduleTask task, ScheduleProblem problem,
            ScheduleParameters parameters) {
        long started = System.nanoTime();
        if (!transactionService.markRunning(task.taskId())) {
            return;
        }
        try {
            ScheduleRun run = algorithmExecutor.execute(task.algorithmType(), problem, parameters);
            transactionService.saveSuccess(task, problem, run, elapsedMillis(started));
        } catch (RuntimeException exception) {
            String reason = "ALGORITHM_EXCEPTION:" + exception.getClass().getSimpleName();
            transactionService.saveFailure(task.taskId(), reason, elapsedMillis(started));
            log.warn("排期算法任务失败: taskId={} algorithm={} reason={}",
                    task.taskId(), task.algorithmType(), reason);
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static String diagnostic(RuntimeException exception) {
        if (exception instanceof SchedulePublicationException) {
            return exception.getMessage();
        }
        return "PUBLISH_EXCEPTION:" + exception.getClass().getSimpleName();
    }
}
