package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

class EarlyCheckinIntegrationTest extends AbstractCheckinNonTransactionalTest {

    @MockitoSpyBean
    private Clock clock;

    @AfterEach
    void resetClock() {
        Mockito.reset(clock);
    }

    @Test
    void repeatBeforeStartIsIdempotentAndAnotherApprovedReservationStillChecksIn() throws Exception {
        long labId = createLaboratory(2);
        Cookie cookie = openSession(createTerminal(labId));
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        long reservationId = createReservation(
                userId, labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 9, 0), false);

        at("2026-10-23T23:45:00Z");
        MvcResult first = terminalCheckin(cookie, account, USER_PASSWORD).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(first).path("data").path("alreadyCheckedIn").asBoolean()).isFalse();
        LocalDateTime originalCheckinTime = checkinTime(reservationId);
        assertThat(originalCheckinTime).isEqualTo(LocalDateTime.of(2026, 10, 24, 7, 45));

        at("2026-10-23T23:50:00Z");
        MvcResult repeated = terminalCheckin(cookie, account, USER_PASSWORD).andReturn();

        assertThat(repeated.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(repeated).path("data").path("reservationId").asLong())
                .isEqualTo(reservationId);
        assertThat(responseJson(repeated).path("data").path("alreadyCheckedIn").asBoolean()).isTrue();
        assertThat(checkinTime(reservationId)).isEqualTo(originalCheckinTime);
        assertThat(checkinLogCount(reservationId)).isEqualTo(1);

        long anotherApproved = createReservation(
                userId, labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 9, 0), false);
        MvcResult additional = terminalCheckin(cookie, account, USER_PASSWORD).andReturn();
        assertThat(additional.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(additional).path("data").path("reservationId").asLong())
                .isEqualTo(anotherApproved);
        assertThat(responseJson(additional).path("data").path("alreadyCheckedIn").asBoolean()).isFalse();
        assertThat(checkinLogCount(reservationId)).isEqualTo(1);
        assertThat(checkinLogCount(anotherApproved)).isEqualTo(1);
    }

    private void at(String instant) {
        doReturn(Instant.parse(instant)).when(clock).instant();
    }

    private LocalDateTime checkinTime(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT checkin_time FROM t_reservation WHERE resv_id = ?",
                LocalDateTime.class, reservationId);
    }

    private int checkinLogCount(long reservationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log
                WHERE resv_id = ? AND from_status = 'APPROVED' AND to_status = 'IN_USE'
                """, Integer.class, reservationId);
    }
}
