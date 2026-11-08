package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class ReservationMigrationUpgradeIntegrationTest {

    private static final String DATABASE = "lab_reservation_upgrade_test";
    private static final String ADMIN_USER =
            System.getenv().getOrDefault("LAB_MYSQL_ADMIN_USER", "lab_admin");
    private static final String ADMIN_URL =
            "jdbc:mysql://127.0.0.1:3307/?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String DATABASE_URL =
            "jdbc:mysql://127.0.0.1:3307/" + DATABASE + "?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";

    @Test
    void v4BackfillsAssignedAndPendingRowsBeforeAddingConstraints() throws Exception {
        String administratorPassword = requiredAdministratorPassword();
        createDedicatedDatabase(administratorPassword);
        try {
            migrate(administratorPassword, "3");
            try (Connection connection = connect(DATABASE_URL, administratorPassword);
                    Statement statement = connection.createStatement()) {
                insertBaseData(statement);
                statement.executeUpdate("""
                        INSERT INTO t_reservation
                            (resv_id, user_id, allocation_mode, reservation_mode, request_date,
                             request_window_start, request_window_end, requested_duration_minutes,
                             lab_id, start_time, end_time, attendee_count, purpose, status)
                        VALUES
                            (1, 1, 'FIXED', 'SEAT', '2026-11-02', '09:00:00', '10:00:00', 60,
                             1, '2026-11-02 09:00:00', '2026-11-02 10:00:00', 1,
                             '历史正式分配SEAT', 'APPROVED'),
                            (2, 1, 'SCHEDULABLE', 'SEAT', '2026-11-02', '08:00:00', '12:00:00', 60,
                             NULL, NULL, NULL, 2, '历史待排期SEAT', 'PENDING_SCHEDULE')
                        """);
                statement.executeUpdate("""
                        INSERT INTO t_reservation_seat (resv_id, seat_id, time_mask, assignment_method)
                        VALUES (1, 1, 786432, 'FIXED')
                        """);
            }

            migrate(administratorPassword, null);

            try (Connection connection = connect(DATABASE_URL, administratorPassword);
                    Statement statement = connection.createStatement()) {
                assertThat(queryInt(statement,
                        "SELECT seat_count FROM t_reservation WHERE resv_id = 1")).isEqualTo(1);
                assertThat(queryInt(statement,
                        "SELECT seat_count FROM t_reservation WHERE resv_id = 2")).isEqualTo(2);
                assertThat(queryInt(statement,
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE")).isZero();
                List<String> versions = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT version FROM flyway_schema_history ORDER BY installed_rank")) {
                    while (resultSet.next()) {
                        versions.add(resultSet.getString(1));
                    }
                }
                assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
                assertThat(queryInt(statement, """
                        SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                        WHERE table_schema = DATABASE()
                          AND index_name IN (
                              'idx_t_appeal_status_created_id',
                              'idx_t_appeal_created_id',
                              'uk_t_credit_record_violation_type')
                        """)).isEqualTo(3);
                assertThat(queryInt(statement, """
                        SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND table_name = 't_reservation'
                          AND index_name IN (
                              'idx_t_reservation_no_show_scan',
                              'idx_t_reservation_ended_scan',
                              'idx_t_reservation_pending_expiry_scan')
                        """)).isEqualTo(3);
                assertThat(queryInt(statement, """
                        SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND table_name = 't_reservation'
                          AND index_name IN (
                              'idx_t_reservation_request_date',
                              'idx_t_reservation_lab_request_date',
                              'idx_t_reservation_user_request_date')
                        """)).isEqualTo(3);
                assertThat(queryInt(statement, """
                        SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = 't_stat_daily'
                          AND column_name = 'available_seat_minutes' AND is_nullable = 'YES'
                        """)).isOne();
                try (ResultSet resultSet = statement.executeQuery("""
                        EXPLAIN SELECT resv_id FROM t_reservation
                        WHERE request_date = '2026-11-02'
                        """)) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString("key")).isEqualTo("idx_t_reservation_request_date");
                }
                statement.executeUpdate("""
                        UPDATE t_reservation
                        SET checkout_channel = 'WEB_ACCOUNT', checkout_time = '2026-11-02 09:30:00'
                        WHERE resv_id = 1
                        """);
                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT checkout_channel FROM t_reservation WHERE resv_id = 1")) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString(1)).isEqualTo("WEB_ACCOUNT");
                }
                assertThatThrownBy(() -> statement.executeUpdate("""
                        INSERT INTO t_reservation
                            (resv_id, user_id, allocation_mode, reservation_mode, request_date,
                             request_window_start, request_window_end, requested_duration_minutes,
                             lab_id, start_time, end_time, attendee_count, seat_count, purpose, status)
                        VALUES
                            (3, 1, 'FIXED', 'SEAT', '2026-11-03', '09:00:00', '10:00:00', 60,
                             1, '2026-11-03 09:00:00', '2026-11-03 10:00:00', 2, 1,
                             '人数与seatCount不一致', 'APPROVED')
                        """)).hasMessageContaining("ck_t_reservation_seat_count");
                statement.executeUpdate("""
                        INSERT INTO t_schedule_task
                            (task_id, schedule_batch_no, algorithm_type, is_primary, dataset_id,
                             demand_snapshot, resource_snapshot, parameters, runtime_environment, status)
                        VALUES
                            (1, 'UPGRADE-SCHEDULE', 'FCFS', FALSE, 'UPGRADE-DATASET',
                             '{"date":"2026-11-02","demands":[]}', '[]', '{}', '{}', 'PUBLISHED'),
                            (2, 'UPGRADE-SCHEDULE', 'GREEDY', TRUE, 'UPGRADE-DATASET',
                             '{"date":"2026-11-02","demands":[]}', '[]', '{}', '{}', 'PUBLISH_FAILED')
                        """);
                assertThat(queryInt(statement, """
                        SELECT COUNT(*) FROM t_schedule_task
                        WHERE schedule_batch_no = 'UPGRADE-SCHEDULE'
                          AND status IN ('PUBLISHED', 'PUBLISH_FAILED')
                        """)).isEqualTo(2);
                assertThat(queryInt(statement, """
                        SELECT COALESCE(MAX(non_unique), 0) FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND table_name = 't_schedule_task'
                          AND index_name = 'idx_t_schedule_task_batch_algorithm'
                        """)).isEqualTo(1);
                assertThat(queryInt(statement, """
                        SELECT COUNT(*) FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                          AND table_name <> 'flyway_schema_history'
                        """)).isEqualTo(23);
            }
        } finally {
            dropDedicatedDatabase(administratorPassword);
        }
    }

    @Test
    void v4BackfillsActualAssignmentCountThenRejectsMismatchedAttendees() throws Exception {
        String administratorPassword = requiredAdministratorPassword();
        createDedicatedDatabase(administratorPassword);
        try {
            migrate(administratorPassword, "3");
            try (Connection connection = connect(DATABASE_URL, administratorPassword);
                    Statement statement = connection.createStatement()) {
                insertBaseData(statement);
                statement.executeUpdate("""
                        INSERT INTO t_reservation
                            (resv_id, user_id, allocation_mode, reservation_mode, request_date,
                             request_window_start, request_window_end, requested_duration_minutes,
                             lab_id, start_time, end_time, attendee_count, purpose, status)
                        VALUES
                            (1, 1, 'FIXED', 'SEAT', '2026-11-02', '09:00:00', '10:00:00', 60,
                             1, '2026-11-02 09:00:00', '2026-11-02 10:00:00', 2,
                             '历史人数分配不一致SEAT', 'APPROVED')
                        """);
                statement.executeUpdate("""
                        INSERT INTO t_reservation_seat (resv_id, seat_id, time_mask, assignment_method)
                        VALUES (1, 1, 786432, 'FIXED')
                        """);
            }

            assertThatThrownBy(() -> migrate(administratorPassword, null))
                    .hasMessageContaining("ck_t_reservation_seat_count");

            try (Connection connection = connect(DATABASE_URL, administratorPassword);
                    Statement statement = connection.createStatement()) {
                assertThat(queryInt(statement,
                        "SELECT seat_count FROM t_reservation WHERE resv_id = 1")).isEqualTo(1);
                assertThat(queryInt(statement,
                        "SELECT attendee_count FROM t_reservation WHERE resv_id = 1")).isEqualTo(2);
            }
        } finally {
            dropDedicatedDatabase(administratorPassword);
        }
    }

    @Test
    void v4BackfillsCancelledAndExpiredSeatRowsWithoutAssignments() throws Exception {
        String administratorPassword = requiredAdministratorPassword();
        createDedicatedDatabase(administratorPassword);
        try {
            migrate(administratorPassword, "3");
            try (Connection connection = connect(DATABASE_URL, administratorPassword);
                    Statement statement = connection.createStatement()) {
                insertBaseData(statement);
                statement.executeUpdate("""
                        INSERT INTO t_reservation
                            (resv_id, user_id, allocation_mode, reservation_mode, request_date,
                             request_window_start, request_window_end, requested_duration_minutes,
                             lab_id, start_time, end_time, attendee_count, purpose, status)
                        VALUES
                            (1, 1, 'SCHEDULABLE', 'SEAT', '2026-11-02', '08:00:00', '12:00:00', 60,
                             NULL, NULL, NULL, 2, '历史取消无分配SEAT', 'CANCELLED'),
                            (2, 1, 'SCHEDULABLE', 'SEAT', '2026-11-02', '08:00:00', '12:00:00', 60,
                             NULL, NULL, NULL, 1, '历史过期无分配SEAT', 'EXPIRED')
                        """);
            }

            migrate(administratorPassword, null);

            try (Connection connection = connect(DATABASE_URL, administratorPassword);
                    Statement statement = connection.createStatement()) {
                assertThat(queryInt(statement,
                        "SELECT seat_count FROM t_reservation WHERE resv_id = 1")).isEqualTo(2);
                assertThat(queryInt(statement,
                        "SELECT seat_count FROM t_reservation WHERE resv_id = 2")).isEqualTo(1);
                assertThat(queryInt(statement,
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE")).isZero();
                assertThat(queryInt(statement,
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('4', '5', '6')"))
                        .isEqualTo(3);
            }
        } finally {
            dropDedicatedDatabase(administratorPassword);
        }
    }

    @Test
    void v9PreservesDuplicateBatchAlgorithmsAndAddsNonUniqueIndex() throws Exception {
        String administratorPassword = requiredAdministratorPassword();
        createDedicatedDatabase(administratorPassword);
        try {
            migrate(administratorPassword, "8");
            try (Connection connection = connect(DATABASE_URL, administratorPassword);
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO t_schedule_task
                            (task_id, schedule_batch_no, algorithm_type, is_primary, dataset_id,
                             demand_snapshot, resource_snapshot, parameters, runtime_environment)
                        VALUES
                            (1, 'DUP-BATCH', 'FCFS', TRUE, 'DUP-DATASET',
                             '{"date":"2026-11-02","demands":[]}', '[]', '{}', '{}'),
                            (2, 'DUP-BATCH', 'FCFS', FALSE, 'DUP-DATASET',
                             '{"date":"2026-11-02","demands":[]}', '[]', '{}', '{}')
                        """);
            }

            migrate(administratorPassword, "9");

            try (Connection connection = connect(DATABASE_URL, administratorPassword);
                    Statement statement = connection.createStatement()) {
                assertThat(queryInt(statement,
                        "SELECT COUNT(*) FROM t_schedule_task WHERE task_id IN (1, 2)"))
                        .isEqualTo(2);
                assertThat(queryInt(statement, """
                        SELECT COALESCE(MAX(non_unique), 0) FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND table_name = 't_schedule_task'
                          AND index_name = 'idx_t_schedule_task_batch_algorithm'
                        """)).isEqualTo(1);
                statement.executeUpdate("""
                        INSERT INTO t_schedule_task
                            (schedule_batch_no, algorithm_type, dataset_id,
                             demand_snapshot, resource_snapshot, parameters, runtime_environment)
                        VALUES
                            ('DUP-BATCH', 'FCFS', 'DUP-DATASET', '[]', '[]', '{}', '{}')
                        """);
                assertThat(queryInt(statement,
                        "SELECT COUNT(*) FROM t_schedule_task WHERE schedule_batch_no = 'DUP-BATCH'"))
                        .isEqualTo(3);
                assertThat(queryInt(statement, """
                        SELECT COUNT(*) FROM flyway_schema_history WHERE version = '9' AND success = TRUE
                        """)).isEqualTo(1);
            }
        } finally {
            dropDedicatedDatabase(administratorPassword);
        }
    }

    private static String requiredAdministratorPassword() {
        String password = System.getenv("LAB_MYSQL_ROOT_PASSWORD");
        assertThat(password)
                .withFailMessage("缺少 LAB_MYSQL_ROOT_PASSWORD，无法创建并清理专用升级数据库 %s", DATABASE)
                .isNotBlank();
        return password;
    }

    private static void createDedicatedDatabase(String password) throws SQLException {
        try (Connection connection = connect(ADMIN_URL, password);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS `" + DATABASE + "`");
            statement.executeUpdate(
                    "CREATE DATABASE `" + DATABASE + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    private static void dropDedicatedDatabase(String password) throws SQLException {
        try (Connection connection = connect(ADMIN_URL, password);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS `" + DATABASE + "`");
        }
    }

    private static void migrate(String password, String target) {
        var configuration = Flyway.configure()
                .dataSource(DATABASE_URL, ADMIN_USER, password)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        configuration.load().migrate();
    }

    private static Connection connect(String url, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", ADMIN_USER);
        properties.setProperty("password", password);
        try {
            return DriverManager.getConnection(url, properties);
        } catch (SQLException exception) {
            throw new SQLException(
                    "LAB_MYSQL_ROOT_PASSWORD 无法用于 MySQL 管理员连接，请检查账号、密码和权限",
                    exception);
        }
    }

    private static void insertBaseData(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO sys_user (user_id, account, name, password_hash, credit_score, status)
                VALUES (1, 'upgrade-user', '升级用户', 'not-a-login-credential', 100, 'ACTIVE')
                """);
        statement.executeUpdate("""
                INSERT INTO t_lab (lab_id, lab_code, name, location, capacity, status)
                VALUES (1, 'UPGRADE-LAB', '升级实验室', '专用测试库', 2, 'ACTIVE')
                """);
        statement.executeUpdate("""
                INSERT INTO t_lab_seat
                    (seat_id, lab_id, seat_code, zone_code, seat_index,
                     spec_level, near_window, seat_status)
                VALUES (1, 1, 'UPGRADE-SEAT', 'ZONE', 1, 'STANDARD', FALSE, 'ACTIVE')
                """);
    }

    private static int queryInt(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }
}
