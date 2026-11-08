package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class InternalToolIntegrationTest extends AbstractIntegrationTest {

    private static final String INTERNAL_TOKEN = "local-ai-internal-token";
    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Test
    void internalToolsRejectMissingInternalToken() throws Exception {
        String bearer = loginAndGetToken("20240001", "Lab@2026");
        long userId = userIdByAccount("20240001");

        mockMvc.perform(get("/api/internal/rules").queryParam("labId", Long.toString(labId("LAB-A")))
                        .header(HttpHeaders.AUTHORIZATION, bearer(bearer)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/internal/availability")
                        .queryParam("labId", Long.toString(labId("LAB-A")))
                        .queryParam("date", DATE.toString()).queryParam("mode", "SEAT")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bearer)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/internal/users/{userId}/reservations", userId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(bearer)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rulesReusePublicEffectiveRules() throws Exception {
        String bearer = loginAndGetToken("20240001", "Lab@2026");
        long labId = labId("LAB-A");

        mockMvc.perform(get("/api/internal/rules").queryParam("labId", Long.toString(labId))
                        .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.labId").value(labId))
                .andExpect(jsonPath("$.data.weeklyRules.length()").value(7))
                .andExpect(jsonPath("$.data.cancellation.freeBeforeHours").isNumber());
    }

    @Test
    void availabilityContainsReal48SlotsAndDataTimeWithoutPrivateFields() throws Exception {
        String bearer = loginAndGetToken("20240001", "Lab@2026");

        var result = mockMvc.perform(get("/api/internal/availability")
                        .queryParam("labId", Long.toString(labId("LAB-A")))
                        .queryParam("date", DATE.toString()).queryParam("mode", "SEAT")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("SEAT"))
                .andExpect(jsonPath("$.data.dataTime").isNotEmpty())
                .andExpect(jsonPath("$.data.slots.length()").value(48))
                .andExpect(jsonPath("$.data.slots[47].end").value("24:00"))
                .andReturn();
        assertNoPrivateFields(result.getResponse().getContentAsString());
    }

    @Test
    void reservationsAreScopedToPathUserAndExposeOnlyNecessaryFields() throws Exception {
        String owner = loginAndGetToken("20240001", "Lab@2026");
        long ownerId = userIdByAccount("20240001");
        long ownerReservation = createFixed(owner, labId("LAB-A"), "本人私密用途")
                .path("data").path("resvId").asLong();
        String other = loginAndGetToken(createTestAccount("STUDENT"), "Lab@2026");
        long otherReservation = createFixed(other, labId("LAB-B"), "他人私密用途")
                .path("data").path("resvId").asLong();

        var result = mockMvc.perform(get("/api/internal/users/{userId}/reservations", ownerId)
                        .queryParam("status", "APPROVED").queryParam("page", "0").queryParam("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .header("X-Internal-Service-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].resvId").value(ownerReservation))
                .andExpect(jsonPath("$.data.items[0].labName").isNotEmpty())
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        JsonNode items = objectMapper.readTree(body).path("data").path("items");
        Set<Long> ids = new HashSet<>();
        items.forEach(item -> ids.add(item.path("resvId").asLong()));
        assertThat(ids).doesNotContain(otherReservation);
        assertThat(body).doesNotContain("本人私密用途");
        assertNoPrivateFields(body);
        assertThat(items.get(0).has("preferences")).isFalse();
    }

    @Test
    void internalToolsExposeNoPostWritePath() throws Exception {
        String bearer = loginAndGetToken("20240001", "Lab@2026");
        long userId = userIdByAccount("20240001");
        long before = reservationCount();
        String[][] requests = {
                {"/api/internal/rules", ""},
                {"/api/internal/availability", ""},
                {"/api/internal/users/" + userId + "/reservations", ""}
        };

        for (String[] request : requests) {
            mockMvc.perform(post(request[0])
                            .header(HttpHeaders.AUTHORIZATION, bearer(bearer))
                            .header("X-Internal-Service-Token", INTERNAL_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("status", "CANCELLED"))))
                    .andExpect(status().is4xxClientError());
        }
        assertThat(reservationCount()).isEqualTo(before);
    }

    private JsonNode createFixed(String token, long labId, String purpose) throws Exception {
        String response = mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "allocationMode", "FIXED", "labId", labId, "date", DATE.toString(),
                                "startTime", "09:00", "endTime", "10:00", "reservationMode", "SEAT",
                                "attendeeCount", 1, "seatCount", 1, "purpose", purpose,
                                "preferences", Map.of()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private long labId(String code) {
        return jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = ?", Long.class, code);
    }

    private long reservationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_reservation", Long.class);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void assertNoPrivateFields(String body) throws Exception {
        JsonNode json = objectMapper.readTree(body);
        for (String field : new String[] {"account", "phone", "mobile", "openid", "password", "purpose", "userId"}) {
            assertThat(json.findValuesAsText(field)).as(field).isEmpty();
        }
    }
}
