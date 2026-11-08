package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

class ReservationDayEndIntegrationTest extends AbstractReservationNonTransactionalTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    private long labId;
    private long ruleId;

    @BeforeEach
    void createIndependentFixture() {
        labId = createLaboratory(4);
        ruleId = jdbcTemplate.queryForObject(
                "SELECT rule_id FROM t_time_rule WHERE lab_id = ? AND day_of_week = 1",
                Long.class, labId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"24:00", "24:00:00"})
    void adminConfiguresEndOfDayThenFixedAndSchedulableUseIt(String closeTime) throws Exception {
        String adminToken = loginAndGetToken(createTestAccount("SYS_ADMIN"), "Lab@2026");
        Map<String, Object> rule = new HashMap<>();
        rule.put("openTime", "08:00");
        rule.put("closeTime", closeTime);

        mockMvc.perform(put("/api/admin/labs/{labId}/rules/{ruleId}", labId, ruleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rule)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closeTime").value("24:00:00"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT close_time FROM t_time_rule WHERE rule_id = ?", String.class, ruleId))
                .isEqualTo("24:00:00");

        String fixedToken = loginAndGetToken(createTestAccount("STUDENT"), "Lab@2026");
        mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + fixedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fixedRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.windowEnd").value("24:00:00"));

        String schedulableToken = loginAndGetToken(createTestAccount("STUDENT"), "Lab@2026");
        mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + schedulableToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(schedulableRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_SCHEDULE"))
                .andExpect(jsonPath("$.data.windowEnd").value("24:00:00"));
    }

    private Map<String, Object> fixedRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("allocationMode", "FIXED");
        request.put("labId", labId);
        request.put("date", DATE.toString());
        request.put("startTime", "23:30");
        request.put("endTime", "24:00");
        request.put("reservationMode", "SEAT");
        request.put("attendeeCount", 1);
        request.put("seatCount", 1);
        request.put("purpose", "管理配置日终后固定预约");
        request.put("preferences", Map.of());
        return request;
    }

    private Map<String, Object> schedulableRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("allocationMode", "SCHEDULABLE");
        request.put("date", DATE.toString());
        request.put("windowStart", "23:00");
        request.put("windowEnd", "24:00");
        request.put("durationMinutes", 30);
        request.put("reservationMode", "SEAT");
        request.put("attendeeCount", 1);
        request.put("seatCount", 1);
        request.put("purpose", "管理配置日终后可调度预约");
        request.put("preferences", Map.of("allowedLabIds", new Long[] {labId}));
        return request;
    }
}
