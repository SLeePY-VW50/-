package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.lab.reservation.TimeSlotMask;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class BatchReservationIntegrationTest extends AbstractReservationNonTransactionalTest {

    private static final LocalDate FIRST_DATE = LocalDate.of(2026, 11, 2);
    private static final LocalDate SECOND_DATE = LocalDate.of(2026, 11, 3);

    private long labId;

    @BeforeEach
    void createIsolatedLaboratory() {
        labId = createLaboratory(20);
    }

    @Test
    void teacherBatchApprovesEveryDateWithOneBatchNumber() throws Exception {
        String token = teacherToken();

        JsonNode created = responseJson(postBatch(token, batchRequest("教师批次成功", List.of(
                        item(FIRST_DATE, "09:00", "10:00"),
                        item(SECOND_DATE, "13:00", "15:00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.batchNo").isNotEmpty())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data.items[1].status").value("APPROVED"))
                .andExpect(jsonPath("$.data.items[0].seatCount").value(20))
                .andExpect(jsonPath("$.data.items[0].seats.length()").value(20))
                .andReturn());
        JsonNode data = created.path("data");
        long batchId = data.path("batchId").asLong();
        String batchNo = data.path("batchNo").asText();
        assertThat(batchNo).hasSizeLessThanOrEqualTo(32);
        assertThat(data.path("items")).allSatisfy(item ->
                assertThat(item.path("batchNo").asText()).isEqualTo(batchNo));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE batch_id = ? AND status = 'APPROVED'",
                Integer.class, batchId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat rs
                JOIN t_reservation r ON r.resv_id = rs.resv_id WHERE r.batch_id = ?
                """, Integer.class, batchId)).isEqualTo(40);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log l
                JOIN t_reservation r ON r.resv_id = l.resv_id WHERE r.batch_id = ?
                """, Integer.class, batchId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT mask FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, FIRST_DATE)).isEqualTo(
                        TimeSlotMask.between(LocalTime.of(9, 0), LocalTime.of(10, 0)).value());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT mask FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, SECOND_DATE)).isEqualTo(
                        TimeSlotMask.between(LocalTime.of(13, 0), LocalTime.of(15, 0)).value());
    }

    @Test
    void overlappingItemsInsideBatchAreRejectedBeforeAnyWrite() throws Exception {
        String token = teacherToken();

        postBatch(token, batchRequest("批内重叠", List.of(
                        item(FIRST_DATE, "09:00", "10:00"),
                        item(FIRST_DATE, "09:30", "10:30"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        assertNoBatchArtifacts("批内重叠");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_slot_bitmap WHERE lab_id = ?", Integer.class, labId)).isZero();
    }

    @Test
    void conflictWithExistingOccupancyRollsBackWholeBatch() throws Exception {
        String student = createTestAccount("STUDENT");
        String studentToken = loginAndGetToken(student, "Lab@2026");
        mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fixedSeatRequest())))
                .andExpect(status().isOk());

        postBatch(teacherToken(), batchRequest("批次中途冲突", List.of(
                        item(FIRST_DATE, "09:00", "10:00"),
                        item(SECOND_DATE, "09:00", "10:00"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        assertNoBatchArtifacts("批次中途冲突");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Integer.class, labId, FIRST_DATE)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE lab_id = ?", Integer.class, labId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log l
                JOIN t_reservation r ON r.resv_id = l.resv_id WHERE r.lab_id = ?
                """, Integer.class, labId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT mask FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, SECOND_DATE)).isZero();
    }

    @Test
    void studentAndLabAdminCannotWriteTeacherBatches() throws Exception {
        for (String role : new String[] {"STUDENT", "LAB_ADMIN"}) {
            String account = createTestAccount(role);
            String token = loginAndGetToken(account, "Lab@2026");

            postBatch(token, batchRequest("越权批次", List.of(item(FIRST_DATE, "09:00", "10:00"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }
        assertNoBatchArtifacts("越权批次");
    }

    @Test
    void batchRejectsMoreThanFourteenItems() throws Exception {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int index = 0; index < 15; index++) {
            LocalTime start = LocalTime.of(8, 0).plusMinutes(index * 30L);
            LocalTime end = start.plusMinutes(30);
            items.add(Map.of(
                    "date", FIRST_DATE.toString(),
                    "startTime", start.toString(),
                    "endTime", end.toString()));
        }

        postBatch(teacherToken(), batchRequest("超过14条批次", items))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("14")));
        assertNoBatchArtifacts("超过14条批次");
    }

    @Test
    void batchRejectsNullItem() throws Exception {
        List<Map<String, Object>> items = java.util.Arrays.asList(
                item(FIRST_DATE, "09:00", "10:00"), null);

        postBatch(teacherToken(), batchRequest("空批次明细", items))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        assertNoBatchArtifacts("空批次明细");
    }

    private String teacherToken() throws Exception {
        return loginAndGetToken(createTestAccount("TEACHER"), "Lab@2026");
    }

    private Map<String, Object> batchRequest(String purpose, List<Map<String, Object>> items) {
        Map<String, Object> request = new HashMap<>();
        request.put("courseName", "软件工程实践");
        request.put("labId", labId);
        request.put("attendeeCount", 20);
        request.put("purpose", purpose);
        request.put("items", items);
        return request;
    }

    private Map<String, Object> fixedSeatRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("allocationMode", "FIXED");
        request.put("labId", labId);
        request.put("date", SECOND_DATE.toString());
        request.put("startTime", "09:00");
        request.put("endTime", "10:00");
        request.put("reservationMode", "SEAT");
        request.put("attendeeCount", 1);
        request.put("seatCount", 1);
        request.put("purpose", "批次外已有占用");
        request.put("preferences", Map.of());
        return request;
    }

    private static Map<String, Object> item(LocalDate date, String start, String end) {
        return Map.of("date", date.toString(), "startTime", start, "endTime", end);
    }

    private ResultActions postBatch(String token, Map<String, Object> request) throws Exception {
        return mockMvc.perform(post("/api/reservations/batches")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private void assertNoBatchArtifacts(String purpose) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation_batch WHERE purpose = ?",
                Integer.class, purpose)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE purpose = ?",
                Integer.class, purpose)).isZero();
    }
}
