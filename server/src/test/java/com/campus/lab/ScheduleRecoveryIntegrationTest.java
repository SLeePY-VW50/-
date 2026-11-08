package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.campus.lab.reservation.ReservationLifecycleService;
import com.campus.lab.schedule.AlgorithmType;
import com.campus.lab.schedule.PrimaryPlanPublisher;
import com.campus.lab.schedule.ScheduleAlgorithmExecutor;
import com.campus.lab.schedule.ScheduleQueryService;
import com.campus.lab.schedule.ScheduleService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class ScheduleRecoveryIntegrationTest extends AbstractScheduleIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ScheduleQueryService queryService;

    @Autowired
    private ReservationLifecycleService lifecycleService;

    @MockitoSpyBean
    private PrimaryPlanPublisher primaryPlanPublisher;

    @MockitoSpyBean
    private ScheduleAlgorithmExecutor algorithmExecutor;

    @AfterEach
    void resetPublisher() {
        Mockito.reset(primaryPlanPublisher, algorithmExecutor);
    }

    @Test
    void newPendingAfterSuccessfulBatchCreatesIndependentRound() {
        long labId = createLaboratory(2);
        long firstUserId = userIdByAccount(createTestAccount("STUDENT"));
        long firstPendingId = createPending(firstUserId, labId, DATE);
        scheduleService.runBatch(DATE);
        String firstBatchNo = onlyBatchNo();
        long firstPrimaryTaskId = primaryTaskId(firstBatchNo);
        assertThat(reservationStatus(firstPendingId)).isEqualTo("APPROVED");
        long firstSeatId = jdbcTemplate.queryForObject(
                "SELECT seat_id FROM t_reservation_seat WHERE resv_id = ?", Long.class, firstPendingId);
        Map<String, Object> firstBitmap = jdbcTemplate.queryForMap(
                "SELECT mask, version FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?",
                firstSeatId, DATE);
        LocalDateTime firstPublishedAt = jdbcTemplate.queryForObject("""
                SELECT published_at FROM t_schedule_result WHERE task_id = ? AND resv_id = ?
                """, LocalDateTime.class, firstPrimaryTaskId, firstPendingId);

        long secondUserId = userIdByAccount(createTestAccount("STUDENT"));
        long secondPendingId = createPending(secondUserId, labId, DATE);
        scheduleService.runBatch(DATE);

        List<String> batchNos = jdbcTemplate.queryForList(
                "SELECT DISTINCT schedule_batch_no FROM t_schedule_task", String.class);
        assertThat(batchNos).hasSize(2).contains(firstBatchNo);
        String secondBatchNo = batchNos.stream().filter(batchNo -> !batchNo.equals(firstBatchNo))
                .findFirst().orElseThrow();
        assertThat(secondBatchNo).isNotEqualTo(firstBatchNo);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_schedule_task", Integer.class)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT dataset_id) FROM t_schedule_task", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_schedule_task WHERE schedule_batch_no = ?",
                Integer.class, firstBatchNo)).isEqualTo(3);
        assertThat(reservationStatus(secondPendingId)).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM t_schedule_task WHERE task_id = ?
                """, String.class, primaryTaskId(secondBatchNo))).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat
                WHERE resv_id = ? AND assignment_method = 'AUTO_PUBLISH'
                """, Integer.class, firstPendingId)).isOne();
        assertThat(countPublishLogs(firstPendingId)).isOne();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT mask, version FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?",
                firstSeatId, DATE)).isEqualTo(firstBitmap);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT published_at FROM t_schedule_result WHERE task_id = ? AND resv_id = ?
                """, LocalDateTime.class, firstPrimaryTaskId, firstPendingId)).isEqualTo(firstPublishedAt);
        assertThat(queryService.listBatches(0, 10).total()).isEqualTo(2);
        assertThat(queryService.listBatches(0, 10).items())
                .allSatisfy(batch -> assertThat(batch.requestDate()).isEqualTo(DATE));
    }

    @Test
    void publishFailedBatchCreatesNewRoundAfterConflictIsReleased() throws Exception {
        long labId = createLaboratory(1);
        long pendingUserId = userIdByAccount(createTestAccount("STUDENT"));
        long fixedUserId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(pendingUserId, labId, DATE);
        CountDownLatch publishEntered = new CountDownLatch(1);
        CountDownLatch allowPublish = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishEntered.countDown();
            assertThat(allowPublish.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(primaryPlanPublisher).publish(anyLong());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        long fixedId;
        try {
            Future<?> firstRun = executor.submit(() -> scheduleService.runBatch(DATE));
            assertThat(publishEntered.await(30, TimeUnit.SECONDS)).isTrue();
            LocalDateTime assignedStart = jdbcTemplate.queryForObject("""
                    SELECT r.assigned_start_time FROM t_schedule_result r
                    JOIN t_schedule_task t ON t.task_id = r.task_id
                    WHERE r.resv_id = ? AND r.assigned_start_time IS NOT NULL AND t.is_primary = TRUE
                    """, LocalDateTime.class, pendingId);
            LocalDateTime assignedEnd = jdbcTemplate.queryForObject("""
                    SELECT r.assigned_end_time FROM t_schedule_result r
                    JOIN t_schedule_task t ON t.task_id = r.task_id
                    WHERE r.resv_id = ? AND r.assigned_end_time IS NOT NULL AND t.is_primary = TRUE
                    """, LocalDateTime.class, pendingId);
            fixedId = createFixed(fixedUserId, labId, DATE,
                    assignedStart.toLocalTime().toString(), assignedEnd.toLocalTime().toString());
            allowPublish.countDown();
            firstRun.get(60, TimeUnit.SECONDS);
        } finally {
            allowPublish.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }
        String firstBatchNo = onlyBatchNo();
        long firstPrimaryTaskId = primaryTaskId(firstBatchNo);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_schedule_task WHERE task_id = ?",
                String.class, firstPrimaryTaskId)).isEqualTo("PUBLISH_FAILED");
        assertThat(reservationStatus(pendingId)).isEqualTo("PENDING_SCHEDULE");

        Mockito.reset(primaryPlanPublisher);
        lifecycleService.cancel(fixedId, fixedUserId, "解除发布冲突");
        scheduleService.runBatch(DATE);

        List<String> batchNos = jdbcTemplate.queryForList(
                "SELECT DISTINCT schedule_batch_no FROM t_schedule_task", String.class);
        assertThat(batchNos).hasSize(2).contains(firstBatchNo);
        String secondBatchNo = batchNos.stream().filter(batchNo -> !batchNo.equals(firstBatchNo))
                .findFirst().orElseThrow();
        long secondPrimaryTaskId = primaryTaskId(secondBatchNo);
        assertThat(reservationStatus(pendingId)).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_schedule_task WHERE task_id = ?",
                String.class, firstPrimaryTaskId)).isEqualTo("PUBLISH_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_schedule_task WHERE task_id = ?",
                String.class, secondPrimaryTaskId)).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result
                WHERE task_id = ? AND is_published = TRUE
                """, Integer.class, firstPrimaryTaskId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result
                WHERE task_id = ? AND is_published = TRUE
                """, Integer.class, secondPrimaryTaskId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat
                WHERE resv_id = ? AND assignment_method = 'AUTO_PUBLISH'
                """, Integer.class, pendingId)).isOne();
        assertThat(countPublishLogs(pendingId)).isOne();
        long seatId = jdbcTemplate.queryForObject(
                "SELECT seat_id FROM t_reservation_seat WHERE resv_id = ? AND assignment_method = 'AUTO_PUBLISH'",
                Long.class, pendingId);
        LocalDateTime start = jdbcTemplate.queryForObject(
                "SELECT start_time FROM t_reservation WHERE resv_id = ?", LocalDateTime.class, pendingId);
        LocalDateTime end = jdbcTemplate.queryForObject(
                "SELECT end_time FROM t_reservation WHERE resv_id = ?", LocalDateTime.class, pendingId);
        long expectedMask = com.campus.lab.reservation.TimeSlotMask.between(
                start.toLocalTime(), end.toLocalTime()).value();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT mask FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?",
                Long.class, seatId, DATE)).isEqualTo(expectedMask);
    }

    @Test
    void failedBatchRetriesOriginalAndNewDemandsWithFreshSnapshot() throws Exception {
        long labId = createLaboratory(1);
        long pendingUserId = userIdByAccount(createTestAccount("STUDENT"));
        long fixedUserId = userIdByAccount(createTestAccount("STUDENT"));
        long firstPendingId = createPending(pendingUserId, labId, DATE);
        long fixedId = createFixed(fixedUserId, labId, DATE, "09:00", "12:00");
        scheduleService.runBatch(DATE);
        String firstBatchNo = onlyBatchNo();
        long firstPrimaryTaskId = primaryTaskId(firstBatchNo);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_schedule_task WHERE task_id = ?",
                String.class, firstPrimaryTaskId)).isEqualTo("FAILED");
        String oldDemandSnapshot = jdbcTemplate.queryForObject(
                "SELECT demand_snapshot FROM t_schedule_task WHERE task_id = ?",
                String.class, firstPrimaryTaskId);
        String oldResourceSnapshot = jdbcTemplate.queryForObject(
                "SELECT resource_snapshot FROM t_schedule_task WHERE task_id = ?",
                String.class, firstPrimaryTaskId);

        lifecycleService.cancel(fixedId, fixedUserId, "解除首轮资源冲突");
        long secondUserId = userIdByAccount(createTestAccount("STUDENT"));
        long secondPendingId = createPending(secondUserId, labId, DATE);
        scheduleService.runBatch(DATE);

        List<String> batchNos = jdbcTemplate.queryForList(
                "SELECT DISTINCT schedule_batch_no FROM t_schedule_task", String.class);
        assertThat(batchNos).hasSize(2).contains(firstBatchNo);
        String secondBatchNo = batchNos.stream().filter(batchNo -> !batchNo.equals(firstBatchNo))
                .findFirst().orElseThrow();
        long secondPrimaryTaskId = primaryTaskId(secondBatchNo);
        assertThat(reservationStatus(firstPendingId)).isEqualTo("APPROVED");
        assertThat(reservationStatus(secondPendingId)).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_schedule_task WHERE task_id = ?",
                String.class, firstPrimaryTaskId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_schedule_task WHERE task_id = ?",
                String.class, secondPrimaryTaskId)).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT demand_snapshot FROM t_schedule_task WHERE task_id = ?",
                String.class, firstPrimaryTaskId)).isEqualTo(oldDemandSnapshot);
        assertThat(oldDemandSnapshot).doesNotContain(Long.toString(secondPendingId));
        String newDemandSnapshot = jdbcTemplate.queryForObject(
                "SELECT demand_snapshot FROM t_schedule_task WHERE task_id = ?",
                String.class, secondPrimaryTaskId);
        List<Long> newDemandIds = new java.util.ArrayList<>();
        objectMapper.readTree(newDemandSnapshot).path("demands")
                .forEach(demand -> newDemandIds.add(demand.path("resvId").asLong()));
        assertThat(newDemandIds).containsExactlyInAnyOrder(firstPendingId, secondPendingId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT resource_snapshot FROM t_schedule_task WHERE task_id = ?",
                String.class, secondPrimaryTaskId)).isNotEqualTo(oldResourceSnapshot);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result
                WHERE task_id = ? AND is_published = TRUE
                """, Integer.class, firstPrimaryTaskId)).isZero();
    }

    @Test
    void legacyRunningAndPendingTasksFailBeforeIndependentRecoveryBatch() throws Exception {
        long labId = createLaboratory(2);
        long firstUserId = userIdByAccount(createTestAccount("STUDENT"));
        long firstPendingId = createPending(firstUserId, labId, DATE);
        doThrow(new IllegalStateException("simulated interrupted primary"))
                .when(algorithmExecutor).execute(eq(AlgorithmType.GREEDY), any(), any());
        scheduleService.runBatch(DATE);
        String firstBatchNo = onlyBatchNo();
        long firstPrimaryTaskId = primaryTaskId(firstBatchNo);
        Long runningTaskId = jdbcTemplate.queryForObject("""
                SELECT task_id FROM t_schedule_task
                WHERE schedule_batch_no = ? AND algorithm_type = 'FCFS'
                """, Long.class, firstBatchNo);
        assertThat(runningTaskId).isNotNull();
        String oldPrimarySnapshot = jdbcTemplate.queryForObject(
                "SELECT demand_snapshot FROM t_schedule_task WHERE task_id = ?",
                String.class, firstPrimaryTaskId);
        String oldRunningSnapshot = jdbcTemplate.queryForObject(
                "SELECT demand_snapshot FROM t_schedule_task WHERE task_id = ?",
                String.class, runningTaskId);
        jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'RUNNING', progress = 40, finished_at = NULL, error_message = NULL
                WHERE task_id = ?
                """, runningTaskId);
        jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'PENDING', progress = 0, finished_at = NULL, error_message = NULL
                WHERE task_id = ?
                """, firstPrimaryTaskId);

        Mockito.reset(algorithmExecutor);
        long secondUserId = userIdByAccount(createTestAccount("STUDENT"));
        long secondPendingId = createPending(secondUserId, labId, DATE);
        scheduleService.runBatch(DATE);

        List<String> batchNos = jdbcTemplate.queryForList(
                "SELECT DISTINCT schedule_batch_no FROM t_schedule_task", String.class);
        assertThat(batchNos).hasSize(2).contains(firstBatchNo);
        String secondBatchNo = batchNos.stream().filter(batchNo -> !batchNo.equals(firstBatchNo))
                .findFirst().orElseThrow();
        long secondPrimaryTaskId = primaryTaskId(secondBatchNo);
        assertThat(secondPrimaryTaskId).isNotEqualTo(firstPrimaryTaskId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_schedule_task", Integer.class)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForList("""
                SELECT status FROM t_schedule_task WHERE task_id IN (?, ?) ORDER BY task_id
                """, String.class, runningTaskId, firstPrimaryTaskId))
                .containsExactly("FAILED", "FAILED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT error_message FROM t_schedule_task WHERE task_id IN (?, ?) ORDER BY task_id
                """, String.class, runningTaskId, firstPrimaryTaskId))
                .allSatisfy(reason -> assertThat(reason).contains("INTERRUPTED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT demand_snapshot FROM t_schedule_task WHERE task_id = ?",
                String.class, firstPrimaryTaskId)).isEqualTo(oldPrimarySnapshot);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT demand_snapshot FROM t_schedule_task WHERE task_id = ?",
                String.class, runningTaskId)).isEqualTo(oldRunningSnapshot);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result WHERE task_id = ?
                """, Integer.class, firstPrimaryTaskId)).isZero();
        String newDemandSnapshot = jdbcTemplate.queryForObject(
                "SELECT demand_snapshot FROM t_schedule_task WHERE task_id = ?",
                String.class, secondPrimaryTaskId);
        List<Long> newDemandIds = new java.util.ArrayList<>();
        objectMapper.readTree(newDemandSnapshot).path("demands")
                .forEach(demand -> newDemandIds.add(demand.path("resvId").asLong()));
        assertThat(newDemandIds).containsExactlyInAnyOrder(firstPendingId, secondPendingId);
        assertThat(reservationStatus(firstPendingId)).isEqualTo("APPROVED");
        assertThat(reservationStatus(secondPendingId)).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM t_schedule_task WHERE task_id = ?",
                String.class, secondPrimaryTaskId)).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM t_schedule_task
                WHERE schedule_batch_no = ? AND algorithm_type = 'GENETIC'
                """, String.class, firstBatchNo)).isEqualTo("SUCCEEDED");
    }

    private int countPublishLogs(long reservationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log
                WHERE resv_id = ? AND from_status = 'PENDING_SCHEDULE' AND to_status = 'APPROVED'
                """, Integer.class, reservationId);
    }
}
