package com.campus.lab.schedule;

import com.campus.lab.schedule.dto.ScheduleExperimentView;
import com.campus.lab.schedule.dto.ScheduleResultView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ScheduleQueryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ScheduleQueryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public long countBatches() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT schedule_batch_no) FROM t_schedule_task
                WHERE schedule_batch_no NOT LIKE 'EXP-%'
                """, Long.class);
        return count == null ? 0 : count;
    }

    public List<com.campus.lab.schedule.dto.ScheduleBatchView> listBatches(int page, int size) {
        return jdbcTemplate.query(batchSql("""
                GROUP BY t.schedule_batch_no
                ORDER BY MIN(t.created_at) DESC, t.schedule_batch_no DESC
                LIMIT ? OFFSET ?
                """), this::mapBatch, size, (long) page * size);
    }

    public com.campus.lab.schedule.dto.ScheduleBatchView findBatch(String batchNo) {
        List<com.campus.lab.schedule.dto.ScheduleBatchView> batches = jdbcTemplate.query(
                batchSql("AND t.schedule_batch_no = ? GROUP BY t.schedule_batch_no"),
                this::mapBatch, batchNo);
        return batches.isEmpty() ? null : batches.getFirst();
    }

    private static String batchSql(String suffix) {
        return """
                SELECT t.schedule_batch_no, MAX(t.dataset_id) AS dataset_id,
                       MAX(JSON_UNQUOTE(JSON_EXTRACT(t.demand_snapshot, '$.date'))) AS request_date,
                       MAX(CASE WHEN t.is_primary = TRUE THEN t.task_id END) AS primary_task_id,
                       MAX(CASE WHEN t.is_primary = TRUE THEN t.status END) AS primary_status,
                       COUNT(DISTINCT t.task_id) AS task_count,
                       MIN(t.created_at) AS created_at,
                       MAX(r.published_at) AS published_at
                FROM t_schedule_task t
                LEFT JOIN t_schedule_result r ON r.task_id = t.task_id
                WHERE t.schedule_batch_no NOT LIKE 'EXP-%'
                """ + suffix;
    }

    private com.campus.lab.schedule.dto.ScheduleBatchView mapBatch(ResultSet rs, int rowNum)
            throws SQLException {
        return new com.campus.lab.schedule.dto.ScheduleBatchView(
                rs.getString("schedule_batch_no"), rs.getString("dataset_id"),
                LocalDate.parse(rs.getString("request_date")), rs.getLong("primary_task_id"),
                ScheduleTaskStatus.valueOf(rs.getString("primary_status")), rs.getInt("task_count"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("published_at", LocalDateTime.class));
    }

    public ScheduleExperimentView findExperiment(long taskId) {
        List<ScheduleExperimentView> experiments = jdbcTemplate.query("""
                SELECT allocation_success_rate, average_seat_utilization,
                       seat_preference_mismatch_rate, time_fragmentation_rate,
                       seat_allocation_failure_rate, phase_one_duration_ms,
                       phase_two_duration_ms, total_duration_ms
                FROM t_algorithm_experiment WHERE task_id = ?
                """, (rs, rowNum) -> new ScheduleExperimentView(
                        rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)), taskId);
        return experiments.isEmpty() ? null : experiments.getFirst();
    }

    public List<ScheduleResultView> listResults(long taskId) {
        return jdbcTemplate.query("""
                SELECT result_id, resv_id, assigned_lab_id, assigned_start_time, assigned_end_time,
                       objective_value, candidate_seat_allocations, is_published, published_at
                FROM t_schedule_result WHERE task_id = ? ORDER BY resv_id
                """, (rs, rowNum) -> new ScheduleResultView(
                        rs.getLong("result_id"), rs.getLong("resv_id"),
                        rs.getObject("assigned_lab_id", Long.class),
                        rs.getObject("assigned_start_time", LocalDateTime.class),
                        rs.getObject("assigned_end_time", LocalDateTime.class),
                        rs.getObject("objective_value", Double.class),
                        readTree(rs.getString("candidate_seat_allocations")),
                        rs.getBoolean("is_published"),
                        rs.getObject("published_at", LocalDateTime.class)), taskId);
    }

    public JsonNode readTree(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SCHEDULE_QUERY_JSON_INVALID", exception);
        }
    }
}
