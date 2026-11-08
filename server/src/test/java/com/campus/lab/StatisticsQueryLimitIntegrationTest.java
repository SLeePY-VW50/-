package com.campus.lab;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.statistics.max-assignment-rows=1")
class StatisticsQueryLimitIntegrationTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    void dashboardCountsAssignmentsBeforeLoadingAndReturns413AboveConfiguredLimit() throws Exception {
        createTwoAssignments();
        String token = loginAndGetToken("A1001", "Lab@2026");

        mockMvc.perform(get("/api/statistics/dashboard")
                        .queryParam("date", "2026-10-23")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is(413));
    }

    @Test
    void heatmapUsesTheSameAssignmentLimit() throws Exception {
        long labId = createTwoAssignments();
        String token = loginAndGetToken("T1001", "Lab@2026");

        mockMvc.perform(get("/api/statistics/heatmap")
                        .queryParam("labId", Long.toString(labId))
                        .queryParam("start", "2026-10-23").queryParam("end", "2026-10-23")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is(413));
    }

    @Test
    void dailyBatchSkipsExceededChunkInsteadOfLoadingIt() {
        long labId = createTwoAssignments();
        var service = applicationContext.getBean(com.campus.lab.statistics.StatisticsService.class);

        org.assertj.core.api.Assertions.assertThatCode(() -> service.refreshDailyBatch(
                        java.util.List.of(new com.campus.lab.statistics.StatisticsViews.LabDateTarget(
                                labId, LocalDate.of(2026, 10, 23)))))
                .doesNotThrowAnyException();

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_stat_daily WHERE lab_id = ? AND stat_date = ?
                """, Integer.class, labId, LocalDate.of(2026, 10, 23))).isZero();
    }

    private long createTwoAssignments() {
        long labId = jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = 'LAB-A'", Long.class);
        java.util.List<Long> seatIds = jdbcTemplate.queryForList("""
                SELECT seat_id FROM t_lab_seat WHERE lab_id = ? ORDER BY seat_index LIMIT 2
                """, Long.class, labId);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        LocalDate date = LocalDate.of(2026, 10, 23);
        for (int index = 0; index < 2; index++) {
            jdbcTemplate.update("""
                    INSERT INTO t_reservation
                        (user_id, allocation_mode, reservation_mode, request_date,
                         request_window_start, request_window_end, requested_duration_minutes,
                         lab_id, start_time, end_time, attendee_count, seat_count, purpose, status)
                    VALUES (?, 'FIXED', 'SEAT', ?, '08:00:00', '09:00:00', 60,
                            ?, '2026-10-23 08:00:00', '2026-10-23 09:00:00', 1, 1, '上限测试', 'APPROVED')
                    """, userId, date, labId);
            long reservationId = jdbcTemplate.queryForObject(
                    "SELECT MAX(resv_id) FROM t_reservation WHERE user_id = ?", Long.class, userId);
            jdbcTemplate.update("""
                    INSERT INTO t_reservation_seat
                        (resv_id, seat_id, time_mask, assignment_method)
                    VALUES (?, ?, ?, 'FIXED')
                    """, reservationId, seatIds.get(index), 1L << 16);
        }
        return labId;
    }
}
