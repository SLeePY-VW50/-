package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class CheckinWindowTest extends AbstractCheckinNonTransactionalTest {

    @Test
    void startMinusConfiguredMinutesIsInclusive() throws Exception {
        assertWindowAccepted(8, 15);
    }

    @Test
    void startTimeIsInclusive() throws Exception {
        assertWindowAccepted(8, 0);
    }

    @Test
    void startPlusConfiguredGraceIsInclusive() throws Exception {
        assertWindowAccepted(7, 45);
    }

    @Test
    void beforeAndAfterWindowAreRejectedWithoutSleeping() throws Exception {
        long labId = createLaboratory(2);
        Cookie cookie = openSession(createTerminal(labId));
        String earlyAccount = createTestAccount("STUDENT");
        createReservation(
                userIdByAccount(earlyAccount), labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 16),
                LocalDateTime.of(2026, 10, 24, 9, 16), false);
        String lateAccount = createTestAccount("STUDENT");
        createReservation(
                userIdByAccount(lateAccount), labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 7, 44),
                LocalDateTime.of(2026, 10, 24, 8, 44), false);

        assertThat(terminalCheckin(cookie, earlyAccount, USER_PASSWORD).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(terminalCheckin(cookie, lateAccount, USER_PASSWORD).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void beforeMinutesAreReadFromSystemConfig() throws Exception {
        long labId = createLaboratory(2);
        Cookie cookie = openSession(createTerminal(labId));
        String account = createTestAccount("STUDENT");
        createReservation(
                userIdByAccount(account), labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 16),
                LocalDateTime.of(2026, 10, 24, 9, 16), false);
        assertThat(terminalCheckin(cookie, account, USER_PASSWORD).andReturn()
                .getResponse().getStatus()).isEqualTo(404);

        try {
            jdbcTemplate.update("""
                    UPDATE t_sys_config SET config_value = '20'
                    WHERE config_key = 'checkin.before.minutes'
                    """);
            assertThat(terminalCheckin(cookie, account, USER_PASSWORD).andReturn()
                    .getResponse().getStatus()).isEqualTo(200);
        } finally {
            jdbcTemplate.update("""
                    UPDATE t_sys_config SET config_value = '15'
                    WHERE config_key = 'checkin.before.minutes'
                    """);
        }
    }

    @Test
    void graceMinutesAreReadFromSystemConfig() throws Exception {
        long labId = createLaboratory(2);
        Cookie cookie = openSession(createTerminal(labId));
        String account = createTestAccount("STUDENT");
        createReservation(
                userIdByAccount(account), labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 7, 44),
                LocalDateTime.of(2026, 10, 24, 8, 44), false);
        assertThat(terminalCheckin(cookie, account, USER_PASSWORD).andReturn()
                .getResponse().getStatus()).isEqualTo(404);

        try {
            jdbcTemplate.update("""
                    UPDATE t_sys_config SET config_value = '20'
                    WHERE config_key = 'checkin.grace.minutes'
                    """);
            assertThat(terminalCheckin(cookie, account, USER_PASSWORD).andReturn()
                    .getResponse().getStatus()).isEqualTo(200);
        } finally {
            jdbcTemplate.update("""
                    UPDATE t_sys_config SET config_value = '15'
                    WHERE config_key = 'checkin.grace.minutes'
                    """);
        }
    }

    private void assertWindowAccepted(int hour, int minute) throws Exception {
        long labId = createLaboratory(2);
        Cookie cookie = openSession(createTerminal(labId));
        String account = createTestAccount("STUDENT");
        long reservationId = createReservation(
                userIdByAccount(account), labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, hour, minute),
                LocalDateTime.of(2026, 10, 24, hour + 1, minute), false);

        MvcResult result = terminalCheckin(cookie, account, USER_PASSWORD).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(result).path("data").path("reservationId").asLong())
                .isEqualTo(reservationId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_reservation WHERE resv_id = ?", String.class, reservationId))
                .isEqualTo("IN_USE");
    }
}
