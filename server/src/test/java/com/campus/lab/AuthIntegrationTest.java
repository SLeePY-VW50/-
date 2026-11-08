package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class AuthIntegrationTest extends AbstractIntegrationTest {

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "sys_user", "sys_role", "sys_permission", "sys_user_role", "sys_role_permission",
            "t_lab", "t_lab_seat", "t_device", "t_time_rule", "t_slot_bitmap", "t_seat_bitmap",
            "t_sys_config", "t_reservation_batch", "t_reservation", "t_reservation_seat",
            "t_reservation_log", "t_violation", "t_credit_record", "t_appeal",
            "t_schedule_task", "t_schedule_result", "t_algorithm_experiment", "t_stat_daily");

    @Test
    void migrationCreatesTheExactSchemaAndReferenceDataContract() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("lab_reservation_test");

        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, String.class);
        assertThat(tables).hasSize(23);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);

        Set<String> reservationColumns = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 't_reservation'
                """, String.class).stream().collect(Collectors.toSet());
        assertThat(reservationColumns).contains(
                "batch_id", "user_id", "allocation_mode", "reservation_mode", "request_date",
                "request_window_start", "request_window_end", "requested_duration_minutes",
                "lab_id", "start_time", "end_time", "attendee_count", "seat_count",
                "purpose", "preferences",
                "checkin_channel", "checkin_time", "checkout_channel", "checkout_time",
                "status", "created_at", "updated_at");

        String bitmapType = jdbcTemplate.queryForObject("""
                SELECT column_type
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 't_slot_bitmap' AND column_name = 'mask'
                """, String.class);
        assertThat(bitmapType).isEqualTo("bigint unsigned");

        assertThat(jdbcTemplate.queryForList("SELECT role_code FROM sys_role", String.class))
                .containsExactlyInAnyOrder("STUDENT", "TEACHER", "LAB_ADMIN", "SYS_ADMIN");

        Map<String, String> config = jdbcTemplate.query(
                "SELECT config_key, config_value FROM t_sys_config",
                resultSet -> {
                    Map<String, String> values = new java.util.HashMap<>();
                    while (resultSet.next()) {
                        values.put(resultSet.getString(1), resultSet.getString(2));
                    }
                    return values;
                });
        assertThat(config)
                .containsEntry("credit.threshold", "60")
                .containsEntry("checkin.before.minutes", "15")
                .containsEntry("checkin.grace.minutes", "15")
                .containsEntry("checkin.choice.expire.seconds", "120")
                .containsEntry("checkin.max.failed.attempts", "5")
                .containsEntry("checkin.lockout.minutes", "10")
                .containsEntry("cancel.free.before.hours", "2")
                .containsEntry("reservation.duration.min.minutes", "30")
                .containsEntry("reservation.duration.max.minutes", "240")
                .containsEntry("reservation.advance.min.days", "1")
                .containsEntry("reservation.advance.max.days", "14")
                .containsEntry("schedule.primary.algorithm", "GREEDY")
                .containsEntry("schedule.batch.interval.minutes", "10")
                .containsEntry("ai.provider", "deepseek")
                .containsEntry("ai.model", "deepseek-chat")
                .containsEntry("ai.temperature", "0.2")
                .containsEntry("ai.timeout.seconds", "30")
                .containsEntry("ai.max.tokens", "1024")
                .containsEntry("ai.history.ttl.hours", "24")
                .containsEntry("ai.history.max.messages", "20");
        assertThat(config.keySet())
                .noneMatch(key -> key.toLowerCase().contains("secret")
                        || key.toLowerCase().contains("password")
                        || key.toLowerCase().contains("api.key"));
    }

    @Test
    void loginReturnsTokenAndUserRoles() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "account", "20240001",
                                "password", "Lab@2026"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.user.userId").isNumber())
                .andExpect(jsonPath("$.data.user.account").value("20240001"))
                .andExpect(jsonPath("$.data.user.name").isNotEmpty())
                .andExpect(jsonPath("$.data.user.roles[0]").value("STUDENT"))
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist());
    }

    @Test
    void jwtContainsOnlySubjectRolesAndExpiration() throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");
        String encodedPayload = token.split("\\.")[1];
        JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(encodedPayload));
        List<String> claimNames = new ArrayList<>();
        payload.fieldNames().forEachRemaining(claimNames::add);

        assertThat(claimNames).containsExactlyInAnyOrder("sub", "roles", "exp");
        assertThat(payload.path("sub").asText())
                .isEqualTo(Long.toString(userIdByAccount("20240001")));
        assertThat(payload.path("roles").get(0).asText()).isEqualTo("STUDENT");
        assertThat(payload.path("exp").isNumber()).isTrue();
    }

    @Test
    void invalidCredentialsUseTheSameUnauthorizedResponse() throws Exception {
        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "account", "20240001",
                                "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult unknownAccount = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "account", "not-existing",
                                "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(wrongPassword.getResponse().getContentAsString())
                .isEqualTo(unknownAccount.getResponse().getContentAsString());
    }

    @Test
    void meReturnsTheCurrentUser() throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(userIdByAccount("20240001")))
                .andExpect(jsonPath("$.data.account").value("20240001"))
                .andExpect(jsonPath("$.data.roles[0]").value("STUDENT"));
    }

    @Test
    void disabledAccountInvalidatesAnAlreadyIssuedToken() throws Exception {
        String account = createTestAccount("TEACHER");
        String token = loginAndGetToken(account, "Lab@2026");
        long userId = userIdByAccount(account);

        jdbcTemplate.update("UPDATE sys_user SET status = 'DISABLED' WHERE user_id = ?", userId);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void localDemoDataCreatesResourcesAndStoresOnlyHashes() {
        createNonDemoResourceData();
        String demoAccounts = "'20240001', 'T1001', 'L1001', 'A1001'";
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE account IN (" + demoAccounts + ")", Integer.class))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForList(
                "SELECT account FROM sys_user WHERE account IN (" + demoAccounts + ")", String.class))
                .containsExactlyInAnyOrder("20240001", "T1001", "L1001", "A1001");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE account IN (" + demoAccounts + ") "
                        + "AND password_hash NOT LIKE '$2%'", Integer.class))
                .isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_lab WHERE lab_code IN ('LAB-A', 'LAB-B')", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_lab_seat s
                JOIN t_lab l ON l.lab_id = s.lab_id WHERE l.lab_code = 'LAB-A'
                """, Integer.class)).isGreaterThanOrEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_lab_seat s
                JOIN t_lab l ON l.lab_id = s.lab_id WHERE l.lab_code = 'LAB-B'
                """, Integer.class)).isGreaterThanOrEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT s.zone_code) FROM t_lab_seat s
                JOIN t_lab l ON l.lab_id = s.lab_id
                WHERE l.lab_code IN ('LAB-A', 'LAB-B')
                """, Integer.class)).isGreaterThanOrEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT s.spec_level) FROM t_lab_seat s
                JOIN t_lab l ON l.lab_id = s.lab_id
                WHERE l.lab_code IN ('LAB-A', 'LAB-B')
                """, Integer.class)).isGreaterThanOrEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT s.near_window) FROM t_lab_seat s
                JOIN t_lab l ON l.lab_id = s.lab_id
                WHERE l.lab_code IN ('LAB-A', 'LAB-B')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_time_rule r
                JOIN t_lab l ON l.lab_id = r.lab_id
                WHERE l.lab_code IN ('LAB-A', 'LAB-B')
                """, Integer.class)).isEqualTo(14);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_time_rule r
                JOIN t_lab l ON l.lab_id = r.lab_id
                WHERE l.lab_code IN ('LAB-A', 'LAB-B')
                  AND (r.open_time <> '08:00:00' OR r.close_time <> '22:00:00')
                """, Integer.class)).isZero();

        String terminalHash = jdbcTemplate.queryForObject("""
                SELECT terminal_secret_hash FROM t_device
                WHERE terminal_code = 'LAB-A-TERM' AND device_type = 'CHECKIN_TERMINAL'
                """, String.class);
        assertThat(terminalHash).startsWith("$2").hasSize(60);
        assertThat(terminalHash).isNotEqualTo("Terminal@2026");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation r
                JOIN sys_user u ON u.user_id = r.user_id
                WHERE u.account IN (""" + demoAccounts + ")", Integer.class))
                .isZero();
    }

    private void createNonDemoResourceData() {
        String otherAccount = createTestAccount("STUDENT");
        long otherUserId = userIdByAccount(otherAccount);
        jdbcTemplate.update("""
                INSERT INTO t_lab (lab_code, name, location, capacity, status)
                VALUES ('LAB-OTHER', '非演示实验室', '其他位置', 1, 'ACTIVE')
                """);
        Long otherLabId = jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = 'LAB-OTHER'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO t_time_rule (lab_id, day_of_week, open_time, close_time)
                VALUES (?, 1, '09:00:00', '21:00:00')
                """, otherLabId);
        jdbcTemplate.update("""
                INSERT INTO t_lab_seat
                    (lab_id, seat_code, zone_code, seat_index, spec_level, near_window, seat_status)
                VALUES (?, 'OTHER-SEAT', 'OTHER-ZONE', 1, 'OTHER-SPEC', FALSE, 'ACTIVE')
                """, otherLabId);
        jdbcTemplate.update("""
                INSERT INTO t_reservation
                    (user_id, allocation_mode, reservation_mode, request_date,
                     request_window_start, request_window_end, requested_duration_minutes,
                     lab_id, start_time, end_time, attendee_count, seat_count, purpose, status)
                VALUES (?, 'FIXED', 'SEAT', '2026-11-02', '09:00:00', '10:00:00', 60,
                        ?, '2026-11-02 09:00:00', '2026-11-02 10:00:00', 1, 1,
                        'non-demo-fixture', 'APPROVED')
                """, otherUserId, otherLabId);
    }

    @Test
    void unsupportedHttpMethodReturns405() throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/auth/login")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));
    }

    @Test
    void unsupportedMediaTypeReturns415() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(415));
    }

    @Test
    void springMvcTestsUseTransactionalRollbackFixture() {
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()).isTrue();
        assertThat(org.springframework.test.context.transaction.TestTransaction
                .isFlaggedForRollback()).isTrue();
    }
}
