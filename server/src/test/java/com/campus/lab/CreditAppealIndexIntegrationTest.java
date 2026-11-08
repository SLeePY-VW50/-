package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreditAppealIndexIntegrationTest extends AbstractReservationNonTransactionalTest {

    private static final String PURPOSE = "信用管理索引计划测试";

    @BeforeEach
    void createRepresentativeData() {
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        jdbcTemplate.update("""
                INSERT INTO t_reservation
                    (user_id, allocation_mode, reservation_mode, request_date,
                     request_window_start, request_window_end, requested_duration_minutes,
                     attendee_count, seat_count, purpose, status)
                SELECT ?, 'SCHEDULABLE', 'SEAT', '2026-11-02', '08:00:00', '12:00:00',
                       60, 1, 1, ?, 'CANCELLED'
                FROM (
                    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
                    UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
                    UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
                ) ones
                CROSS JOIN (
                    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
                    UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
                    UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
                ) tens
                CROSS JOIN (
                    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
                    UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
                    UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
                ) hundreds
                CROSS JOIN (
                    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
                    UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
                    UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
                ) thousands
                """, userId, PURPOSE);
        jdbcTemplate.update("""
                INSERT INTO t_violation (user_id, resv_id, violation_type, deduct_points)
                SELECT ?, resv_id, 'LATE_CANCEL', 10 FROM t_reservation
                WHERE user_id = ? AND purpose = ?
                """, userId, userId, PURPOSE);
        jdbcTemplate.update("""
                INSERT INTO t_appeal (violation_id, user_id, reason, status, created_at)
                SELECT v.violation_id, ?, '管理索引计划测试',
                       CASE MOD(v.violation_id, 3)
                           WHEN 0 THEN 'PENDING'
                           WHEN 1 THEN 'APPROVED'
                           ELSE 'REJECTED'
                       END,
                       DATE_ADD('2026-01-01 00:00:00', INTERVAL v.violation_id SECOND)
                FROM t_violation v
                JOIN t_reservation r ON r.resv_id = v.resv_id
                WHERE r.user_id = ? AND r.purpose = ?
                """, userId, userId, PURPOSE);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_appeal WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(10000);
        jdbcTemplate.execute("ANALYZE TABLE t_appeal");
    }

    @Test
    void statusFilteredAdminQueryUsesStatusCreatedIdIndexWithoutFilesort() {
        Map<String, Object> plan = explain("""
                SELECT appeal_id, violation_id, user_id, reason, status,
                       reviewer_id, review_opinion, reviewed_at, created_at
                FROM t_appeal WHERE status = 'PENDING'
                ORDER BY created_at DESC, appeal_id DESC LIMIT 20 OFFSET 0
                """);

        assertThat(planValue(plan, "key")).isEqualTo("idx_t_appeal_status_created_id");
        assertThat(planValue(plan, "Extra")).doesNotContain("Using filesort");
    }

    @Test
    void unfilteredAdminQueryUsesCreatedIdIndexWithoutFilesort() {
        Map<String, Object> plan = explain("""
                SELECT appeal_id, violation_id, user_id, reason, status,
                       reviewer_id, review_opinion, reviewed_at, created_at
                FROM t_appeal
                ORDER BY created_at DESC, appeal_id DESC LIMIT 20 OFFSET 0
                """);

        assertThat(planValue(plan, "key")).isEqualTo("idx_t_appeal_created_id");
        assertThat(planValue(plan, "Extra")).doesNotContain("Using filesort");
    }

    private Map<String, Object> explain(String selectSql) {
        Map<String, Object> plan = jdbcTemplate.queryForMap("EXPLAIN " + selectSql);
        assertThat(planValue(plan, "key")).isNotNull();
        return plan;
    }

    private static String planValue(Map<String, Object> plan, String column) {
        for (Map.Entry<String, Object> entry : plan.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue() == null ? null : entry.getValue().toString();
            }
        }
        return null;
    }
}
