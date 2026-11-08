package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class ResourceIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Test
    void everyAuthenticatedRoleCanListPublicLabProfiles() throws Exception {
        for (String account : List.of("20240001", "T1001", "L1001", "A1001")) {
            String token = loginAndGetToken(account, "Lab@2026");
            MvcResult result = mockMvc.perform(get("/api/labs")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").isArray())
                    .andReturn();

            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.path("data").size()).isGreaterThanOrEqualTo(2);
            JsonNode lab = nodeByCode(body.path("data"), "LAB-A");
            assertThat(lab.path("labId").asLong()).isEqualTo(labId("LAB-A"));
            assertThat(lab.path("code").asText()).isEqualTo("LAB-A");
            assertThat(lab.path("name").asText()).isNotBlank();
            assertThat(lab.path("location").asText()).isNotBlank();
            assertThat(lab.path("capacity").asInt()).isEqualTo(20);
            assertThat(lab.path("status").asText()).isEqualTo("ACTIVE");
            assertThat(fieldNames(body)).noneMatch(this::sensitiveFieldName);
        }
    }

    @Test
    void rulesExposeEffectivePublicValuesWithoutInternalConfiguration() throws Exception {
        long labId = labId("LAB-A");
        jdbcTemplate.update("""
                UPDATE t_time_rule
                SET min_duration_minutes = 60, max_duration_minutes = 180,
                    advance_min_days = 2, advance_max_days = 10
                WHERE lab_id = ? AND day_of_week = 1
                """, labId);
        String token = loginAndGetToken("20240001", "Lab@2026");

        MvcResult result = mockMvc.perform(get("/api/labs/{labId}/rules", labId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.labId").value(labId))
                .andExpect(jsonPath("$.data.weeklyRules.length()").value(7))
                .andExpect(jsonPath("$.data.weeklyRules[0].dayOfWeek").value(1))
                .andExpect(jsonPath("$.data.weeklyRules[0].openTime").value("08:00:00"))
                .andExpect(jsonPath("$.data.weeklyRules[0].closeTime").value("22:00:00"))
                .andExpect(jsonPath("$.data.weeklyRules[0].minDurationMinutes").value(60))
                .andExpect(jsonPath("$.data.weeklyRules[0].maxDurationMinutes").value(180))
                .andExpect(jsonPath("$.data.weeklyRules[0].advanceMinDays").value(2))
                .andExpect(jsonPath("$.data.weeklyRules[0].advanceMaxDays").value(10))
                .andExpect(jsonPath("$.data.weeklyRules[1].minDurationMinutes").value(30))
                .andExpect(jsonPath("$.data.weeklyRules[1].maxDurationMinutes").value(240))
                .andExpect(jsonPath("$.data.weeklyRules[1].advanceMinDays").value(1))
                .andExpect(jsonPath("$.data.weeklyRules[1].advanceMaxDays").value(14))
                .andExpect(jsonPath("$.data.cancellation.freeBeforeHours").value(2))
                .andExpect(jsonPath("$.data.checkin.beforeMinutes").value(15))
                .andExpect(jsonPath("$.data.checkin.graceMinutes").value(15))
                .andExpect(jsonPath("$.data.checkin.choiceExpireSeconds").value(120))
                .andExpect(jsonPath("$.data.checkin.maxFailedAttempts").value(5))
                .andExpect(jsonPath("$.data.checkin.lockoutMinutes").value(10))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(body)).noneMatch(this::sensitiveFieldName);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("ai.provider", "schedule.primary.algorithm", "internal-token");
    }

    @Test
    void availabilityReturns48SlotsAndSeatProfilesWithZeroForMissingBitmapRows() throws Exception {
        long labId = labId("LAB-A");
        String token = loginAndGetToken("L1001", "Lab@2026");

        MvcResult result = mockMvc.perform(get("/api/labs/{labId}/availability", labId)
                        .queryParam("date", DATE.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.labId").value(labId))
                .andExpect(jsonPath("$.data.date").value(DATE.toString()))
                .andExpect(jsonPath("$.data.roomMask").value("0"))
                .andExpect(jsonPath("$.data.labStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.seats.length()").value(20))
                .andExpect(jsonPath("$.data.slots.length()").value(48))
                .andExpect(jsonPath("$.data.seats[0].seatCode").value("LAB-A-SEAT-01"))
                .andExpect(jsonPath("$.data.seats[0].zoneCode").isNotEmpty())
                .andExpect(jsonPath("$.data.seats[0].seatIndex").value(1))
                .andExpect(jsonPath("$.data.seats[0].specLevel").isNotEmpty())
                .andExpect(jsonPath("$.data.seats[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.seats[0].mask").value("0"))
                .andExpect(jsonPath("$.data.slots[0].index").value(0))
                .andExpect(jsonPath("$.data.slots[0].start").value("00:00"))
                .andExpect(jsonPath("$.data.slots[0].end").value("00:30"))
                .andExpect(jsonPath("$.data.slots[0].roomOccupied").value(false))
                .andExpect(jsonPath("$.data.slots[0].occupiedSeatCount").value(0))
                .andExpect(jsonPath("$.data.slots[0].availableSeatCount").value(20))
                .andExpect(jsonPath("$.data.slots[47].start").value("23:30"))
                .andExpect(jsonPath("$.data.slots[47].end").value("24:00"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("data").path("seats"))
                .allSatisfy(seat -> assertThat(seat.path("mask").asText()).isEqualTo("0"));
        assertThat(fieldNames(body)).noneMatch(this::sensitiveFieldName);
        assertThat(fieldNames(body)).doesNotContain("userId", "account", "purpose", "resvId");
    }

    @Test
    void availabilitySummarizesManualRoomAndSeatBitmaps() throws Exception {
        long labId = labId("LAB-A");
        List<Long> seatIds = seatIds(labId);
        long roomMask = 1L << 16;
        long firstSeatMask = 1L << 16;
        long secondSeatMask = (1L << 16) | (1L << 17);
        jdbcTemplate.update(
                "INSERT INTO t_slot_bitmap (lab_id, bitmap_date, mask) VALUES (?, ?, ?)",
                labId, DATE, roomMask);
        jdbcTemplate.update(
                "INSERT INTO t_seat_bitmap (seat_id, bitmap_date, mask) VALUES (?, ?, ?)",
                seatIds.get(0), DATE, firstSeatMask);
        jdbcTemplate.update(
                "INSERT INTO t_seat_bitmap (seat_id, bitmap_date, mask) VALUES (?, ?, ?)",
                seatIds.get(1), DATE, secondSeatMask);
        jdbcTemplate.update("UPDATE t_lab_seat SET seat_status = 'DISABLED' WHERE seat_id = ?",
                seatIds.get(2));
        String token = loginAndGetToken("T1001", "Lab@2026");

        mockMvc.perform(get("/api/labs/{labId}/availability", labId)
                        .queryParam("date", DATE.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomMask").value(Long.toString(roomMask)))
                .andExpect(jsonPath("$.data.seats[0].mask").value(Long.toString(firstSeatMask)))
                .andExpect(jsonPath("$.data.seats[1].mask").value(Long.toString(secondSeatMask)))
                .andExpect(jsonPath("$.data.seats[2].mask").value("0"))
                .andExpect(jsonPath("$.data.slots[16].start").value("08:00"))
                .andExpect(jsonPath("$.data.slots[16].roomOccupied").value(true))
                .andExpect(jsonPath("$.data.slots[16].occupiedSeatCount").value(2))
                .andExpect(jsonPath("$.data.slots[16].availableSeatCount").value(0))
                .andExpect(jsonPath("$.data.slots[17].roomOccupied").value(false))
                .andExpect(jsonPath("$.data.slots[17].occupiedSeatCount").value(1))
                .andExpect(jsonPath("$.data.slots[17].availableSeatCount").value(18))
                .andExpect(jsonPath("$.data.slots[18].occupiedSeatCount").value(0))
                .andExpect(jsonPath("$.data.slots[18].availableSeatCount").value(19));
    }

    @Test
    void disabledLabKeepsOccupancyFactsButHasNoAvailableSlots() throws Exception {
        assertUnavailableLab("DISABLED");
    }

    @Test
    void maintenanceLabKeepsOccupancyFactsButHasNoAvailableSlots() throws Exception {
        assertUnavailableLab("MAINTENANCE");
    }

    @Test
    void availabilityUsesAReadOnlyRepeatableReadSnapshot() throws Exception {
        var annotation = com.campus.lab.resource.ResourceService.class
                .getMethod("getAvailability", long.class, LocalDate.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.readOnly()).isTrue();
        assertThat(annotation.isolation()).isEqualTo(
                org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
    }

    private void assertUnavailableLab(String labStatus) throws Exception {
        long labId = labId("LAB-A");
        long seatId = seatIds(labId).getFirst();
        long occupiedMask = 1L << 16;
        jdbcTemplate.update("UPDATE t_lab SET status = ? WHERE lab_id = ?", labStatus, labId);
        jdbcTemplate.update(
                "INSERT INTO t_slot_bitmap (lab_id, bitmap_date, mask) VALUES (?, ?, ?)",
                labId, DATE, occupiedMask);
        jdbcTemplate.update(
                "INSERT INTO t_seat_bitmap (seat_id, bitmap_date, mask) VALUES (?, ?, ?)",
                seatId, DATE, occupiedMask);
        String token = loginAndGetToken("20240001", "Lab@2026");

        MvcResult result = mockMvc.perform(get("/api/labs/{labId}/availability", labId)
                        .queryParam("date", DATE.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labStatus").value(labStatus))
                .andExpect(jsonPath("$.data.roomMask").value(Long.toString(occupiedMask)))
                .andExpect(jsonPath("$.data.seats[0].mask").value(Long.toString(occupiedMask)))
                .andExpect(jsonPath("$.data.slots[16].roomOccupied").value(true))
                .andExpect(jsonPath("$.data.slots[16].occupiedSeatCount").value(1))
                .andExpect(jsonPath("$.data.slots[16].availableSeatCount").value(0))
                .andExpect(jsonPath("$.data.slots[17].roomOccupied").value(false))
                .andExpect(jsonPath("$.data.slots[17].occupiedSeatCount").value(0))
                .andExpect(jsonPath("$.data.slots[17].availableSeatCount").value(0))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("data").path("slots")).hasSize(48);
        assertThat(body.path("data").path("slots"))
                .allSatisfy(slot -> assertThat(slot.path("availableSeatCount").asInt()).isZero());
    }

    @Test
    void unknownLabAndInvalidAvailabilityDateReturn404Or400() throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");

        mockMvc.perform(get("/api/labs/{labId}/rules", 999999999)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/labs/{labId}/availability", 999999999)
                        .queryParam("date", DATE.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/labs/{labId}/availability", labId("LAB-A"))
                        .queryParam("date", "2026-02-30")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/api/labs/{labId}/availability", labId("LAB-A"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void labAdminIsForbiddenFromEveryResourceWriteEndpoint() throws Exception {
        String token = loginAndGetToken("L1001", "Lab@2026");
        long labId = labId("LAB-A");
        long seatId = seatIds(labId).getFirst();
        long ruleId = ruleId(labId, 1);

        mockMvc.perform(post("/api/admin/labs")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LAB-FORBIDDEN", "name", "禁止创建",
                                "location", "无", "capacity", 10))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(patch("/api/admin/labs/{id}/status", labId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(patch("/api/admin/seats/{id}/status", seatId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "MAINTENANCE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(put("/api/admin/labs/{labId}/rules/{ruleId}", labId, ruleId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "openTime", "09:00", "closeTime", "21:00"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/admin/terminals")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "labId", labId, "deviceName", "禁止创建终端",
                                "model", "TEST", "terminalCode", "FORBIDDEN-TERM",
                                "initialSecret", "Forbidden!123"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_lab WHERE lab_code = 'LAB-FORBIDDEN'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_device WHERE terminal_code = 'FORBIDDEN-TERM'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_lab WHERE lab_id = ?", String.class, labId)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT seat_status FROM t_lab_seat WHERE seat_id = ?", String.class, seatId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void sysAdminCanCreateLabAndUpdateLabAndSeatStatus() throws Exception {
        String token = loginAndGetToken("A1001", "Lab@2026");

        mockMvc.perform(post("/api/admin/labs")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LAB-NEW", "name", "新实验室",
                                "location", "创新楼 C101", "capacity", 12))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.labId").isNumber())
                .andExpect(jsonPath("$.data.code").value("LAB-NEW"))
                .andExpect(jsonPath("$.data.name").value("新实验室"))
                .andExpect(jsonPath("$.data.location").value("创新楼 C101"))
                .andExpect(jsonPath("$.data.capacity").value(12))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        long newLabId = labId("LAB-NEW");
        mockMvc.perform(patch("/api/admin/labs/{id}/status", newLabId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "MAINTENANCE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MAINTENANCE"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_lab WHERE lab_id = ?", String.class, newLabId))
                .isEqualTo("MAINTENANCE");

        long seatId = seatIds(labId("LAB-A")).getFirst();
        mockMvc.perform(patch("/api/admin/seats/{id}/status", seatId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seatId").value(seatId))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT seat_status FROM t_lab_seat WHERE seat_id = ?", String.class, seatId))
                .isEqualTo("DISABLED");
    }

    @Test
    void sysAdminCanUpdateRulesIdempotentlyAndClearNullableOverrides() throws Exception {
        String token = loginAndGetToken("A1001", "Lab@2026");
        long labId = labId("LAB-A");
        long ruleId = ruleId(labId, 1);
        Map<String, Object> request = Map.of(
                "openTime", "09:00", "closeTime", "21:00",
                "minDurationMinutes", 60, "maxDurationMinutes", 180,
                "advanceMinDays", 2, "advanceMaxDays", 7);

        for (int invocation = 0; invocation < 2; invocation++) {
            mockMvc.perform(put("/api/admin/labs/{labId}/rules/{ruleId}", labId, ruleId)
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ruleId").value(ruleId))
                    .andExpect(jsonPath("$.data.openTime").value("09:00:00"))
                    .andExpect(jsonPath("$.data.closeTime").value("21:00:00"))
                    .andExpect(jsonPath("$.data.minDurationMinutes").value(60))
                    .andExpect(jsonPath("$.data.maxDurationMinutes").value(180))
                    .andExpect(jsonPath("$.data.advanceMinDays").value(2))
                    .andExpect(jsonPath("$.data.advanceMaxDays").value(7));
        }
        mockMvc.perform(put("/api/admin/labs/{labId}/rules/{ruleId}", labId, ruleId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"openTime":"09:00","closeTime":"21:00",
                                 "minDurationMinutes":null,"maxDurationMinutes":null,
                                 "advanceMinDays":null,"advanceMaxDays":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minDurationMinutes").value(30))
                .andExpect(jsonPath("$.data.maxDurationMinutes").value(240))
                .andExpect(jsonPath("$.data.advanceMinDays").value(1))
                .andExpect(jsonPath("$.data.advanceMaxDays").value(14));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_time_rule
                WHERE rule_id = ? AND min_duration_minutes IS NULL
                  AND max_duration_minutes IS NULL AND advance_min_days IS NULL
                  AND advance_max_days IS NULL
                """, Integer.class, ruleId)).isEqualTo(1);
    }

    @Test
    void sysAdminTerminalResponseAndDatabaseNeverExposePlaintextSecret() throws Exception {
        String token = loginAndGetToken("A1001", "Lab@2026");
        long labId = labId("LAB-A");
        String initialSecret = "TestTerminal!2026";

        MvcResult result = mockMvc.perform(post("/api/admin/terminals")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "labId", labId, "deviceName", "测试签到终端",
                                "model", "TEST-TERMINAL", "terminalCode", "TEST-TERM",
                                "initialSecret", initialSecret))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deviceId").isNumber())
                .andExpect(jsonPath("$.data.labId").value(labId))
                .andExpect(jsonPath("$.data.deviceName").value("测试签到终端"))
                .andExpect(jsonPath("$.data.terminalCode").value("TEST-TERM"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn();
        String response = result.getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        assertThat(response).doesNotContain(initialSecret);
        assertThat(fieldNames(body)).noneMatch(this::sensitiveFieldName);
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT terminal_secret_hash FROM t_device WHERE terminal_code = 'TEST-TERM'",
                String.class);
        assertThat(storedHash).startsWith("$2").hasSize(60).isNotEqualTo(initialSecret);
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches(initialSecret, storedHash)).isTrue();
    }

    @Test
    void duplicateLabCodeAndTerminalCodeReturn409() throws Exception {
        String token = loginAndGetToken("A1001", "Lab@2026");
        long labId = labId("LAB-A");

        mockMvc.perform(post("/api/admin/labs")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LAB-A", "name", "重复实验室",
                                "location", "无", "capacity", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        mockMvc.perform(post("/api/admin/terminals")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "labId", labId, "deviceName", "重复终端",
                                "model", "TEST", "terminalCode", "LAB-A-TERM",
                                "initialSecret", "Duplicate!123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void adminValidationAndMissingResourcesReturn400Or404WithoutWrites() throws Exception {
        String token = loginAndGetToken("A1001", "Lab@2026");
        long labId = labId("LAB-A");
        long ruleId = ruleId(labId, 1);

        mockMvc.perform(post("/api/admin/labs")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "", "name", "", "location", "", "capacity", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(patch("/api/admin/labs/{id}/status", labId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "SUSPENDED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(put("/api/admin/labs/{labId}/rules/{ruleId}", labId, ruleId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "openTime", "09:00", "closeTime", "08:00",
                                "minDurationMinutes", 60, "maxDurationMinutes", 30))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT open_time FROM t_time_rule WHERE rule_id = ?", String.class, ruleId))
                .isEqualTo("08:00:00");
        mockMvc.perform(patch("/api/admin/seats/{id}/status", 999999999)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "ACTIVE"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(post("/api/admin/terminals")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "labId", 999999999, "deviceName", "不存在实验室终端",
                                "model", "TEST", "terminalCode", "MISSING-LAB-TERM",
                                "initialSecret", "Missing!123"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(post("/api/admin/terminals")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "labId", labId, "deviceName", "空秘密终端",
                                "model", "TEST", "terminalCode", "EMPTY-SECRET-TERM",
                                "initialSecret", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private long ruleId(long labId, int dayOfWeek) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT rule_id FROM t_time_rule WHERE lab_id = ? AND day_of_week = ?",
                Long.class, labId, dayOfWeek);
        assertThat(id).isNotNull();
        return id;
    }

    private long labId(String code) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = ?", Long.class, code);
        assertThat(id).isNotNull();
        return id;
    }

    private List<Long> seatIds(long labId) {
        return jdbcTemplate.queryForList(
                "SELECT seat_id FROM t_lab_seat WHERE lab_id = ? ORDER BY seat_index",
                Long.class, labId);
    }

    private static JsonNode nodeByCode(JsonNode nodes, String code) {
        for (JsonNode node : nodes) {
            if (code.equals(node.path("code").asText())) {
                return node;
            }
        }
        throw new AssertionError("Missing lab " + code);
    }

    private static List<String> fieldNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        collectFieldNames(body, names);
        return names;
    }

    private static void collectFieldNames(JsonNode node, List<String> names) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                names.add(entry.getKey());
                collectFieldNames(entry.getValue(), names);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectFieldNames(child, names));
        }
    }

    private boolean sensitiveFieldName(String name) {
        String lower = name.toLowerCase();
        return lower.contains("password") || lower.contains("secret")
                || lower.contains("token") || lower.contains("hash");
    }
}
