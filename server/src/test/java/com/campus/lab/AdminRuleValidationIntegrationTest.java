package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.lab.resource.ResourceService;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

class AdminRuleValidationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ResourceService resourceService;

    @ParameterizedTest
    @MethodSource("invalidDurationOverrides")
    void invalidMergedDurationsReturn400AndDoNotPersist(Map<String, Object> overrides)
            throws Exception {
        String token = loginAndGetToken("A1001", "Lab@2026");
        long labId = labId();
        long ruleId = ruleId(labId);
        Map<String, Object> request = new HashMap<>(overrides);
        request.put("openTime", "08:00");
        request.put("closeTime", "22:00");

        mockMvc.perform(put("/api/admin/labs/{labId}/rules/{ruleId}", labId, ruleId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("30")));
        assertNullableOverridesRemain(ruleId);
    }

    static Stream<Arguments> invalidDurationOverrides() {
        return Stream.of(
                Arguments.of(Map.of("minDurationMinutes", 45, "maxDurationMinutes", 90)),
                Arguments.of(Map.of("minDurationMinutes", 30, "maxDurationMinutes", 300)),
                Arguments.of(Map.of("minDurationMinutes", 270)));
    }

    @Test
    void thirtyAndFourHourBoundariesAreAccepted() throws Exception {
        String token = loginAndGetToken("A1001", "Lab@2026");
        long labId = labId();
        long ruleId = ruleId(labId);

        mockMvc.perform(put("/api/admin/labs/{labId}/rules/{ruleId}", labId, ruleId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "openTime", "08:00", "closeTime", "22:00",
                                "minDurationMinutes", 30, "maxDurationMinutes", 240))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minDurationMinutes").value(30))
                .andExpect(jsonPath("$.data.maxDurationMinutes").value(240));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT min_duration_minutes FROM t_time_rule WHERE rule_id = ?",
                Integer.class, ruleId)).isEqualTo(30);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT max_duration_minutes FROM t_time_rule WHERE rule_id = ?",
                Integer.class, ruleId)).isEqualTo(240);
    }

    @Test
    void invalidGlobalDurationConfigurationFailsClearlyWhenRulesAreLoaded() {
        jdbcTemplate.update(
                "UPDATE t_sys_config SET config_value = '300' "
                        + "WHERE config_key = 'reservation.duration.max.minutes'");

        assertThatThrownBy(() -> resourceService.getRules(labId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30")
                .hasMessageContaining("240");
    }

    private void assertNullableOverridesRemain(long ruleId) {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_time_rule
                WHERE rule_id = ? AND min_duration_minutes IS NULL
                  AND max_duration_minutes IS NULL
                """, Integer.class, ruleId)).isEqualTo(1);
    }

    private long labId() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = 'LAB-A'", Long.class);
        assertThat(id).isNotNull();
        return id;
    }

    private long ruleId(long labId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT rule_id FROM t_time_rule WHERE lab_id = ? AND day_of_week = 1",
                Long.class, labId);
        assertThat(id).isNotNull();
        return id;
    }
}
