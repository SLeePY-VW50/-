package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ReservationQueryIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private org.springframework.jdbc.core.JdbcTemplate spyJdbcTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    private com.campus.lab.reservation.ReservationQueryService reservationQueryService;

    private long[] jdbcBaseline = new long[3];

    @Test
    void myReservationsAreOwnerScopedAndFilterByStatusWithFullView() throws Exception {
        long labId = labId("LAB-A");
        String owner = loginAndGetToken("20240001", "Lab@2026");
        long approvedId = createFixed(owner, labId, "09:00", "10:00", "本人已审批")
                .path("data").path("resvId").asLong();
        long pendingId = createSchedulable(owner, "本人待排期")
                .path("data").path("resvId").asLong();
        String other = loginAndGetToken(createTestAccount("STUDENT"), "Lab@2026");
        long otherId = createFixed(other, labId, "09:00", "10:00", "他人预约")
                .path("data").path("resvId").asLong();

        JsonNode mine = responseJson(mockMvc.perform(get("/api/reservations/my")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn());
        Set<Long> ids = new HashSet<>();
        mine.path("data").path("items").forEach(item -> ids.add(item.path("resvId").asLong()));
        assertThat(ids).containsExactlyInAnyOrder(approvedId, pendingId).doesNotContain(otherId);
        assertThat(mine.path("data").path("items")).allSatisfy(item -> {
            assertThat(item.has("batchId")).isTrue();
            assertThat(item.has("batchNo")).isTrue();
            assertThat(item.has("labName")).isTrue();
            assertThat(item.has("windowStart")).isTrue();
            assertThat(item.has("windowEnd")).isTrue();
            assertThat(item.has("durationMinutes")).isTrue();
            assertThat(item.has("preferences")).isTrue();
            assertThat(item.has("seats")).isTrue();
            assertThat(item.has("checkinChannel")).isTrue();
            assertThat(item.has("checkinTime")).isTrue();
            assertThat(item.has("checkoutChannel")).isTrue();
            assertThat(item.has("checkoutTime")).isTrue();
            assertThat(item.has("createdAt")).isTrue();
            assertThat(item.has("userId")).isFalse();
        });

        mockMvc.perform(get("/api/reservations/my")
                        .queryParam("status", "PENDING_SCHEDULE")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].resvId").value(pendingId))
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING_SCHEDULE"));
    }

    @Test
    void listIsOwnerScopedForUsersAndAdminCanFilterAll() throws Exception {
        long labId = labId("LAB-A");
        String student = loginAndGetToken("20240001", "Lab@2026");
        long studentReservation = createFixed(student, labId, "09:00", "10:00", "学生列表")
                .path("data").path("resvId").asLong();
        String teacher = loginAndGetToken("T1001", "Lab@2026");
        long teacherReservation = createFixed(teacher, labId, "09:00", "10:00", "教师列表")
                .path("data").path("resvId").asLong();

        mockMvc.perform(get("/api/reservations")
                        .queryParam("labId", Long.toString(labId))
                        .queryParam("date", DATE.toString())
                        .queryParam("status", "APPROVED")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].resvId").value(studentReservation));
        mockMvc.perform(get("/api/reservations")
                        .queryParam("labId", Long.toString(labId))
                        .queryParam("date", DATE.toString())
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].resvId").value(teacherReservation));

        for (String admin : new String[] {"L1001", "A1001"}) {
            String token = loginAndGetToken(admin, "Lab@2026");
            mockMvc.perform(get("/api/reservations")
                            .queryParam("labId", Long.toString(labId))
                            .queryParam("date", DATE.toString())
                            .queryParam("status", "APPROVED")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(2));
            mockMvc.perform(get("/api/reservations")
                            .queryParam("status", "PENDING_SCHEDULE")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(0));
        }
        mockMvc.perform(get("/api/reservations")
                        .queryParam("status", "NOT_A_STATUS")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void detailAllowsOwnerAndAdminsButDoesNotLeakToOtherUsers() throws Exception {
        long labId = labId("LAB-A");
        String owner = loginAndGetToken("20240001", "Lab@2026");
        long reservationId = createFixed(owner, labId, "09:00", "10:00", "详情权限")
                .path("data").path("resvId").asLong();

        mockMvc.perform(get("/api/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resvId").value(reservationId))
                .andExpect(jsonPath("$.data.userId").doesNotExist());
        String other = loginAndGetToken(createTestAccount("STUDENT"), "Lab@2026");
        mockMvc.perform(get("/api/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(get("/api/reservations/{id}", reservationId)
                        .header("Authorization", "Bearer " + loginAndGetToken("T1001", "Lab@2026")))
                .andExpect(status().isNotFound());
        for (String admin : new String[] {"L1001", "A1001"}) {
            mockMvc.perform(get("/api/reservations/{id}", reservationId)
                            .header("Authorization", "Bearer " + loginAndGetToken(admin, "Lab@2026")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resvId").value(reservationId));
        }
    }

    @Test
    void myPaginatesWithTotalAndRejectsInvalidPageArguments() throws Exception {
        long labId = labId("LAB-A");
        String owner = loginAndGetToken("20240001", "Lab@2026");
        createFixed(owner, labId, "08:00", "09:00", "分页一");
        createFixed(owner, labId, "09:00", "10:00", "分页二");
        createFixed(owner, labId, "10:00", "11:00", "分页三");

        mockMvc.perform(get("/api/reservations/my")
                        .queryParam("page", "0").queryParam("size", "2")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.total").value(3));
        mockMvc.perform(get("/api/reservations/my")
                        .queryParam("page", "1").queryParam("size", "2")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.total").value(3));
        mockMvc.perform(get("/api/reservations/my")
                        .queryParam("page", "-1")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/reservations/my")
                        .queryParam("size", "0")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/reservations/my")
                        .queryParam("size", "101")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest());
    }

    @Test
    void paginationUsesFixedJdbcCallCount() throws Exception {
        long labId = labId("LAB-A");
        String owner = loginAndGetToken("20240001", "Lab@2026");
        createFixed(owner, labId, "08:00", "09:00", "计数一");
        createFixed(owner, labId, "09:00", "10:00", "计数二");
        createFixed(owner, labId, "10:00", "11:00", "计数三");
        jdbcBaseline = jdbcCallCounts();

        mockMvc.perform(get("/api/reservations/my")
                        .queryParam("page", "0").queryParam("size", "2")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());

        assertJdbcCallCounts(2, 1, 1);
    }

    @Test
    void batchViewUsesFixedJdbcCallCount() throws Exception {
        long labId = labId("LAB-A");
        String teacher = loginAndGetToken("T1001", "Lab@2026");
        Map<String, Object> request = new HashMap<>();
        request.put("courseName", "批量查询课程");
        request.put("labId", labId);
        request.put("attendeeCount", 20);
        request.put("purpose", "批量查询SQL计数");
        request.put("items", java.util.List.of(
                Map.of("date", "2026-11-02", "startTime", "09:00", "endTime", "10:00"),
                Map.of("date", "2026-11-03", "startTime", "09:00", "endTime", "10:00")));
        JsonNode created = responseJson(mockMvc.perform(post("/api/reservations/batches")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn());
        long batchId = created.path("data").path("batchId").asLong();
        jdbcBaseline = jdbcCallCounts();

        var view = reservationQueryService.getBatchOwned(batchId, userIdByAccount("T1001"));

        assertThat(view.items()).hasSize(2);
        assertJdbcCallCounts(3, 1, 0);
    }

    private void assertJdbcCallCounts(int query, int queryForList, int queryForObject) {
        long[] current = jdbcCallCounts();
        assertThat(current[0] - jdbcBaseline[0]).isEqualTo(query);
        assertThat(current[1] - jdbcBaseline[1]).isEqualTo(queryForList);
        assertThat(current[2] - jdbcBaseline[2]).isEqualTo(queryForObject);
    }

    private long[] jdbcCallCounts() {
        return new long[] {
                countJdbcCalls("query"), countJdbcCalls("queryForList"), countJdbcCalls("queryForObject")};
    }

    private long countJdbcCalls(String methodName) {
        return org.mockito.Mockito.mockingDetails(spyJdbcTemplate).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(methodName))
                .filter(invocation -> invocation.getLocation().toString()
                        .contains("com.campus.lab.reservation.ReservationRepository"))
                .count();
    }

    private JsonNode createFixed(String token, long labId, String start, String end, String purpose)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("allocationMode", "FIXED");
        request.put("labId", labId);
        request.put("date", DATE.toString());
        request.put("startTime", start);
        request.put("endTime", end);
        request.put("reservationMode", "SEAT");
        request.put("attendeeCount", 1);
        request.put("seatCount", 1);
        request.put("purpose", purpose);
        request.put("preferences", Map.of());
        return responseJson(mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode createSchedulable(String token, String purpose) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("allocationMode", "SCHEDULABLE");
        request.put("date", DATE.toString());
        request.put("windowStart", "10:00");
        request.put("windowEnd", "12:00");
        request.put("durationMinutes", 60);
        request.put("reservationMode", "SEAT");
        request.put("attendeeCount", 1);
        request.put("seatCount", 1);
        request.put("purpose", purpose);
        request.put("preferences", Map.of("allowedLabIds", new Long[] {labId("LAB-A")}));
        return responseJson(mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private ResultActions responseActions(ResultActions actions) {
        return actions;
    }

    private JsonNode responseJson(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long labId(String code) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = ?", Long.class, code);
        assertThat(id).isNotNull();
        return id;
    }
}
