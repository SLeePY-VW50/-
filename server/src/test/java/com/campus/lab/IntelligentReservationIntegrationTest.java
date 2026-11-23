package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.lab.reservation.TimeSlotMask;
import com.campus.lab.schedule.ScheduleDemand;
import com.campus.lab.schedule.ScheduleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class IntelligentReservationIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Test
    void recommendsConsecutiveFreeSlotsWithoutCreatingAReservation() throws Exception {
        long labId = labId("LAB-A");
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        int before = reservationCount(userId);

        JsonNode body = recommend(loginAndGetToken(account, "Lab@2026"), labId, DATE.toString(), 2, "SEAT", 1);
        assertThat(body.path("data").path("outcome").asText()).isEqualTo("AVAILABLE");
        JsonNode items = body.path("data").path("recommendations");
        assertThat(items.size()).isGreaterThanOrEqualTo(3);
        assertThat(items.get(0).path("startTime").asText()).isEqualTo("08:30");
        assertThat(items.get(0).path("endTime").asText()).isEqualTo("10:00");
        assertThat(items.get(0).path("startSlot").asInt()).isEqualTo(0);
        assertThat(items.get(0).path("endSlot").asInt()).isEqualTo(2);
        assertThat(items.get(1).path("startTime").asText()).isEqualTo("09:15");
        assertThat(items.get(1).path("endTime").asText()).isEqualTo("10:50");
        assertThat(items).allSatisfy(item -> {
            assertThat(item.path("durationPeriods").asInt()).isEqualTo(2);
            assertThat(item.path("endSlot").asInt() - item.path("startSlot").asInt()).isEqualTo(2);
            assertThat(item.path("startTime").asText().compareTo("08:30")).isNotNegative();
            assertThat(item.path("endTime").asText().compareTo("17:30")).isNotPositive();
        });
        assertThat(reservationCount(userId)).isEqualTo(before);
    }

    @Test
    void overlapOnTheRoomBitmapRemovesCoveredSeatIntervals() throws Exception {
        long labId = labId("LAB-A");
        occupyRoom(labId, com.campus.lab.period.DongfangPeriodTable.schedule().between(LocalTime.of(9, 15), LocalTime.of(10, 0)).value());

        List<String> slots = times(recommend(
                loginAndGetToken("20240001", "Lab@2026"), labId, DATE.toString(), 2, "SEAT", 1));
        assertThat(slots).contains("10:05-11:45");
        assertThat(slots).doesNotContain("08:30-10:00", "09:15-10:50");
    }

    @Test
    void seatOccupancyExcludesWholeRoomIntervalsWhileRoomMaskStaysEmpty() throws Exception {
        long labId = labId("LAB-A");
        long occupied = com.campus.lab.period.DongfangPeriodTable.schedule().between(LocalTime.of(9, 15), LocalTime.of(10, 0)).value();
        Long seatId = jdbcTemplate.queryForObject("""
                SELECT seat_id FROM t_lab_seat
                WHERE lab_id = ? AND seat_status = 'ACTIVE'
                ORDER BY seat_index LIMIT 1
                """, Long.class, labId);
        jdbcTemplate.update("""
                INSERT INTO t_seat_bitmap (seat_id, bitmap_date, mask, version)
                VALUES (?, ?, ?, 0)
                ON DUPLICATE KEY UPDATE mask = ?
                """, seatId, DATE, occupied, occupied);

        JsonNode body = recommend(loginAndGetToken("20240001", "Lab@2026"), labId, DATE.toString(), 2, "ROOM", 1);
        assertThat(body.path("data").path("outcome").asText()).isEqualTo("AVAILABLE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_slot_bitmap
                WHERE lab_id = ? AND bitmap_date = ? AND mask <> 0
                """, Integer.class, labId, DATE)).isZero();
        List<String> slots = times(body);
        assertThat(slots).contains("10:05-11:45");
        assertThat(slots).doesNotContain("08:30-10:00", "09:15-10:50");
    }

    @Test
    void creditAndRuleFailuresReturnNoSlots() throws Exception {
        long labId = labId("LAB-A");
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        jdbcTemplate.update("UPDATE sys_user SET credit_score = 59 WHERE user_id = ?", userId);
        String token = loginAndGetToken(account, "Lab@2026");

        JsonNode credit = recommend(token, labId, DATE.toString(), 2, "SEAT", 1);
        assertThat(credit.path("data").path("outcome").asText()).isEqualTo("CREDIT_BLOCKED");
        assertThat(credit.path("data").path("recommendations")).isEmpty();

        jdbcTemplate.update("UPDATE sys_user SET credit_score = 100 WHERE user_id = ?", userId);
        JsonNode advance = recommend(token, labId, "2026-12-01", 2, "SEAT", 1);
        assertThat(advance.path("data").path("outcome").asText()).isEqualTo("RULE_BLOCKED");
        assertThat(advance.path("data").path("recommendations")).isEmpty();

        jdbcTemplate.update("UPDATE t_lab SET status = 'MAINTENANCE' WHERE lab_id = ?", labId);
        JsonNode closed = recommend(token, labId, DATE.toString(), 2, "ROOM", 1);
        assertThat(closed.path("data").path("outcome").asText()).isEqualTo("RULE_BLOCKED");
        assertThat(closed.path("data").path("recommendations")).isEmpty();
    }

    @Test
    void durationThatDoesNotFitReturnsNoSlots() throws Exception {
        long labId = labId("LAB-A");
        String token = loginAndGetToken("20240001", "Lab@2026");

        JsonNode unaligned = recommend(token, labId, DATE.toString(), 15, "SEAT", 1);
        assertThat(unaligned.path("data").path("outcome").asText()).isEqualTo("DURATION_UNFIT");
        assertThat(unaligned.path("data").path("recommendations")).isEmpty();

        JsonNode tooLong = recommend(token, labId, DATE.toString(), 300, "SEAT", 1);
        assertThat(tooLong.path("data").path("outcome").asText()).isEqualTo("DURATION_UNFIT");
        assertThat(tooLong.path("data").path("recommendations")).isEmpty();

        jdbcTemplate.update("""
                UPDATE t_time_rule SET close_time = '09:15:00', open_end_period = 1
                WHERE lab_id = ? AND day_of_week = 1
                """, labId);
        JsonNode window = recommend(token, labId, DATE.toString(), 2, "SEAT", 1);
        assertThat(window.path("data").path("outcome").asText()).isEqualTo("DURATION_UNFIT");
        assertThat(window.path("data").path("recommendations")).isEmpty();
    }

    @Test
    void nothingFreeReturnsAnEmptyRecommendationList() throws Exception {
        long labId = labId("LAB-A");
        occupyRoom(labId, (1L << com.campus.lab.period.DongfangPeriodTable.schedule().slotCount()) - 1L);

        JsonNode body = recommend(loginAndGetToken("20240001", "Lab@2026"), labId, DATE.toString(), 2, "SEAT", 2);
        assertThat(body.path("data").path("outcome").asText()).isEqualTo("NONE_FREE");
        assertThat(body.path("data").path("recommendations")).isEmpty();
    }

    @Test
    void confirmedSchedulableRequestReachesTheSchedulingSnapshot() throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");
        Map<String, Object> request = new HashMap<>();
        request.put("allocationMode", "SCHEDULABLE");
        request.put("date", DATE.toString());
        request.put("windowStart", "08:30");
        request.put("windowEnd", "12:30");
        request.put("durationPeriods", 3);
        request.put("reservationMode", "SEAT");
        request.put("attendeeCount", 3);
        request.put("seatCount", 3);
        request.put("purpose", "可调度草稿确认后进入排程");
        request.put("preferences", Map.of());

        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allocationMode").value("SCHEDULABLE"))
                .andExpect(jsonPath("$.data.status").value("PENDING_SCHEDULE"))
                .andReturn().getResponse().getContentAsString());
        long reservationId = created.path("data").path("resvId").asLong();

        ScheduleDemand demand = scheduleRepository.loadSnapshot(DATE).problem().demands().stream()
                .filter(item -> item.resvId() == reservationId)
                .findFirst()
                .orElseThrow();
        assertThat(demand.windowStart()).isEqualTo(8 * 60 + 30);
        assertThat(demand.windowEnd()).isEqualTo(12 * 60 + 30);
        assertThat(demand.durationPeriods()).isEqualTo(3);
        assertThat(demand.attendeeCount()).isEqualTo(3);
        assertThat(demand.seatCount()).isEqualTo(3);
    }

    private JsonNode recommend(
            String token, long labId, String date, int duration, String mode, int attendees) throws Exception {
        String response = mockMvc.perform(get("/api/reservations/recommendations")
                        .header("Authorization", "Bearer " + token)
                        .param("labId", Long.toString(labId))
                        .param("date", date)
                        .param("durationPeriods", Integer.toString(duration))
                        .param("reservationMode", mode)
                        .param("attendeeCount", Integer.toString(attendees)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private void occupyRoom(long labId, long mask) {
        jdbcTemplate.update("""
                INSERT INTO t_slot_bitmap (lab_id, bitmap_date, mask, version)
                VALUES (?, ?, ?, 0)
                ON DUPLICATE KEY UPDATE mask = ?
                """, labId, DATE, mask, mask);
    }

    private static List<String> times(JsonNode body) {
        List<String> values = new ArrayList<>();
        body.path("data").path("recommendations").forEach(item ->
                values.add(item.path("startTime").asText() + "-" + item.path("endTime").asText()));
        return values;
    }

    private int reservationCount(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE user_id = ?", Integer.class, userId);
    }

    private long labId(String code) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = ?", Long.class, code);
        assertThat(id).isNotNull();
        return id;
    }
}
