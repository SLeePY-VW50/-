package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.lab.reservation.TimeSlotMask;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class CheckoutIntegrationTest extends AbstractCheckinNonTransactionalTest {

    @MockitoSpyBean
    private Clock clock;

    @AfterEach
    void resetClock() {
        Mockito.reset(clock);
    }

    @Test
    void seatCheckoutAtTenTenReleasesFromTenThirtyAndRepeatChangesNothing() throws Exception {
        at("2026-10-24T02:10:00Z");
        long labId = createLaboratory(2);
        Fixture owner = fixture("STUDENT");
        Fixture other = fixture("STUDENT");
        long reservationId = createReservation(
                owner.userId(), labId, "SEAT", "IN_USE",
                LocalDateTime.of(2026, 10, 24, 9, 0),
                LocalDateTime.of(2026, 10, 24, 12, 0), true);
        createReservation(
                other.userId(), labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 12, 0),
                LocalDateTime.of(2026, 10, 24, 13, 0), true);
        long seatId = assignedSeatId(reservationId);

        MvcResult first = checkout(owner.token(), reservationId).andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(first).path("data").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(responseJson(first).path("data").path("checkoutChannel").asText())
                .isEqualTo("WEB_ACCOUNT");
        assertThat(responseJson(first).path("data").path("checkoutTime").asText()).isNotBlank();
        long expectedMask = mask("09:00", "10:30") | mask("12:00", "13:00");
        assertThat(seatMask(seatId)).isEqualTo(expectedMask);
        assertThat(checkoutLogCount(reservationId)).isEqualTo(1);
        LocalDateTime checkoutTime = checkoutTime(reservationId);
        int version = bitmapVersion(seatId);

        MvcResult repeated = checkout(owner.token(), reservationId).andReturn();
        assertThat(repeated.getResponse().getStatus()).isEqualTo(200);
        assertThat(checkoutTime(reservationId)).isEqualTo(checkoutTime);
        assertThat(seatMask(seatId)).isEqualTo(expectedMask);
        assertThat(bitmapVersion(seatId)).isEqualTo(version);
        assertThat(checkoutLogCount(reservationId)).isEqualTo(1);
    }

    @Test
    void seatCheckoutAtTenThirtyReleasesOnlyFromEleven() throws Exception {
        at("2026-10-24T02:30:00Z");
        long labId = createLaboratory(2);
        Fixture owner = fixture("STUDENT");
        long reservationId = createReservation(
                owner.userId(), labId, "SEAT", "IN_USE",
                LocalDateTime.of(2026, 10, 24, 9, 0),
                LocalDateTime.of(2026, 10, 24, 12, 0), true);
        long seatId = assignedSeatId(reservationId);

        checkout(owner.token(), reservationId).andReturn();

        assertThat(seatMask(seatId)).isEqualTo(mask("09:00", "11:00"));
        assertThat(checkoutTime(reservationId)).isEqualTo(LocalDateTime.of(2026, 10, 24, 10, 30));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT checkout_channel FROM t_reservation WHERE resv_id = ?",
                String.class, reservationId)).isEqualTo("WEB_ACCOUNT");
    }

    @Test
    void roomCheckoutClearsOnlyRemainingWholeRoomMaskAndPreservesOtherReservation() throws Exception {
        at("2026-10-24T02:10:00Z");
        long labId = createLaboratory(2);
        Fixture owner = fixture("TEACHER");
        Fixture other = fixture("TEACHER");
        long reservationId = createReservation(
                owner.userId(), labId, "ROOM", "IN_USE",
                LocalDateTime.of(2026, 10, 24, 9, 0),
                LocalDateTime.of(2026, 10, 24, 12, 0), true);
        createReservation(
                other.userId(), labId, "ROOM", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 12, 0),
                LocalDateTime.of(2026, 10, 24, 13, 0), true);

        MvcResult result = checkout(owner.token(), reservationId).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(roomMask(labId)).isEqualTo(mask("09:00", "10:30") | mask("12:00", "13:00"));
        assertThat(checkoutLogCount(reservationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation_seat WHERE resv_id = ?", Integer.class, reservationId))
                .isEqualTo(2);
    }

    @Test
    void nonOwnerAdministratorsAndIllegalStatesCannotCheckout() throws Exception {
        long labId = createLaboratory(2);
        Fixture owner = fixture("STUDENT");
        Fixture other = fixture("STUDENT");
        long reservationId = createReservation(
                owner.userId(), labId, "SEAT", "IN_USE",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 9, 0), true);

        assertThat(checkout(other.token(), reservationId).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        for (String role : new String[] {"LAB_ADMIN", "SYS_ADMIN"}) {
            Fixture administrator = fixture(role);
            assertThat(checkout(administrator.token(), reservationId).andReturn()
                    .getResponse().getStatus()).isEqualTo(403);
        }
        for (String state : new String[] {"APPROVED", "CANCELLED", "NO_SHOW", "EXPIRED", "VIOLATION_TERMINATED"}) {
            jdbcTemplate.update("UPDATE t_reservation SET status = ? WHERE resv_id = ?", state, reservationId);
            assertThat(checkout(owner.token(), reservationId).andReturn().getResponse().getStatus())
                    .isEqualTo(409);
            assertThat(statusOf(reservationId)).isEqualTo(state);
        }
        assertThat(checkoutLogCount(reservationId)).isZero();
    }

    @Test
    void secondSeatReleaseFailureRollsBackFirstSeatStatusCheckoutFieldsAndLog() throws Exception {
        at("2026-10-24T02:10:00Z");
        long labId = createLaboratory(2);
        Fixture owner = fixture("STUDENT");
        long reservationId = createReservation(
                owner.userId(), labId, "SEAT", "IN_USE",
                LocalDateTime.of(2026, 10, 24, 9, 0),
                LocalDateTime.of(2026, 10, 24, 12, 0), true);
        long firstSeat = assignedSeatId(reservationId);
        long secondSeat = jdbcTemplate.queryForObject("""
                SELECT seat_id FROM t_lab_seat
                WHERE lab_id = ? AND seat_id <> ? ORDER BY seat_index, seat_id LIMIT 1
                """, Long.class, labId, firstSeat);
        long assignmentMask = jdbcTemplate.queryForObject(
                "SELECT time_mask FROM t_reservation_seat WHERE resv_id = ? AND seat_id = ?",
                Long.class, reservationId, firstSeat);
        jdbcTemplate.update("UPDATE t_reservation SET attendee_count = 2, seat_count = 2 WHERE resv_id = ?",
                reservationId);
        jdbcTemplate.update("""
                INSERT INTO t_reservation_seat (resv_id, seat_id, time_mask, assignment_method)
                VALUES (?, ?, ?, 'FIXED')
                """, reservationId, secondSeat, assignmentMask);
        jdbcTemplate.update("""
                INSERT INTO t_seat_bitmap (seat_id, bitmap_date, mask, version)
                VALUES (?, ?, 0, 0)
                ON DUPLICATE KEY UPDATE bitmap_id = t_seat_bitmap.bitmap_id
                """, secondSeat, TODAY);
        jdbcTemplate.update("""
                UPDATE t_seat_bitmap SET mask = mask | ? WHERE seat_id = ? AND bitmap_date = ?
                """, assignmentMask, secondSeat, TODAY);
        Map<String, Object> firstBefore = bitmap(firstSeat);
        jdbcTemplate.update("UPDATE t_seat_bitmap SET mask = 0 WHERE seat_id = ? AND bitmap_date = ?",
                secondSeat, TODAY);

        MvcResult result = checkout(owner.token(), reservationId).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(statusOf(reservationId)).isEqualTo("IN_USE");
        assertThat(bitmap(firstSeat)).isEqualTo(firstBefore);
        assertThat(seatMask(secondSeat)).isZero();
        assertThat(checkoutTime(reservationId)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT checkout_channel FROM t_reservation WHERE resv_id = ?",
                String.class, reservationId)).isNull();
        assertThat(checkoutLogCount(reservationId)).isZero();
    }

    @Test
    void wechatCheckoutRecordsItsChannel() throws Exception {
        at("2026-10-24T02:10:00Z");
        long labId = createLaboratory(2);
        Fixture owner = fixture("STUDENT");
        long reservationId = createReservation(owner.userId(), labId, "SEAT", "IN_USE",
                LocalDateTime.of(2026, 10, 24, 9, 0),
                LocalDateTime.of(2026, 10, 24, 12, 0), true);
        mockMvc.perform(post("/api/checkin/{id}/checkout", reservationId)
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"WECHAT_QR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutChannel").value("WECHAT_QR"));
    }

    @Test
    void nullCheckoutChannelKeepsWebDefault() throws Exception {
        at("2026-10-24T02:10:00Z");
        long labId = createLaboratory(2);
        Fixture owner = fixture("STUDENT");
        long reservationId = createReservation(owner.userId(), labId, "SEAT", "IN_USE",
                LocalDateTime.of(2026, 10, 24, 9, 0),
                LocalDateTime.of(2026, 10, 24, 12, 0), true);
        mockMvc.perform(post("/api/checkin/{id}/checkout", reservationId)
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutChannel").value("WEB_ACCOUNT"));
    }

    @Test
    void unknownCheckoutChannelIsBadRequest() throws Exception {
        Fixture owner = fixture("STUDENT");
        mockMvc.perform(post("/api/checkin/{id}/checkout", 1)
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"CLIENT_INVENTED\"}"))
                .andExpect(status().isBadRequest());
    }

    private void at(String instant) {
        doReturn(Instant.parse(instant)).when(clock).instant();
    }

    private Fixture fixture(String role) throws Exception {
        String account = createTestAccount(role);
        return new Fixture(account, userIdByAccount(account), loginAndGetToken(account, USER_PASSWORD));
    }

    private ResultActions checkout(String token, long reservationId) throws Exception {
        return mockMvc.perform(post("/api/checkin/{id}/checkout", reservationId)
                .header("Authorization", "Bearer " + token));
    }

    private long assignedSeatId(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT seat_id FROM t_reservation_seat WHERE resv_id = ? ORDER BY seat_id LIMIT 1",
                Long.class, reservationId);
    }

    private long seatMask(long seatId) {
        return jdbcTemplate.queryForObject(
                "SELECT mask FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?",
                Long.class, seatId, TODAY);
    }

    private long roomMask(long labId) {
        return jdbcTemplate.queryForObject(
                "SELECT mask FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, TODAY);
    }

    private int bitmapVersion(long seatId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?",
                Integer.class, seatId, TODAY);
    }

    private Map<String, Object> bitmap(long seatId) {
        return jdbcTemplate.queryForMap(
                "SELECT mask, version FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?",
                seatId, TODAY);
    }

    private LocalDateTime checkoutTime(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT checkout_time FROM t_reservation WHERE resv_id = ?",
                LocalDateTime.class, reservationId);
    }

    private String statusOf(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_reservation WHERE resv_id = ?", String.class, reservationId);
    }

    private int checkoutLogCount(long reservationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log
                WHERE resv_id = ? AND from_status = 'IN_USE' AND to_status = 'COMPLETED'
                """, Integer.class, reservationId);
    }

    private static long mask(String start, String end) {
        return TimeSlotMask.between(LocalTime.parse(start), LocalTime.parse(end)).value();
    }

    private record Fixture(String account, long userId, String token) {
    }
}
