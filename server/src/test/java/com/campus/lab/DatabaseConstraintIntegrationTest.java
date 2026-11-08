package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

class DatabaseConstraintIntegrationTest extends AbstractIntegrationTest {

    @Test
    void flywayAppliesOriginalSchemaReferenceDataAndScheduleIntegrityInOrder() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        String descriptions = jdbcTemplate.queryForObject(
                "SELECT GROUP_CONCAT(description ORDER BY installed_rank) FROM flyway_schema_history",
                String.class);
        assertThat(descriptions).isEqualTo(
                "schema,reference data,schedule integrity,reservation seat count,reservation end of day,"
                        + "credit appeal integrity,checkout web channel,reservation lifecycle scan indexes,"
                        + "schedule publication states,statistics history and indexes");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE", Integer.class))
                .isZero();
    }

    @Test
    void lifecycleScanIndexesHaveCursorPrefixAndCoveringColumns() {
        Map<String, List<String>> indexes = jdbcTemplate.query("""
                SELECT index_name, column_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 't_reservation'
                  AND index_name IN (
                      'idx_t_reservation_no_show_scan',
                      'idx_t_reservation_ended_scan',
                      'idx_t_reservation_pending_expiry_scan')
                ORDER BY index_name, seq_in_index
                """, resultSet -> {
            Map<String, List<String>> values = new java.util.LinkedHashMap<>();
            while (resultSet.next()) {
                values.computeIfAbsent(resultSet.getString(1), key -> new java.util.ArrayList<>())
                        .add(resultSet.getString(2));
            }
            return values;
        });

        assertThat(indexes)
                .containsEntry("idx_t_reservation_no_show_scan",
                        List.of("status", "resv_id", "start_time", "checkin_time"))
                .containsEntry("idx_t_reservation_ended_scan",
                        List.of("status", "resv_id", "end_time"))
                .containsEntry("idx_t_reservation_pending_expiry_scan",
                        List.of("status", "resv_id", "request_date", "request_window_end"));
    }

    @Test
    void webAccountCheckoutChannelIsAcceptedAlongsideExistingChannels() {
        long reservationId = insertReservation("SCHEDULABLE", "PENDING_SCHEDULE", false);

        assertThatCode(() -> jdbcTemplate.update("""
                UPDATE t_reservation
                SET checkout_channel = 'WEB_ACCOUNT', checkout_time = ?
                WHERE resv_id = ?
                """, LocalDateTime.of(2026, 11, 1, 9, 30), reservationId))
                .doesNotThrowAnyException();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT checkout_channel FROM t_reservation WHERE resv_id = ?",
                String.class, reservationId)).isEqualTo("WEB_ACCOUNT");
    }

    @Test
    void restoreIsUniquePerViolationAndNullViolationIdsRemainRepeatable() {
        long reservationId = insertReservation("SCHEDULABLE", "PENDING_SCHEDULE", false);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM sys_user WHERE account = '20240001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO t_violation (user_id, resv_id, violation_type, deduct_points)
                VALUES (?, ?, 'LATE_CANCEL', 10)
                """, userId, reservationId);
        Long violationId = jdbcTemplate.queryForObject(
                "SELECT violation_id FROM t_violation WHERE resv_id = ?", Long.class, reservationId);
        String insertRestore = """
                INSERT INTO t_credit_record
                    (user_id, violation_id, change_type, change_amount, balance_before, balance_after)
                VALUES (?, ?, 'RESTORE', 0, 100, 100)
                """;
        jdbcTemplate.update(insertRestore, userId, violationId);

        assertThatThrownBy(() -> jdbcTemplate.update(insertRestore, userId, violationId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_t_credit_record_violation_type");

        jdbcTemplate.update(insertRestore, userId, null);
        jdbcTemplate.update(insertRestore, userId, null);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_credit_record
                WHERE user_id = ? AND violation_id IS NULL AND change_type = 'RESTORE'
                """, Integer.class, userId)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "IN_USE", "COMPLETED", "NO_SHOW", "VIOLATION_TERMINATED"})
    void schedulableAssignedStatesRejectMissingFinalAllocation(String status) {
        assertThatThrownBy(() -> insertReservation("SCHEDULABLE", status, false))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_t_reservation_schedule");
    }

    @Test
    void reservationChecksAcceptEveryAllowedAllocationOrigin() {
        assertThatCode(() -> {
            insertReservation("SCHEDULABLE", "PENDING_SCHEDULE", false);
            insertReservation("SCHEDULABLE", "APPROVED", true);
            for (String status : new String[] {"CANCELLED", "EXPIRED"}) {
                insertReservation("SCHEDULABLE", status, false);
                insertReservation("SCHEDULABLE", status, true);
            }
            insertReservation("FIXED", "APPROVED", true);
        }).doesNotThrowAnyException();

        assertThatThrownBy(() -> insertReservation("FIXED", "PENDING_SCHEDULE", true))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_t_reservation_schedule");
    }

    @Test
    void publishedScheduleResultRequiresPublicationTimestamp() {
        assertThatThrownBy(() -> insertScheduleResult(true, false))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_t_schedule_result_publication_time");
    }

    @Test
    void scheduleResultAcceptsValidPublicationStates() {
        assertThatCode(() -> {
            insertScheduleResult(false, false);
            insertScheduleResult(true, true);
        }).doesNotThrowAnyException();
    }

    private long insertReservation(String allocationMode, String status, boolean assigned) {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM sys_user WHERE account = '20240001'", Long.class);
        Long labId = assigned ? jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = 'LAB-A'", Long.class) : null;
        String purpose = "constraint-test-" + UUID.randomUUID();
        String windowStart = allocationMode.equals("FIXED") ? "09:00:00" : "08:00:00";
        String windowEnd = allocationMode.equals("FIXED") ? "10:00:00" : "12:00:00";
        String startTime = assigned ? "2026-11-01 09:00:00" : null;
        String endTime = assigned ? "2026-11-01 10:00:00" : null;

        jdbcTemplate.update("""
                INSERT INTO t_reservation
                    (user_id, allocation_mode, reservation_mode, request_date,
                     request_window_start, request_window_end, requested_duration_minutes,
                     lab_id, start_time, end_time, attendee_count, seat_count, purpose, status)
                VALUES (?, ?, 'SEAT', '2026-11-01', ?, ?, 60, ?, ?, ?, 1, 1, ?, ?)
                """, userId, allocationMode, windowStart, windowEnd,
                labId, startTime, endTime, purpose, status);
        Long reservationId = jdbcTemplate.queryForObject(
                "SELECT resv_id FROM t_reservation WHERE purpose = ?", Long.class, purpose);
        if (reservationId == null) {
            throw new IllegalStateException("测试预约插入失败");
        }
        return reservationId;
    }

    private void insertScheduleResult(boolean published, boolean withTimestamp) {
        long reservationId = insertReservation("SCHEDULABLE", "APPROVED", true);
        String batchNumber = "constraint-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO t_schedule_task
                    (schedule_batch_no, algorithm_type, is_primary, dataset_id,
                     demand_snapshot, resource_snapshot, parameters, runtime_environment, status)
                VALUES (?, 'GREEDY', TRUE, ?, '{}', '{}', '{}', '{}', 'PENDING')
                """, batchNumber, batchNumber);
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT task_id FROM t_schedule_task WHERE schedule_batch_no = ?", Long.class, batchNumber);
        LocalDateTime publishedAt = withTimestamp ? LocalDateTime.of(2026, 11, 1, 10, 0) : null;

        jdbcTemplate.update("""
                INSERT INTO t_schedule_result
                    (task_id, resv_id, assigned_lab_id, assigned_start_time, assigned_end_time,
                     is_published, published_at)
                SELECT ?, r.resv_id, r.lab_id, r.start_time, r.end_time, ?, ?
                FROM t_reservation r WHERE r.resv_id = ?
                """, taskId, published, publishedAt, reservationId);
    }
}
