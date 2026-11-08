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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class ReservationIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Test
    void fixedSeatIsApprovedWithAssignmentsBitmapsLogAndAllowsDisjointTime() throws Exception {
        long labId = labId("LAB-A");
        String token = loginAndGetToken("20240001", "Lab@2026");

        JsonNode created = createReservation(token, fixedSeatRequest(labId, "09:00", "10:00", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.allocationMode").value("FIXED"))
                .andExpect(jsonPath("$.data.reservationMode").value("SEAT"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.labId").value(labId))
                .andExpect(jsonPath("$.data.seatCount").value(2))
                .andExpect(jsonPath("$.data.seats.length()").value(2))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andReturn();
        long reservationId = created.path("data").path("resvId").asLong();
        assertThat(reservationId).isPositive();
        assertThat(created.path("data").path("seats"))
                .allSatisfy(seat -> {
                    assertThat(seat.path("seatId").asLong()).isPositive();
                    assertThat(seat.path("seatCode").asText()).isNotBlank();
                    assertThat(seat.path("zoneCode").asText()).isNotBlank();
                    assertThat(seat.path("zoneMismatch").asBoolean()).isFalse();
                    assertThat(seat.path("mask").asText()).isEqualTo(
                            Long.toUnsignedString(TimeSlotMask.between(
                                    LocalTime.of(9, 0), LocalTime.of(10, 0)).value()));
                });
        assertThat(count("t_reservation", reservationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_reservation WHERE resv_id = ?", String.class, reservationId))
                .isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation_seat WHERE resv_id = ? "
                        + "AND assignment_method = 'FIXED'", Integer.class, reservationId))
                .isEqualTo(2);
        long expectedMask = TimeSlotMask.between(LocalTime.of(9, 0), LocalTime.of(10, 0)).value();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM t_reservation_seat rs
                JOIN t_seat_bitmap b ON b.seat_id = rs.seat_id AND b.bitmap_date = ?
                WHERE rs.resv_id = ? AND b.mask = ?
                """, Integer.class, DATE, reservationId, expectedMask)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log
                WHERE resv_id = ? AND from_status IS NULL AND to_status = 'APPROVED'
                """, Integer.class, reservationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT mask FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, DATE)).isZero();

        JsonNode disjoint = createReservation(token, fixedSeatRequest(labId, "10:00", "11:00", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andReturn();
        assertThat(disjoint.path("data").path("resvId").asLong()).isNotEqualTo(reservationId);
    }

    @Test
    void fixedRoomAllocatesEveryActiveSeatAndOnlyUpdatesRoomBitmap() throws Exception {
        long labId = labId("LAB-A");
        String token = loginAndGetToken("20240001", "Lab@2026");
        Map<String, Object> request = fixedRoomRequest(labId, "09:00", "10:00", 20);
        request.put("seatCount", 1);

        JsonNode created = createReservation(token, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.reservationMode").value("ROOM"))
                .andExpect(jsonPath("$.data.seatCount").value(20))
                .andExpect(jsonPath("$.data.seats.length()").value(20))
                .andReturn();
        long reservationId = created.path("data").path("resvId").asLong();
        int activeSeats = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_lab_seat WHERE lab_id = ? AND seat_status = 'ACTIVE'",
                Integer.class, labId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation_seat WHERE resv_id = ?",
                Integer.class, reservationId)).isEqualTo(activeSeats);
        long expectedMask = TimeSlotMask.between(LocalTime.of(9, 0), LocalTime.of(10, 0)).value();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT mask FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, DATE)).isEqualTo(expectedMask);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_seat_bitmap b
                JOIN t_lab_seat s ON s.seat_id = b.seat_id
                WHERE s.lab_id = ? AND b.bitmap_date = ? AND b.mask <> 0
                """, Integer.class, labId, DATE)).isZero();
    }

    @Test
    void labAndSystemAdminsCannotCreateReservations() throws Exception {
        long labId = labId("LAB-A");
        for (String account : new String[] {"L1001", "A1001"}) {
            String token = loginAndGetToken(account, "Lab@2026");
            Map<String, Object> request = fixedSeatRequest(labId, "09:00", "10:00", 1);
            request.put("purpose", "管理员禁止预约-" + account);
            createReservation(token, request)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_reservation WHERE purpose = ?",
                    Integer.class, request.get("purpose"))).isZero();
        }
    }

    @Test
    void requestUserIdCannotOverrideJwtSubject() throws Exception {
        long labId = labId("LAB-A");
        long jwtUserId = userIdByAccount("20240001");
        String token = loginAndGetToken("20240001", "Lab@2026");
        Map<String, Object> request = fixedSeatRequest(labId, "09:00", "10:00", 1);
        request.put("userId", userIdByAccount("T1001"));

        JsonNode created = createReservation(token, request)
                .andExpect(status().isOk()).andReturn();
        long reservationId = created.path("data").path("resvId").asLong();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM t_reservation WHERE resv_id = ?", Long.class, reservationId))
                .isEqualTo(jwtUserId);
        assertThat(created.path("data").has("userId")).isFalse();
    }

    @Test
    void creditBelowThresholdIsRejectedWithoutOccupancy() throws Exception {
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        jdbcTemplate.update("UPDATE sys_user SET credit_score = 59 WHERE user_id = ?", userId);
        String token = loginAndGetToken(account, "Lab@2026");

        createReservation(token, fixedSeatRequest(labId("LAB-A"), "09:00", "10:00", 1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    void inactiveLaboratoryIsRejected() throws Exception {
        long labId = labId("LAB-A");
        jdbcTemplate.update("UPDATE t_lab SET status = 'MAINTENANCE' WHERE lab_id = ?", labId);
        String token = loginAndGetToken("20240001", "Lab@2026");

        createReservation(token, fixedSeatRequest(labId, "09:00", "10:00", 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("实验室当前不可预约"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "2026-10-24,09:00,10:00",
            "2027-01-01,09:00,10:00",
            "2026-11-02,07:30,08:00",
            "2026-11-02,09:00,13:30",
            "2026-11-02,09:15,10:00"
    })
    void invalidAdvanceOpeningDurationOrAlignmentIsRejected(String date, String start, String end)
            throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");
        Map<String, Object> request = fixedSeatRequest(labId("LAB-A"), start, end, 1);
        request.put("date", date);

        createReservation(token, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void seatCountMustEqualAttendeeCount() throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");
        Map<String, Object> request = fixedSeatRequest(labId("LAB-A"), "09:00", "10:00", 2);
        request.put("seatCount", 1);

        createReservation(token, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("seatCount")));
    }

    @Test
    void roomAttendeeCountCannotExceedCapacity() throws Exception {
        String token = loginAndGetToken("T1001", "Lab@2026");

        createReservation(token, fixedRoomRequest(labId("LAB-A"), "09:00", "10:00", 21))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("容量")));
    }

    @Test
    void fixedSeatAttendeeCountCannotExceedCapacityEvenWithActiveSeats() throws Exception {
        long labId = labId("LAB-A");
        jdbcTemplate.update("UPDATE t_lab SET capacity = 1 WHERE lab_id = ?", labId);
        jdbcTemplate.update("""
                UPDATE t_lab_seat SET seat_status = 'DISABLED'
                WHERE lab_id = ? AND seat_index > 2
                """, labId);
        String token = loginAndGetToken("20240001", "Lab@2026");

        createReservation(token, fixedSeatRequest(labId, "09:00", "10:00", 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("容量")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE user_id = ?",
                Integer.class, userIdByAccount("20240001"))).isZero();
    }

    @Test
    void sameUserCannotOverlapAcrossLaboratories() throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");
        createReservation(token, fixedSeatRequest(labId("LAB-A"), "09:00", "10:00", 1))
                .andExpect(status().isOk());

        createReservation(token, fixedSeatRequest(labId("LAB-B"), "09:30", "10:30", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE user_id = ?",
                Integer.class, userIdByAccount("20240001"))).isEqualTo(1);
    }

    @Test
    void fixedSeatAcceptsEndOfDay24() throws Exception {
        long labId = labId("LAB-A");
        jdbcTemplate.update("""
                UPDATE t_time_rule SET close_time = '24:00:00'
                WHERE lab_id = ? AND day_of_week = 1
                """, labId);
        String token = loginAndGetToken("20240001", "Lab@2026");

        JsonNode created = createReservation(token, fixedSeatRequest(labId, "22:00", "24:00", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.windowEnd").value("24:00:00"))
                .andExpect(jsonPath("$.data.endTime").value("2026-11-03T00:00:00"))
                .andReturn();
        long reservationId = created.path("data").path("resvId").asLong();
        long expectedMask = TimeSlotMask.between(LocalTime.of(22, 0), LocalTime.MIDNIGHT).value();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat rs
                JOIN t_seat_bitmap b ON b.seat_id = rs.seat_id AND b.bitmap_date = ?
                WHERE rs.resv_id = ? AND b.mask = ?
                """, Integer.class, DATE, reservationId, expectedMask)).isEqualTo(1);
    }

    @Test
    void schedulableSeatOnlyPersistsPendingRequestWithoutOccupancyOrTask() throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");
        long firstLabId = labId("LAB-A");
        long secondLabId = labId("LAB-B");
        Map<String, Object> request = schedulableRequest("SEAT", 1, 60);
        request.put("preferences", Map.of(
                "allowedLabIds", java.util.List.of(firstLabId, secondLabId),
                "minSpecLevel", "HIGH"));

        JsonNode created = createReservation(token, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_SCHEDULE"))
                .andExpect(jsonPath("$.data.allocationMode").value("SCHEDULABLE"))
                .andExpect(jsonPath("$.data.durationMinutes").value(60))
                .andExpect(jsonPath("$.data.seats.length()").value(0))
                .andReturn();
        JsonNode data = created.path("data");
        long reservationId = data.path("resvId").asLong();
        assertThat(data.path("labId").isNull()).isTrue();
        assertThat(data.path("startTime").isNull()).isTrue();
        assertThat(data.path("endTime").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_reservation WHERE resv_id = ?", String.class, reservationId))
                .isEqualTo("PENDING_SCHEDULE");
        assertThat(count("t_reservation_seat", reservationId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_slot_bitmap WHERE bitmap_date = ? AND lab_id IN (?, ?)",
                Integer.class, DATE, firstLabId, secondLabId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_seat_bitmap b
                JOIN t_lab_seat s ON s.seat_id = b.seat_id
                WHERE b.bitmap_date = ? AND s.lab_id IN (?, ?)
                """, Integer.class, DATE, firstLabId, secondLabId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_schedule_task", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log
                WHERE resv_id = ? AND from_status IS NULL AND to_status = 'PENDING_SCHEDULE'
                """, Integer.class, reservationId)).isEqualTo(1);
    }

    @Test
    void schedulableRoomCanUseOptionalLabCandidateWithNullSeatCount() throws Exception {
        String token = loginAndGetToken("T1001", "Lab@2026");
        Map<String, Object> request = schedulableRequest("ROOM", 20, 120);
        request.put("labId", labId("LAB-A"));

        JsonNode created = createReservation(token, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_SCHEDULE"))
                .andExpect(jsonPath("$.data.reservationMode").value("ROOM"))
                .andReturn();

        assertThat(created.path("data").path("labId").isNull()).isTrue();
        assertThat(created.path("data").path("seatCount").isNull()).isTrue();
        assertThat(count("t_reservation_seat", created.path("data").path("resvId").asLong())).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {20, 250, 90})
    void schedulableRejectsInvalidDuration(int duration) throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");
        Map<String, Object> request = schedulableRequest("SEAT", 1, duration);
        if (duration == 90) {
            request.put("windowEnd", "09:00");
        }

        createReservation(token, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("时长")));
    }

    @Test
    void schedulableFiltersCapacityUnsuitableCandidates() throws Exception {
        String token = loginAndGetToken("T1001", "Lab@2026");
        Map<String, Object> request = schedulableRequest("ROOM", 21, 60);
        request.put("preferences", Map.of(
                "allowedLabIds", java.util.List.of(labId("LAB-A"), labId("LAB-B"))));

        createReservation(token, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_SCHEDULE"))
                .andExpect(jsonPath("$.data.labId").doesNotExist());
    }

    @Test
    void schedulableFiltersCandidatesWithoutUsableOpeningWindow() throws Exception {
        jdbcTemplate.update("""
                UPDATE t_time_rule SET open_time = '18:00:00', close_time = '20:00:00'
                WHERE lab_id = ? AND day_of_week = 1
                """, labId("LAB-A"));
        String token = loginAndGetToken("20240001", "Lab@2026");
        Map<String, Object> request = schedulableRequest("SEAT", 1, 60);
        request.put("preferences", Map.of(
                "allowedLabIds", java.util.List.of(labId("LAB-A"), labId("LAB-B"))));

        createReservation(token, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_SCHEDULE"));
    }

    @Test
    void schedulableRejectsUnknownCandidateLaboratory() throws Exception {
        String token = loginAndGetToken("20240001", "Lab@2026");
        Map<String, Object> request = schedulableRequest("SEAT", 1, 60);
        request.put("preferences", Map.of("allowedLabIds", java.util.List.of(999999999L)));

        createReservation(token, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private static Map<String, Object> schedulableRequest(String mode, int attendees, int duration) {
        Map<String, Object> request = new HashMap<>();
        request.put("allocationMode", "SCHEDULABLE");
        request.put("date", DATE.toString());
        request.put("windowStart", "08:00");
        request.put("windowEnd", "12:00");
        request.put("durationMinutes", duration);
        request.put("reservationMode", mode);
        request.put("attendeeCount", attendees);
        if ("SEAT".equals(mode)) {
            request.put("seatCount", attendees);
        }
        request.put("purpose", "可调度预约集成测试");
        request.put("preferences", Map.of());
        return request;
    }

    private static Map<String, Object> fixedRoomRequest(
            long labId, String start, String end, int attendees) {
        Map<String, Object> request = new HashMap<>();
        request.put("allocationMode", "FIXED");
        request.put("labId", labId);
        request.put("date", DATE.toString());
        request.put("startTime", start);
        request.put("endTime", end);
        request.put("reservationMode", "ROOM");
        request.put("attendeeCount", attendees);
        request.put("purpose", "固定整室预约集成测试");
        request.put("preferences", Map.of());
        return request;
    }

    private ResultActionsAdapter createReservation(
            String token, Map<String, Object> request) throws Exception {
        return new ResultActionsAdapter(mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))));
    }

    private static Map<String, Object> fixedSeatRequest(
            long labId, String start, String end, int count) {
        Map<String, Object> request = new HashMap<>();
        request.put("allocationMode", "FIXED");
        request.put("labId", labId);
        request.put("date", DATE.toString());
        request.put("startTime", start);
        request.put("endTime", end);
        request.put("reservationMode", "SEAT");
        request.put("attendeeCount", count);
        request.put("seatCount", count);
        request.put("purpose", "固定工位预约集成测试");
        request.put("preferences", Map.of("nearWindow", true, "minSpecLevel", "STANDARD"));
        return request;
    }

    private int count(String table, long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE resv_id = ?", Integer.class, reservationId);
    }

    private long labId(String code) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = ?", Long.class, code);
        assertThat(id).isNotNull();
        return id;
    }

    private static final class MvcResultActions {
        private final org.springframework.test.web.servlet.ResultActions actions;

        private MvcResultActions(org.springframework.test.web.servlet.ResultActions actions) {
            this.actions = actions;
        }

        private ResultActionsAdapter andExpect(
                org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return new ResultActionsAdapter(actions);
        }
    }

    private static final class ResultActionsAdapter {
        private final org.springframework.test.web.servlet.ResultActions actions;

        private ResultActionsAdapter(org.springframework.test.web.servlet.ResultActions actions) {
            this.actions = actions;
        }

        private ResultActionsAdapter andExpect(
                org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        private JsonNode andReturn() throws Exception {
            MvcResult result = actions.andReturn();
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(result.getResponse().getContentAsString());
        }
    }
}
