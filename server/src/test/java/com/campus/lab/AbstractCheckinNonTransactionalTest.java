package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.lab.reservation.TimeSlotMask;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

public abstract class AbstractCheckinNonTransactionalTest extends AbstractReservationNonTransactionalTest {

    protected static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    protected static final String USER_PASSWORD = "Lab@2026";

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    protected TerminalFixture createTerminal(long labId) {
        String terminalCode = "TERM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String secret = "test-terminal-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO t_device
                    (lab_id, device_type, device_name, model, status, terminal_code, terminal_secret_hash)
                VALUES (?, 'CHECKIN_TERMINAL', '签到终端集成测试', 'TEST', 'ACTIVE', ?, ?)
                """, labId, terminalCode, passwordEncoder.encode(secret));
        Long deviceId = jdbcTemplate.queryForObject(
                "SELECT device_id FROM t_device WHERE terminal_code = ?", Long.class, terminalCode);
        assertThat(deviceId).isNotNull();
        return new TerminalFixture(deviceId, labId, terminalCode, secret);
    }

    protected MvcResult terminalSession(String terminalCode, String terminalSecret) throws Exception {
        return mockMvc.perform(post("/api/checkin/terminal/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "terminalCode", terminalCode,
                                "terminalSecret", terminalSecret))))
                .andReturn();
    }

    protected Cookie openSession(TerminalFixture terminal) throws Exception {
        MvcResult result = terminalSession(terminal.terminalCode(), terminal.secret());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Cookie cookie = result.getResponse().getCookie("lab_terminal");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    protected ResultActions terminalCheckin(Cookie cookie, String account, String password) throws Exception {
        var request = post("/api/checkin/terminal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "account", account,
                        "password", password)));
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    protected long createReservation(
            long userId,
            long labId,
            String mode,
            String status,
            LocalDateTime start,
            LocalDateTime end,
            boolean withOccupancy) {
        List<Long> seatIds = jdbcTemplate.queryForList(
                "SELECT seat_id FROM t_lab_seat WHERE lab_id = ? ORDER BY seat_index, seat_id",
                Long.class, labId);
        assertThat(seatIds).isNotEmpty();
        long durationMinutes = Duration.between(start, end).toMinutes();
        String requestEnd = end.toLocalDate().isAfter(start.toLocalDate())
                ? "24:00:00" : end.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME);
        Integer seatCount = "SEAT".equals(mode) ? 1 : seatIds.size();
        String purpose = "checkin-test-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO t_reservation
                    (user_id, allocation_mode, reservation_mode, request_date,
                     request_window_start, request_window_end, requested_duration_minutes,
                     lab_id, start_time, end_time, attendee_count, seat_count, purpose, status)
                VALUES (?, 'FIXED', ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                """, userId, mode, start.toLocalDate(), start.toLocalTime(), requestEnd,
                durationMinutes, labId, start, end, seatCount, purpose, status);
        Long reservationId = jdbcTemplate.queryForObject(
                "SELECT resv_id FROM t_reservation WHERE purpose = ?", Long.class, purpose);
        assertThat(reservationId).isNotNull();
        jdbcTemplate.update("""
                INSERT INTO t_reservation_log (resv_id, from_status, to_status, operator_id, reason)
                VALUES (?, NULL, ?, ?, '签到测试初始预约')
                """, reservationId, status, userId);
        if ("IN_USE".equals(status)) {
            jdbcTemplate.update("""
                    UPDATE t_reservation
                    SET checkin_time = ?, checkin_channel = 'TERMINAL_ACCOUNT'
                    WHERE resv_id = ?
                    """, start, reservationId);
        }
        if (withOccupancy) {
            long mask = TimeSlotMask.between(start.toLocalTime(), end.toLocalTime()).value();
            jdbcTemplate.update("""
                    INSERT INTO t_slot_bitmap (lab_id, bitmap_date, mask, version)
                    VALUES (?, ?, 0, 0)
                    ON DUPLICATE KEY UPDATE bitmap_id = t_slot_bitmap.bitmap_id
                    """, labId, start.toLocalDate());
            if ("ROOM".equals(mode)) {
                for (long seatId : seatIds) {
                    jdbcTemplate.update("""
                            INSERT INTO t_reservation_seat
                                (resv_id, seat_id, time_mask, assignment_method)
                            VALUES (?, ?, ?, 'FIXED')
                            """, reservationId, seatId, mask);
                }
                jdbcTemplate.update("""
                        UPDATE t_slot_bitmap SET mask = mask | ?
                        WHERE lab_id = ? AND bitmap_date = ?
                        """, mask, labId, start.toLocalDate());
            } else {
                long seatId = seatIds.getFirst();
                jdbcTemplate.update("""
                        INSERT INTO t_reservation_seat
                            (resv_id, seat_id, time_mask, assignment_method)
                        VALUES (?, ?, ?, 'FIXED')
                        """, reservationId, seatId, mask);
                jdbcTemplate.update("""
                        INSERT INTO t_seat_bitmap (seat_id, bitmap_date, mask, version)
                        VALUES (?, ?, 0, 0)
                        ON DUPLICATE KEY UPDATE bitmap_id = t_seat_bitmap.bitmap_id
                        """, seatId, start.toLocalDate());
                jdbcTemplate.update("""
                        UPDATE t_seat_bitmap SET mask = mask | ?
                        WHERE seat_id = ? AND bitmap_date = ?
                        """, mask, seatId, start.toLocalDate());
            }
        }
        return reservationId;
    }

    protected record TerminalFixture(long deviceId, long labId, String terminalCode, String secret) {
    }
}
