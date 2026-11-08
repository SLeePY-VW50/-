package com.campus.lab.schedule;

import com.campus.lab.common.DayTimes;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatSnapshot;
import com.campus.lab.reservation.dto.ReservationPreferences;
import com.campus.lab.resource.EffectiveTimeRule;
import com.campus.lab.resource.ReservationRuleConfig;
import com.campus.lab.resource.RuleValidator;
import com.campus.lab.resource.TimeRule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
public class ScheduleRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RuleValidator ruleValidator;

    public ScheduleRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, RuleValidator ruleValidator) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ruleValidator = ruleValidator;
    }

    public List<LocalDate> listScheduleDates() {
        return jdbcTemplate.queryForList("""
                SELECT request_date FROM (
                    SELECT DISTINCT request_date FROM t_reservation
                    WHERE allocation_mode = 'SCHEDULABLE' AND status = 'PENDING_SCHEDULE'
                    UNION
                    SELECT DISTINCT JSON_UNQUOTE(JSON_EXTRACT(demand_snapshot, '$.date'))
                    FROM t_schedule_task
                    WHERE status IN ('PENDING', 'RUNNING')
                      AND schedule_batch_no NOT LIKE 'EXP-%'
                ) schedule_dates ORDER BY request_date
                """, LocalDate.class);
    }

    public ScheduleSnapshot loadSnapshot(LocalDate date) {
        List<ScheduleDemand> demands = jdbcTemplate.query("""
                SELECT r.resv_id, r.user_id, r.created_at, r.request_date,
                       r.request_window_start, r.request_window_end, r.requested_duration_minutes,
                       r.reservation_mode, r.attendee_count, r.seat_count,
                       u.credit_score, r.preferences
                FROM t_reservation r
                JOIN sys_user u ON u.user_id = r.user_id
                WHERE r.request_date = ? AND r.allocation_mode = 'SCHEDULABLE'
                  AND r.status = 'PENDING_SCHEDULE'
                ORDER BY r.resv_id
                """, (rs, rowNum) -> mapDemand(rs), date);
        if (demands.isEmpty()) {
            return null;
        }
        List<ScheduleLabResource> resources = loadResources(date, demands);
        DemandSnapshot demandSnapshot = new DemandSnapshot(date, demands);
        String batchId = java.util.UUID.randomUUID().toString().replace("-", "");
        return new ScheduleSnapshot(
                "SCH-" + date + "-" + batchId, "DSET-" + date + "-" + batchId, date,
                toJson(demandSnapshot), toJson(resources),
                new ScheduleProblem(demands, resources));
    }

    private List<ScheduleLabResource> loadResources(LocalDate date, List<ScheduleDemand> demands) {
        Set<Long> labIds = new LinkedHashSet<>();
        boolean allActive = false;
        for (ScheduleDemand demand : demands) {
            if (demand.allowedLabIds().isEmpty()) {
                allActive = true;
                break;
            }
            labIds.addAll(demand.allowedLabIds());
        }
        String sql = "SELECT lab_id, capacity, status FROM t_lab ";
        List<Object> arguments = new ArrayList<>();
        if (allActive) {
            sql += "WHERE status = 'ACTIVE' ";
        } else {
            if (labIds.isEmpty()) {
                return List.of();
            }
            String placeholders = String.join(",", Collections.nCopies(labIds.size(), "?"));
            sql += "WHERE lab_id IN (" + placeholders + ") ";
            arguments.addAll(labIds);
        }
        sql += "ORDER BY lab_id";
        List<LabBase> labs = jdbcTemplate.query(sql, (rs, rowNum) -> new LabBase(
                rs.getLong("lab_id"), rs.getInt("capacity"),
                ResourceStatus.valueOf(rs.getString("status"))), arguments.toArray());
        if (labs.isEmpty()) {
            return List.of();
        }
        List<Long> orderedLabIds = labs.stream().map(LabBase::labId).toList();
        String placeholders = String.join(",", Collections.nCopies(orderedLabIds.size(), "?"));
        ReservationRuleConfig global = globalRuleConfig();
        Map<Long, Long> roomMasks = jdbcTemplate.query("""
                SELECT l.lab_id, COALESCE(b.mask, 0)
                FROM t_lab l
                LEFT JOIN t_slot_bitmap b ON b.lab_id = l.lab_id AND b.bitmap_date = ?
                WHERE l.lab_id IN (""" + placeholders + ") ORDER BY l.lab_id", rs -> {
            Map<Long, Long> values = new java.util.HashMap<>();
            while (rs.next()) {
                values.put(rs.getLong("lab_id"), rs.getLong(2));
            }
            return values;
        }, queryArguments(date, orderedLabIds));
        Map<Long, List<SeatSnapshot>> seatsByLab = jdbcTemplate.query("""
                SELECT s.lab_id, s.seat_id, s.seat_code, s.zone_code, s.seat_index,
                       s.spec_level, s.near_window, s.seat_status, COALESCE(b.mask, 0) AS mask
                FROM t_lab_seat s
                LEFT JOIN t_seat_bitmap b ON b.seat_id = s.seat_id AND b.bitmap_date = ?
                WHERE s.lab_id IN (""" + placeholders + ") ORDER BY s.lab_id, s.seat_index, s.seat_id",
                rs -> {
                    Map<Long, List<SeatSnapshot>> values = new java.util.HashMap<>();
                    while (rs.next()) {
                        values.computeIfAbsent(rs.getLong("lab_id"), key -> new ArrayList<>())
                                .add(mapSeat(rs));
                    }
                    return values;
                }, queryArguments(date, orderedLabIds));
        Map<Long, EffectiveTimeRule> rules = jdbcTemplate.query("""
                SELECT rule_id, lab_id, day_of_week, open_time, close_time,
                       min_duration_minutes, max_duration_minutes, advance_min_days, advance_max_days
                FROM t_time_rule WHERE day_of_week = ? AND lab_id IN (""" + placeholders + ") ORDER BY lab_id",
                rs -> {
                    Map<Long, EffectiveTimeRule> values = new java.util.HashMap<>();
                    while (rs.next()) {
                        long labId = rs.getLong("lab_id");
                        values.put(labId, ruleValidator.merge(new TimeRule(
                                rs.getLong("rule_id"), labId, rs.getInt("day_of_week"),
                                DayTimes.parseDatabaseTime(rs.getString("open_time")),
                                DayTimes.parseDatabaseTime(rs.getString("close_time")),
                                rs.getObject("min_duration_minutes", Integer.class),
                                rs.getObject("max_duration_minutes", Integer.class),
                                rs.getObject("advance_min_days", Integer.class),
                                rs.getObject("advance_max_days", Integer.class)), global));
                    }
                    return values;
                }, queryArguments(date.getDayOfWeek().getValue(), orderedLabIds));
        return labs.stream().map(lab -> {
            EffectiveTimeRule rule = rules.get(lab.labId());
            return new ScheduleLabResource(
                    lab.labId(), date, lab.status(), lab.capacity(), openMask(rule),
                    roomMasks.getOrDefault(lab.labId(), 0L),
                    seatsByLab.getOrDefault(lab.labId(), List.of()), rule);
        }).toList();
    }

    private static Object[] queryArguments(Object first, List<Long> labIds) {
        List<Object> arguments = new ArrayList<>(labIds.size() + 1);
        arguments.add(first);
        arguments.addAll(labIds);
        return arguments.toArray();
    }

    private record LabBase(long labId, int capacity, ResourceStatus status) {
    }

    private ScheduleDemand mapDemand(ResultSet rs) throws SQLException {
        LocalTime windowStart = DayTimes.parseDatabaseTime(rs.getString("request_window_start"));
        LocalTime windowEnd = DayTimes.parseDatabaseTime(rs.getString("request_window_end"));
        ReservationMode mode = ReservationMode.valueOf(rs.getString("reservation_mode"));
        ReservationPreferences preferences = readJson(
                rs.getString("preferences"), ReservationPreferences.class);
        if (preferences == null) {
            preferences = ReservationPreferences.empty();
        }
        return new ScheduleDemand(
                rs.getLong("resv_id"), rs.getLong("user_id"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("request_date", LocalDate.class),
                DayTimes.minuteOfDay(windowStart, false), DayTimes.minuteOfDay(windowEnd, true),
                rs.getInt("requested_duration_minutes"), mode, rs.getInt("attendee_count"),
                mode == ReservationMode.SEAT ? rs.getInt("seat_count") : 0,
                rs.getInt("credit_score"), preferences.minSpecLevel(), preferences.zoneCode(),
                Boolean.TRUE.equals(preferences.nearWindow()), Set.copyOf(preferences.allowedLabIds()));
    }

    private static SeatSnapshot mapSeat(ResultSet rs) throws SQLException {
        return new SeatSnapshot(
                rs.getLong("seat_id"), rs.getString("seat_code"), rs.getString("zone_code"),
                rs.getInt("seat_index"), rs.getString("spec_level"), rs.getBoolean("near_window"),
                ResourceStatus.valueOf(rs.getString("seat_status")), rs.getLong("mask"));
    }

    EffectiveTimeRule lockEffectiveRule(long labId, LocalDate date) {
        return effectiveRule(labId, date, globalRuleConfig(), true);
    }

    private EffectiveTimeRule effectiveRule(
            long labId, LocalDate date, ReservationRuleConfig global, boolean forUpdate) {
        String sql = """
                SELECT rule_id, lab_id, day_of_week, open_time, close_time,
                       min_duration_minutes, max_duration_minutes, advance_min_days, advance_max_days
                FROM t_time_rule WHERE lab_id = ? AND day_of_week = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        List<EffectiveTimeRule> rules = jdbcTemplate.query(sql, (rs, rowNum) -> ruleValidator.merge(
                new TimeRule(
                        rs.getLong("rule_id"), rs.getLong("lab_id"), rs.getInt("day_of_week"),
                        DayTimes.parseDatabaseTime(rs.getString("open_time")),
                        DayTimes.parseDatabaseTime(rs.getString("close_time")),
                        rs.getObject("min_duration_minutes", Integer.class),
                        rs.getObject("max_duration_minutes", Integer.class),
                        rs.getObject("advance_min_days", Integer.class),
                        rs.getObject("advance_max_days", Integer.class)), global),
                labId, date.getDayOfWeek().getValue());
        return rules.isEmpty() ? null : rules.getFirst();
    }

    private ReservationRuleConfig globalRuleConfig() {
        Map<String, String> values = jdbcTemplate.query("""
                SELECT config_key, config_value FROM t_sys_config
                WHERE config_key IN (
                    'reservation.duration.min.minutes', 'reservation.duration.max.minutes',
                    'reservation.advance.min.days', 'reservation.advance.max.days')
                """, rs -> {
            Map<String, String> config = new java.util.HashMap<>();
            while (rs.next()) {
                config.put(rs.getString(1), rs.getString(2));
            }
            return config;
        });
        return new ReservationRuleConfig(
                requiredConfig(values, "reservation.duration.min.minutes"),
                requiredConfig(values, "reservation.duration.max.minutes"),
                requiredConfig(values, "reservation.advance.min.days"),
                requiredConfig(values, "reservation.advance.max.days"));
    }

    private static int requiredConfig(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("MISSING_SCHEDULE_RULE_CONFIG:" + key);
        }
        return Integer.parseInt(value);
    }

    static long openMask(EffectiveTimeRule rule) {
        if (rule == null || rule.openTime() == null || rule.closeTime() == null) {
            return 0;
        }
        int start = DayTimes.minuteOfDay(rule.openTime(), false) / 30;
        int end = DayTimes.minuteOfDay(rule.closeTime(), true) / 30;
        long mask = 0;
        for (int slot = start; slot < end; slot++) {
            mask |= 1L << slot;
        }
        return mask;
    }

    public AlgorithmType primaryAlgorithm() {
        List<String> values = jdbcTemplate.queryForList("""
                SELECT config_value FROM t_sys_config WHERE config_key = 'schedule.primary.algorithm'
                """, String.class);
        String value = values.isEmpty() ? "GREEDY" : values.getFirst();
        try {
            return AlgorithmType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("INVALID_PRIMARY_ALGORITHM", exception);
        }
    }

    public long insertTask(
            ScheduleSnapshot snapshot, AlgorithmType algorithmType, boolean primary,
            String parameters, long seed, String runtimeEnvironment) {
        String sql = """
                INSERT INTO t_schedule_task
                    (schedule_batch_no, algorithm_type, is_primary, dataset_id,
                     demand_snapshot, resource_snapshot, parameters, random_seed, repeat_index,
                     runtime_environment, status, progress)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'PENDING', 0)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, snapshot.batchNo());
            statement.setString(2, algorithmType.name());
            statement.setBoolean(3, primary);
            statement.setString(4, snapshot.datasetId());
            statement.setString(5, snapshot.demandSnapshot());
            statement.setString(6, snapshot.resourceSnapshot());
            statement.setString(7, parameters);
            statement.setLong(8, seed);
            statement.setString(9, runtimeEnvironment);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("SCHEDULE_TASK_KEY_MISSING");
        }
        return key.longValue();
    }

    public int failInterruptedTasks(LocalDate date, String reason, LocalDateTime finishedAt) {
        return jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'FAILED', progress = 100,
                    finished_at = COALESCE(finished_at, ?), error_message = ?
                WHERE status IN ('PENDING', 'RUNNING')
                  AND schedule_batch_no NOT LIKE 'EXP-%'
                  AND JSON_UNQUOTE(JSON_EXTRACT(demand_snapshot, '$.date')) = ?
                """, Timestamp.valueOf(finishedAt), reason, date.toString());
    }

    public int failBatchTasks(String batchNo, String reason, LocalDateTime finishedAt) {
        return jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'FAILED', progress = 100,
                    finished_at = COALESCE(finished_at, ?), error_message = ?
                WHERE schedule_batch_no = ? AND status IN ('PENDING', 'RUNNING')
                """, Timestamp.valueOf(finishedAt), reason, batchNo);
    }

    public boolean markRunning(long taskId, LocalDateTime startedAt) {
        return jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'RUNNING', progress = 10, started_at = ?, error_message = NULL
                WHERE task_id = ? AND status = 'PENDING'
                """, Timestamp.valueOf(startedAt), taskId) == 1;
    }

    public void completeTask(
            long taskId, ScheduleTaskStatus status, String errorMessage,
            LocalDateTime finishedAt, long durationMs) {
        int updated = jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = ?, progress = 100, finished_at = ?, duration_ms = ?, error_message = ?
                WHERE task_id = ? AND status = 'RUNNING'
                """, status.name(), Timestamp.valueOf(finishedAt), durationMs, errorMessage, taskId);
        if (updated != 1) {
            throw new IllegalStateException("SCHEDULE_TASK_STATE_CHANGED");
        }
    }

    public void insertResult(
            long taskId, long reservationId, ScheduleSeatedAssignment assignment,
            LocalDate date, String candidateSeatAllocations) {
        LocalDateTime start = assignment == null ? null : slotTime(date, assignment.startSlot());
        LocalDateTime end = assignment == null ? null : slotTime(date, assignment.endSlot());
        jdbcTemplate.update("""
                INSERT INTO t_schedule_result
                    (task_id, resv_id, assigned_lab_id, assigned_start_time, assigned_end_time,
                     objective_value, candidate_seat_allocations)
                VALUES (?, ?, ?, ?, ?, NULL, ?)
                """, taskId, reservationId, assignment == null ? null : assignment.labId(),
                start, end, candidateSeatAllocations);
    }

    public void insertExperiment(long taskId, AlgorithmType algorithmType, int dataScale, ScheduleRun run) {
        EvaluationResult evaluation = run.evaluation();
        long phaseOneMs = TimeUnit.NANOSECONDS.toMillis(evaluation.solveTime().phase1Nanos());
        long phaseTwoMs = TimeUnit.NANOSECONDS.toMillis(evaluation.solveTime().phase2Nanos());
        jdbcTemplate.update("""
                INSERT INTO t_algorithm_experiment
                    (task_id, algorithm_type, data_scale, allocation_success_rate,
                     average_seat_utilization, seat_preference_mismatch_rate, time_fragmentation_rate,
                     seat_allocation_failure_rate, phase_one_duration_ms, phase_two_duration_ms,
                     total_duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, taskId, algorithmType.name(), dataScale, evaluation.assignmentSuccessRate(),
                evaluation.averageSeatUtilization(), evaluation.softPreferenceMismatchRate(),
                evaluation.timeFragmentationRate(), run.seatAllocationFailureRate(),
                phaseOneMs, phaseTwoMs, phaseOneMs + phaseTwoMs);
    }

    public ScheduleTask findTask(long taskId) {
        List<ScheduleTask> tasks = jdbcTemplate.query(
                taskSql("WHERE task_id = ?"), this::mapTask, taskId);
        return tasks.isEmpty() ? null : tasks.getFirst();
    }

    public ScheduleTask lockTask(long taskId) {
        List<ScheduleTask> tasks = jdbcTemplate.query(
                taskSql("WHERE task_id = ? FOR UPDATE"), this::mapTask, taskId);
        if (tasks.isEmpty()) {
            throw new SchedulePublicationException("SCHEDULE_TASK_NOT_FOUND");
        }
        return tasks.getFirst();
    }

    public List<ScheduleTask> listTasks(String batchNo) {
        return jdbcTemplate.query(
                taskSql("WHERE schedule_batch_no = ? ORDER BY task_id"), this::mapTask, batchNo);
    }

    private static String taskSql(String suffix) {
        return """
                SELECT task_id, schedule_batch_no, algorithm_type, is_primary, dataset_id,
                       demand_snapshot, resource_snapshot, parameters, random_seed, repeat_index,
                       runtime_environment, status, progress, started_at, finished_at,
                       duration_ms, error_message, created_at
                FROM t_schedule_task
                """ + suffix;
    }

    private ScheduleTask mapTask(ResultSet rs, int rowNum) throws SQLException {
        return new ScheduleTask(
                rs.getLong("task_id"), rs.getString("schedule_batch_no"),
                AlgorithmType.valueOf(rs.getString("algorithm_type")), rs.getBoolean("is_primary"),
                rs.getString("dataset_id"), rs.getString("demand_snapshot"),
                rs.getString("resource_snapshot"), rs.getString("parameters"),
                rs.getObject("random_seed", Long.class), rs.getInt("repeat_index"),
                rs.getString("runtime_environment"),
                ScheduleTaskStatus.valueOf(rs.getString("status")), rs.getBigDecimal("progress"),
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class),
                rs.getObject("duration_ms", Long.class), rs.getString("error_message"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    public List<Long> snapshotDemandIds(String demandSnapshot) {
        return readJson(demandSnapshot, DemandSnapshot.class).demands().stream()
                .map(ScheduleDemand::resvId).toList();
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SCHEDULE_JSON_SERIALIZATION_FAILED", exception);
        }
    }

    public <T> T readJson(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SCHEDULE_JSON_DESERIALIZATION_FAILED", exception);
        }
    }

    private static LocalDateTime slotTime(LocalDate date, int slot) {
        return date.atStartOfDay().plusMinutes(slot * 30L);
    }

    public record DemandSnapshot(LocalDate date, List<ScheduleDemand> demands) {
    }

    public record ScheduleSnapshot(
            String batchNo, String datasetId, LocalDate date,
            String demandSnapshot, String resourceSnapshot, ScheduleProblem problem) {
    }

    public record ScheduleTask(
            long taskId, String scheduleBatchNo, AlgorithmType algorithmType, boolean primary,
            String datasetId, String demandSnapshot, String resourceSnapshot, String parameters,
            Long randomSeed, int repeatIndex, String runtimeEnvironment, ScheduleTaskStatus status,
            BigDecimal progress, LocalDateTime startedAt, LocalDateTime finishedAt,
            Long durationMs, String errorMessage, LocalDateTime createdAt) {
    }
}
