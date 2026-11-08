package com.campus.lab.experiment;

import com.campus.lab.common.BusinessException;
import com.campus.lab.schedule.AlgorithmType;
import com.campus.lab.schedule.ScheduleAlgorithmExecutor;
import com.campus.lab.schedule.ScheduleParameters;
import com.campus.lab.schedule.ScheduleProblem;
import com.campus.lab.schedule.ScheduleRun;
import com.campus.lab.schedule.ScheduleTaskStatus;
import com.campus.lab.schedule.dto.ScheduleExperimentView;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ExperimentService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentService.class);
    private static final Set<Integer> SCALES = Set.of(50, 100, 200, 400);
    private static final long DEFAULT_SEED = 20261024L;

    private final ExperimentRepository repository;
    private final SyntheticProblemFactory problemFactory;
    private final ScheduleAlgorithmExecutor algorithmExecutor;
    private final ExperimentBatchLockManager lockManager;
    private final Executor executor;
    private final int repeats;
    private final int populationSize;
    private final int generations;
    private final int recoveryBatchLimit;

    public ExperimentService(
            ExperimentRepository repository,
            SyntheticProblemFactory problemFactory,
            ScheduleAlgorithmExecutor algorithmExecutor,
            ExperimentBatchLockManager lockManager,
            @Qualifier("experimentExecutor") Executor executor,
            @Value("${app.experiment.repeats:10}") int repeats,
            @Value("${app.experiment.genetic.population-size:80}") int populationSize,
            @Value("${app.experiment.genetic.generations:200}") int generations,
            @Value("${app.experiment.recovery.batch-limit:8}") int recoveryBatchLimit) {
        if (repeats < 1 || populationSize < 1 || generations < 0 || recoveryBatchLimit < 1) {
            throw new IllegalArgumentException("实验重复次数、恢复上限或遗传算法参数无效");
        }
        this.repository = repository;
        this.problemFactory = problemFactory;
        this.algorithmExecutor = algorithmExecutor;
        this.lockManager = lockManager;
        this.executor = executor;
        this.repeats = repeats;
        this.populationSize = populationSize;
        this.generations = generations;
        this.recoveryBatchLimit = recoveryBatchLimit;
    }

    public ExperimentViews.RunView start(ExperimentViews.RunRequest request) {
        if (request == null || request.dataScale() == null || !SCALES.contains(request.dataScale())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "dataScale仅支持50、100、200、400");
        }
        int dataScale = request.dataScale();
        long seed = request.seed() == null ? DEFAULT_SEED : request.seed();
        ScheduleProblem problem = problemFactory.generate(dataScale, seed);
        String id = UUID.randomUUID().toString().replace("-", "");
        String batchNo = "EXP-" + id;
        String datasetId = "EXP-DSET-" + id;
        String demandSnapshot = repository.toJson(new DemandSnapshot(
                dataScale, seed, SyntheticProblemFactory.DATE, problem.demands()));
        String resourceSnapshot = repository.toJson(problem.resources());
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("osName", System.getProperty("os.name"));
        environment.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        java.util.Optional<ExperimentBatchLock> acquired = lockManager.tryAcquire(batchNo);
        if (acquired.isEmpty()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "实验批次正在执行，请稍后重试");
        }
        ExperimentBatchLock lock = acquired.get();
        try {
            repository.createBatch(
                    batchNo, datasetId, seed, demandSnapshot, resourceSnapshot,
                    repository.toJson(environment), repeats, populationSize, generations);
            submitLocked(batchNo, problem, dataScale, false, lock);
        } catch (org.springframework.core.task.TaskRejectedException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "实验执行队列已满，请稍后重试");
        } catch (RuntimeException exception) {
            lock.close();
            throw exception;
        }
        return new ExperimentViews.RunView(batchNo, datasetId, dataScale, seed, "PENDING");
    }

    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        for (ExperimentRepository.RecoveryBatch batch
                : repository.listInterruptedBatches(recoveryBatchLimit)) {
            try {
                submit(batch.batchNo(), problemFactory.generate(batch.dataScale(), batch.seed()),
                        batch.dataScale(), true);
            } catch (org.springframework.core.task.TaskRejectedException exception) {
                log.warn("实验恢复队列已满，保留待恢复状态: batchNo={}", batch.batchNo());
            } catch (RuntimeException exception) {
                repository.failBatch(batch.batchNo(),
                        "RECOVERY_EXCEPTION:" + exception.getClass().getSimpleName());
                log.warn("实验恢复失败: batchNo={} errorType={}",
                        batch.batchNo(), exception.getClass().getSimpleName());
            }
        }
    }

    private boolean submit(
            String batchNo, ScheduleProblem problem, int dataScale, boolean recovery) {
        java.util.Optional<ExperimentBatchLock> acquired = lockManager.tryAcquire(batchNo);
        if (acquired.isEmpty()) {
            return false;
        }
        return submitLocked(batchNo, problem, dataScale, recovery, acquired.get());
    }

    private boolean submitLocked(
            String batchNo, ScheduleProblem problem, int dataScale, boolean recovery,
            ExperimentBatchLock lock) {
        try {
            if (recovery) {
                repository.resetRunningTasks(batchNo);
            }
            executor.execute(() -> {
                try (lock) {
                    execute(batchNo, problem, dataScale, lock);
                }
            });
            return true;
        } catch (org.springframework.core.task.TaskRejectedException exception) {
            lock.close();
            if (!recovery) {
                repository.failBatch(batchNo, "EXECUTOR_REJECTED");
            }
            throw exception;
        } catch (RuntimeException exception) {
            lock.close();
            throw exception;
        }
    }

    private void execute(
            String batchNo, ScheduleProblem problem, int dataScale, ExperimentBatchLock lock) {
        for (ExperimentRepository.TaskRow task : repository.listTasks(batchNo)) {
            lock.ensureHeld();
            long started = System.nanoTime();
            if (!repository.markRunning(task.taskId())) {
                continue;
            }
            try {
                ScheduleParameters parameters = ScheduleParameters.builder()
                        .populationSize(populationSize).generations(generations)
                        .seed(task.randomSeed()).build();
                ScheduleRun run = algorithmExecutor.execute(task.algorithmType(), problem, parameters);
                lock.ensureHeld();
                repository.saveSuccess(
                        task.taskId(), task.algorithmType(), dataScale, run, elapsedMillis(started));
            } catch (RuntimeException exception) {
                if ("EXPERIMENT_BATCH_LOCK_LOST".equals(exception.getMessage())) {
                    throw exception;
                }
                repository.saveFailure(task.taskId(),
                        "ALGORITHM_EXCEPTION:" + exception.getClass().getSimpleName(),
                        elapsedMillis(started));
                log.warn("对比实验任务失败: taskId={} algorithm={} errorType={}",
                        task.taskId(), task.algorithmType(), exception.getClass().getSimpleName());
            }
        }
    }

    public ExperimentViews.BatchView get(String batchNo) {
        List<ExperimentRepository.TaskRow> rows = repository.listTasks(batchNo);
        if (rows.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "实验批次不存在");
        }
        ExperimentRepository.TaskRow first = rows.getFirst();
        JsonNode snapshot = readTree(first.demandSnapshot());
        List<ExperimentViews.TaskView> tasks = rows.stream().map(row ->
                new ExperimentViews.TaskView(
                        row.taskId(), row.algorithmType(), row.primary(), row.status(),
                        row.randomSeed(), row.repeatIndex(), readTree(row.parameters()),
                        readTree(row.runtimeEnvironment()), readTree(row.demandSnapshot()),
                        readTree(row.resourceSnapshot()), row.experiment())).toList();
        return new ExperimentViews.BatchView(
                batchNo, first.datasetId(), snapshot.path("dataScale").asInt(),
                snapshot.path("seed").asLong(), batchStatus(rows), tasks, geneticSummary(rows));
    }

    private static ScheduleTaskStatus batchStatus(List<ExperimentRepository.TaskRow> rows) {
        if (rows.stream().allMatch(row -> row.status() == ScheduleTaskStatus.PENDING)) {
            return ScheduleTaskStatus.PENDING;
        }
        if (rows.stream().anyMatch(row -> row.status() == ScheduleTaskStatus.PENDING
                || row.status() == ScheduleTaskStatus.RUNNING)) {
            return ScheduleTaskStatus.RUNNING;
        }
        return rows.stream().allMatch(row -> row.status() == ScheduleTaskStatus.SUCCEEDED)
                ? ScheduleTaskStatus.SUCCEEDED : ScheduleTaskStatus.FAILED;
    }

    private static ExperimentViews.GeneticSummary geneticSummary(
            List<ExperimentRepository.TaskRow> rows) {
        List<ScheduleExperimentView> completed = rows.stream()
                .filter(row -> row.algorithmType() == AlgorithmType.GENETIC)
                .map(ExperimentRepository.TaskRow::experiment).toList();
        if (completed.isEmpty() || completed.stream().anyMatch(java.util.Objects::isNull)
                || rows.stream().filter(row -> row.algorithmType() == AlgorithmType.GENETIC)
                        .anyMatch(row -> row.status() != ScheduleTaskStatus.SUCCEEDED)) {
            return ExperimentViews.GeneticSummary.incomplete();
        }
        return new ExperimentViews.GeneticSummary(
                mean(completed, ScheduleExperimentView::allocationSuccessRate),
                deviation(completed, ScheduleExperimentView::allocationSuccessRate),
                mean(completed, ScheduleExperimentView::averageSeatUtilization),
                deviation(completed, ScheduleExperimentView::averageSeatUtilization),
                mean(completed, ScheduleExperimentView::seatPreferenceMismatchRate),
                deviation(completed, ScheduleExperimentView::seatPreferenceMismatchRate),
                mean(completed, ScheduleExperimentView::timeFragmentationRate),
                deviation(completed, ScheduleExperimentView::timeFragmentationRate),
                mean(completed, ScheduleExperimentView::seatAllocationFailureRate),
                deviation(completed, ScheduleExperimentView::seatAllocationFailureRate));
    }

    private static Double mean(
            List<ScheduleExperimentView> values, Function<ScheduleExperimentView, Double> metric) {
        List<Double> present = values.stream().map(metric).filter(java.util.Objects::nonNull).toList();
        return present.isEmpty() ? null : present.stream().mapToDouble(Double::doubleValue)
                .average().orElseThrow();
    }

    private static Double deviation(
            List<ScheduleExperimentView> values, Function<ScheduleExperimentView, Double> metric) {
        List<Double> present = values.stream().map(metric).filter(java.util.Objects::nonNull).toList();
        if (present.size() < 2) {
            return null;
        }
        double mean = mean(values, metric);
        double sum = present.stream().mapToDouble(Double::doubleValue)
                .map(value -> (value - mean) * (value - mean)).sum();
        return Math.sqrt(sum / (present.size() - 1));
    }

    private JsonNode readTree(String json) {
        return repository.readJson(json, JsonNode.class);
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private record DemandSnapshot(
            int dataScale, long seed, LocalDate date,
            List<com.campus.lab.schedule.ScheduleDemand> demands) {
    }
}
