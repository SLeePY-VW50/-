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
        Map<String, Object> request = new HashMap<>();
        request.put("openStartPeriod", 1);
        request.put("openEndPeriod", 10);
        request.put("advanceMinDays", 0);
        request.put("advanceMaxDays", 14);
        request.put("minPeriods", 1);
        request.put("maxPeriods", 5);
        request.putAll(overrides);

        mockMvc.perform(put("/api/admin/labs/{labId}/rules/{ruleId}", labId, ruleId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("节")));
        assertNullableOverridesRemain(ruleId);
    }

    static Stream<Arguments> invalidDurationOverrides() {
        return Stream.of(
                Arguments.of(Map.of("minPeriods", 4, "maxPeriods", 2)),
                Arguments.of(Map.of("openEndPeriod", 20, "minPeriods", 1, "maxPeriods", 5)),
                Arguments.of(Map.of("minPeriods", 3, "maxPeriods", 1)));
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
                                "openStartPeriod", 1, "openEndPeriod", 10,
                                "minPeriods", 1, "maxPeriods", 5,
                                "advanceMinDays", 0, "advanceMaxDays", 14))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minPeriods").value(1))
                .andExpect(jsonPath("$.data.maxPeriods").value(5));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT min_periods FROM t_time_rule WHERE rule_id = ?",
                Integer.class, ruleId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT max_periods FROM t_time_rule WHERE rule_id = ?",
                Integer.class, ruleId)).isEqualTo(5);
    }

    @Test
    void invalidGlobalDurationConfigurationFailsClearlyWhenRulesAreLoaded() {
        jdbcTemplate.update(
                "UPDATE t_sys_config SET config_value = '0' "
                        + "WHERE config_key = 'reservation.duration.max.periods'");

        assertThatThrownBy(() -> resourceService.getRules(labId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("节");
    }

    private void assertNullableOverridesRemain(long ruleId) {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_time_rule
                WHERE rule_id = ? AND min_periods IS NULL
                  AND max_periods IS NULL
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
