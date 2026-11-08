package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.lab.reservation.AllocationMode;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationService;
import com.campus.lab.reservation.dto.CreateReservationRequest;
import com.campus.lab.reservation.dto.ReservationPreferences;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;

abstract class AbstractScheduleIntegrationTest extends AbstractReservationNonTransactionalTest {

    @Autowired
    protected ReservationService reservationService;

    @AfterEach
    void cleanScheduleRowsAndLocks() {
        jdbcTemplate.update("DELETE FROM t_algorithm_experiment");
        jdbcTemplate.update("DELETE FROM t_schedule_result");
        jdbcTemplate.update("DELETE FROM t_schedule_task");
        Set<String> keys = scanKeys("schedule:*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    protected long createPending(long userId, long labId, LocalDate date) {
        return createPending(userId, labId, date, "09:00", "12:00", 60);
    }

    protected long createPending(
            long userId, long labId, LocalDate date,
            String windowStart, String windowEnd, int durationMinutes) {
        CreateReservationRequest request = new CreateReservationRequest(
                AllocationMode.SCHEDULABLE, labId, date, null, null,
                windowStart, windowEnd, durationMinutes, ReservationMode.SEAT, 1, 1,
                "排期集成测试待排期需求",
                new ReservationPreferences(null, null, null, List.of(labId)));
        return reservationService.create(userId, request).resvId();
    }

    protected long createFixed(
            long userId, long labId, LocalDate date, String start, String end) {
        CreateReservationRequest request = new CreateReservationRequest(
                AllocationMode.FIXED, labId, date, start, end, null, null, null,
                ReservationMode.SEAT, 1, 1, "排期集成测试固定占用", ReservationPreferences.empty());
        return reservationService.create(userId, request).resvId();
    }

    protected long primaryTaskId(String batchNo) {
        Long taskId = jdbcTemplate.queryForObject("""
                SELECT task_id FROM t_schedule_task
                WHERE schedule_batch_no = ? AND is_primary = TRUE
                """, Long.class, batchNo);
        assertThat(taskId).isNotNull();
        return taskId;
    }

    protected String onlyBatchNo() {
        List<String> batchNos = jdbcTemplate.queryForList(
                "SELECT DISTINCT schedule_batch_no FROM t_schedule_task", String.class);
        assertThat(batchNos).hasSize(1);
        return batchNos.getFirst();
    }

    protected String reservationStatus(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_reservation WHERE resv_id = ?", String.class, reservationId);
    }
}
