package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class ReservationSeatCountConstraintIntegrationTest extends AbstractIntegrationTest {

    @Test
    void schedulableSeatReservationRejectsMissingSeatCount() {
        assertThatThrownBy(() -> insertReservation("SCHEDULABLE", "SEAT", null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_t_reservation_seat_count");
    }

    @Test
    void fixedSeatReservationRejectsZeroSeatCount() {
        assertThatThrownBy(() -> insertReservation("FIXED", "SEAT", 0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_t_reservation_seat_count");
    }

    @Test
    void roomReservationAllowsPendingNullAndFixedPositiveSeatCount() {
        assertThatCode(() -> {
            insertReservation("SCHEDULABLE", "ROOM", null);
            insertReservation("FIXED", "ROOM", 20);
        }).doesNotThrowAnyException();
    }

    private void insertReservation(String allocationMode, String reservationMode, Integer seatCount) {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM sys_user WHERE account = '20240001'", Long.class);
        Long labId = "FIXED".equals(allocationMode) ? jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = 'LAB-A'", Long.class) : null;
        boolean fixed = "FIXED".equals(allocationMode);
        jdbcTemplate.update("""
                INSERT INTO t_reservation
                    (user_id, allocation_mode, reservation_mode, request_date,
                     request_window_start, request_window_end, requested_duration_minutes,
                     lab_id, start_time, end_time, attendee_count, seat_count, purpose, status)
                VALUES (?, ?, ?, '2026-11-02', '09:00:00', '10:00:00', 60,
                        ?, ?, ?, 1, ?, ?, ?)
                """, userId, allocationMode, reservationMode, labId,
                fixed ? "2026-11-02 09:00:00" : null,
                fixed ? "2026-11-02 10:00:00" : null,
                seatCount, "v4-test-" + UUID.randomUUID(),
                fixed ? "APPROVED" : "PENDING_SCHEDULE");
    }
}
