package com.campus.lab.experiment;

import com.campus.lab.schedule.AlgorithmType;
import com.campus.lab.schedule.ScheduleParameters;
import com.campus.lab.schedule.ScheduleRun;
import com.campus.lab.schedule.ScheduleTaskStatus;
import com.campus.lab.schedule.dto.ScheduleExperimentView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ExperimentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ExperimentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void createBatch(
            String batchNo, String datasetId, long seed, String demandSnapshot,
            String resourceSnapshot, String runtimeEnvironment, int repeats,
            int populationSize, int generations) {
        insertTask(batchNo, datasetId, AlgorithmType.FCFS, seed, 0, demandSnapshot,
                resourceSnapshot, parameters(seed, populationSize, generations), runtimeEnvironment);
        insertTask(batchNo, datasetId, AlgorithmType.GREEDY, seed, 0, demandSnapshot,
                resourceSnapshot, parameters(seed, populationSize, generations), runtimeEnvironment);
        for (int repeat = 0; repeat < repeats; repeat++) {
            long repeatSeed = seed + repeat;
            insertTask(batchNo, datasetId, AlgorithmType.GENETIC, repeatSeed, repeat,
                    demandSnapshot, resourceSnapshot,
                    parameters(repeatSeed, populationSize, generations), runtimeEnvironment);
        }
    }

    private void insertTask(
            String batchNo, String datasetId, AlgorithmType algorithm, long seed, int repeat,
            String demandSnapshot, String resourceSnapshot, ScheduleParameters parameters,
            String runtimeEnvironment) {
        jdbcTemplate.update("""
                INSERT INTO t_schedule_task
                    (schedule_batch_no, algorithm_type, is_primary, dataset_id,
                     demand_snapshot, resource_snapshot, parameters, random_seed, repeat_index,
                     runtime_environment, status, progress)
                VALUES (?, ?, FALSE, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0)
                """, batchNo, algorithm.name(), datasetId, demandSnapshot, resourceSnapshot,
                toJson(parameters), seed, repeat, runtimeEnvironment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean markRunning(long taskId) {
        return jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'RUNNING', progress = 10, started_at = ?, error_message = NULL
                WHERE task_id = ? AND status = 'PENDING'
                """, Timestamp.valueOf(LocalDateTime.now(clock)), taskId) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveSuccess(long taskId, AlgorithmType algorithm, int dataScale, ScheduleRun run,
            long durationMs) {
        long phaseOneMs = TimeUnit.NANOSECONDS.toMillis(
                run.evaluation().solveTime().phase1Nanos());
        long phaseTwoMs = TimeUnit.NANOSECONDS.toMillis(
                run.evaluation().solveTime().phase2Nanos());
        jdbcTemplate.update("""
                INSERT INTO t_algorithm_experiment
                    (task_id, algorithm_type, data_scale, allocation_success_rate,
                     average_seat_utilization, seat_preference_mismatch_rate, time_fragmentation_rate,
                     seat_allocation_failure_rate, phase_one_duration_ms, phase_two_duration_ms,
                     total_duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, taskId, algorithm.name(), dataScale,
                run.evaluation().assignmentSuccessRate(), run.evaluation().averageSeatUtilization(),
                run.evaluation().softPreferenceMismatchRate(), run.evaluation().timeFragmentationRate(),
                run.seatAllocationFailureRate(), phaseOneMs, phaseTwoMs, phaseOneMs + phaseTwoMs);
        jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'SUCCEEDED', progress = 100, finished_at = ?, duration_ms = ?
                WHERE task_id = ? AND status = 'RUNNING'
                """, Timestamp.valueOf(LocalDateTime.now(clock)), durationMs, taskId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveFailure(long taskId, String reason, long durationMs) {
        jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'FAILED', progress = 100, finished_at = ?, duration_ms = ?,
                    error_message = ?
                WHERE task_id = ? AND status = 'RUNNING'
                """, Timestamp.valueOf(LocalDateTime.now(clock)), durationMs, reason, taskId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void failBatch(String batchNo, String reason) {
        jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'FAILED', progress = 100, finished_at = ?, error_message = ?
                WHERE schedule_batch_no = ? AND status IN ('PENDING', 'RUNNING')
                """, Timestamp.valueOf(LocalDateTime.now(clock)), reason, batchNo);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void resetRunningTasks(String batchNo) {
        jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'PENDING', progress = 0, started_at = NULL,
                    finished_at = NULL, duration_ms = NULL, error_message = NULL
                WHERE schedule_batch_no = ? AND status = 'RUNNING'
                """, batchNo);
    }

    public List<RecoveryBatch> listInterruptedBatches(int limit) {
        return jdbcTemplate.query("""
                SELECT t.schedule_batch_no, t.demand_snapshot,
                       MIN(t.created_at) AS first_created_at
                FROM t_schedule_task t
                WHERE t.schedule_batch_no LIKE 'EXP-%' AND t.status IN ('PENDING', 'RUNNING')
                GROUP BY t.schedule_batch_no, t.demand_snapshot
                ORDER BY first_created_at, t.schedule_batch_no
                LIMIT ?
                """, (rs, rowNum) -> {
                    com.fasterxml.jackson.databind.JsonNode snapshot =
                            readJson(rs.getString("demand_snapshot"),
                                    com.fasterxml.jackson.databind.JsonNode.class);
                    return new RecoveryBatch(
                            rs.getString("schedule_batch_no"), snapshot.path("dataScale").asInt(),
                            snapshot.path("seed").asLong());
                }, limit);
    }

    public List<TaskRow> listTasks(String batchNo) {
        return jdbcTemplate.query("""
                SELECT t.task_id, t.dataset_id, t.algorithm_type, t.is_primary, t.status,
                       t.random_seed, t.repeat_index, t.parameters, t.runtime_environment,
                       t.demand_snapshot, t.resource_snapshot,
                       e.task_id AS experiment_task_id, e.allocation_success_rate,
                       e.average_seat_utilization, e.seat_preference_mismatch_rate,
                       e.time_fragmentation_rate, e.seat_allocation_failure_rate,
                       e.phase_one_duration_ms, e.phase_two_duration_ms, e.total_duration_ms
                FROM t_schedule_task t
                LEFT JOIN t_algorithm_experiment e ON e.task_id = t.task_id
                WHERE t.schedule_batch_no = ?
                ORDER BY t.task_id
                """, this::mapTask, batchNo);
    }

    private TaskRow mapTask(ResultSet rs, int rowNum) throws SQLException {
        ScheduleExperimentView experiment = rs.getObject("experiment_task_id", Long.class) == null
                ? null : new ScheduleExperimentView(
                        rs.getDouble("allocation_success_rate"),
                        rs.getDouble("average_seat_utilization"),
                        rs.getDouble("seat_preference_mismatch_rate"),
                        rs.getDouble("time_fragmentation_rate"),
                        nullableDouble(rs, "seat_allocation_failure_rate"),
                        rs.getLong("phase_one_duration_ms"), rs.getLong("phase_two_duration_ms"),
                        rs.getLong("total_duration_ms"));
        return new TaskRow(
                rs.getLong("task_id"), rs.getString("dataset_id"),
                AlgorithmType.valueOf(rs.getString("algorithm_type")),
                rs.getBoolean("is_primary"), ScheduleTaskStatus.valueOf(rs.getString("status")),
                rs.getLong("random_seed"), rs.getInt("repeat_index"), rs.getString("parameters"),
                rs.getString("runtime_environment"), rs.getString("demand_snapshot"),
                rs.getString("resource_snapshot"), experiment);
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("EXPERIMENT_JSON_SERIALIZATION_FAILED", exception);
        }
    }

    public <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("EXPERIMENT_JSON_DESERIALIZATION_FAILED", exception);
        }
    }

    private static ScheduleParameters parameters(long seed, int populationSize, int generations) {
        return ScheduleParameters.builder()
                .populationSize(populationSize).generations(generations).seed(seed).build();
    }

    record RecoveryBatch(String batchNo, int dataScale, long seed) {
    }

    record TaskRow(
            long taskId, String datasetId, AlgorithmType algorithmType, boolean primary,
            ScheduleTaskStatus status, long randomSeed, int repeatIndex, String parameters,
            String runtimeEnvironment, String demandSnapshot, String resourceSnapshot,
            ScheduleExperimentView experiment) {
    }
}
