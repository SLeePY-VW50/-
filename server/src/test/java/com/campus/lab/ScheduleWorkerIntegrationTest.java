package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.campus.lab.schedule.AlgorithmType;
import com.campus.lab.schedule.ScheduleAlgorithmExecutor;
import com.campus.lab.schedule.ScheduleBatchLockManager;
import com.campus.lab.schedule.ScheduleService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class ScheduleWorkerIntegrationTest extends AbstractScheduleIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Autowired
    private ScheduleService scheduleService;

    @MockitoSpyBean
    private ScheduleAlgorithmExecutor algorithmExecutor;

    @MockitoSpyBean
    private ScheduleBatchLockManager batchLockManager;

    @AfterEach
    void resetAlgorithmExecutor() {
        Mockito.reset(algorithmExecutor, batchLockManager);
    }

    @Test
    void threeAlgorithmsShareSnapshotAndOnlyPrimaryPublishesProductionOccupancy() throws Exception {
        long labId = createLaboratory(4);
        long pendingUserId = userIdByAccount(createTestAccount("STUDENT"));
        long fixedUserId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(pendingUserId, labId, DATE);
        long fixedId = createFixed(fixedUserId, labId, DATE, "08:00", "09:00");

        scheduleService.runBatch(DATE);

        String batchNo = onlyBatchNo();
        List<Map<String, Object>> tasks = jdbcTemplate.queryForList("""
                SELECT task_id, algorithm_type, is_primary, dataset_id, demand_snapshot,
                       resource_snapshot, parameters, random_seed, repeat_index,
                       runtime_environment, status, progress, started_at, finished_at
                FROM t_schedule_task WHERE schedule_batch_no = ? ORDER BY algorithm_type
                """, batchNo);
        assertThat(tasks).hasSize(3);
        assertThat(tasks).extracting(task -> task.get("algorithm_type"))
                .containsExactlyInAnyOrder("FCFS", "GENETIC", "GREEDY");
        assertThat(tasks).allSatisfy(task -> {
            assertThat(task.get("dataset_id")).isEqualTo(tasks.getFirst().get("dataset_id"));
            assertThat(task.get("demand_snapshot")).isEqualTo(tasks.getFirst().get("demand_snapshot"));
            assertThat(task.get("resource_snapshot")).isEqualTo(tasks.getFirst().get("resource_snapshot"));
            assertThat(task.get("parameters")).isEqualTo(tasks.getFirst().get("parameters"));
            assertThat(task.get("random_seed")).isEqualTo(tasks.getFirst().get("random_seed"));
            assertThat(task.get("repeat_index")).isEqualTo(tasks.getFirst().get("repeat_index"));
            assertThat(task.get("runtime_environment")).isEqualTo(tasks.getFirst().get("runtime_environment"));
            assertThat(task.get("started_at")).isNotNull();
            assertThat(task.get("finished_at")).isNotNull();
            assertThat(((Number) task.get("progress")).intValue()).isEqualTo(100);
        });
        assertThat(tasks.stream().filter(task -> Boolean.TRUE.equals(task.get("is_primary"))))
                .singleElement().satisfies(task -> {
                    assertThat(task.get("algorithm_type")).isEqualTo("GREEDY");
                    assertThat(task.get("status")).isEqualTo("PUBLISHED");
                });
        assertThat(tasks.stream().filter(task -> !Boolean.TRUE.equals(task.get("is_primary"))))
                .allSatisfy(task -> assertThat(task.get("status")).isEqualTo("SUCCEEDED"));

        String demandSnapshot = jdbcTemplate.queryForObject("""
                SELECT demand_snapshot FROM t_schedule_task WHERE schedule_batch_no = ? LIMIT 1
                """, String.class, batchNo);
        assertThat(demandSnapshot)
                .doesNotContain("password", "openid", "phone", "purpose", "account", "name");
        assertThat(objectMapper.readTree(demandSnapshot).path("demands").get(0).path("resvId").asLong())
                .isEqualTo(pendingId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result r
                JOIN t_schedule_task t ON t.task_id = r.task_id
                WHERE t.schedule_batch_no = ? AND r.assigned_lab_id IS NOT NULL
                """, Integer.class, batchNo)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_algorithm_experiment e
                JOIN t_schedule_task t ON t.task_id = e.task_id
                WHERE t.schedule_batch_no = ?
                  AND (e.allocation_success_rate IS NULL OR e.average_seat_utilization IS NULL
                       OR e.seat_preference_mismatch_rate IS NULL OR e.time_fragmentation_rate IS NULL
                       OR e.seat_allocation_failure_rate IS NULL
                       OR e.total_duration_ms <> e.phase_one_duration_ms + e.phase_two_duration_ms)
                """, Integer.class, batchNo)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT JSON_EXTRACT(parameters, '$.populationSize')
                FROM t_schedule_task WHERE schedule_batch_no = ? LIMIT 1
                """, Integer.class, batchNo)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT JSON_EXTRACT(parameters, '$.generations')
                FROM t_schedule_task WHERE schedule_batch_no = ? LIMIT 1
                """, Integer.class, batchNo)).isEqualTo(5);

        assertThat(reservationStatus(pendingId)).isEqualTo("APPROVED");
        assertThat(reservationStatus(fixedId)).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat
                WHERE resv_id = ? AND assignment_method = 'AUTO_PUBLISH'
                """, Integer.class, pendingId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation
                WHERE lab_id = ? AND status = 'APPROVED'
                """, Integer.class, labId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result r
                JOIN t_schedule_task t ON t.task_id = r.task_id
                WHERE t.schedule_batch_no = ? AND t.is_primary = FALSE AND r.is_published = TRUE
                """, Integer.class, batchNo)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_schedule_task", Integer.class)).isEqualTo(3);
    }

    @Test
    void comparisonFailureDoesNotBlockPrimaryOrOtherExperiment() {
        long labId = createLaboratory(2);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(userId, labId, DATE);
        doThrow(new IllegalStateException("sensitive algorithm body must not be logged"))
                .when(algorithmExecutor).execute(eq(AlgorithmType.FCFS), any(), any());

        scheduleService.runBatch(DATE);

        String batchNo = onlyBatchNo();
        assertThat(algorithmStatus(batchNo, "FCFS")).isEqualTo("FAILED");
        assertThat(algorithmStatus(batchNo, "GREEDY")).isEqualTo("PUBLISHED");
        assertThat(algorithmStatus(batchNo, "GENETIC")).isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT error_message FROM t_schedule_task
                WHERE schedule_batch_no = ? AND algorithm_type = 'FCFS'
                """, String.class, batchNo))
                .contains("IllegalStateException")
                .doesNotContain("sensitive algorithm body");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_algorithm_experiment e
                JOIN t_schedule_task t ON t.task_id = e.task_id
                WHERE t.schedule_batch_no = ?
                """, Integer.class, batchNo)).isEqualTo(2);
        assertThat(reservationStatus(pendingId)).isEqualTo("APPROVED");
    }

    @Test
    void primaryAlgorithmFailureDoesNotFallbackOrPublish() {
        long labId = createLaboratory(2);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(userId, labId, DATE);
        doThrow(new IllegalStateException("sensitive primary failure"))
                .when(algorithmExecutor).execute(eq(AlgorithmType.GREEDY), any(), any());

        scheduleService.runBatch(DATE);

        String batchNo = onlyBatchNo();
        assertThat(algorithmStatus(batchNo, "GREEDY")).isEqualTo("FAILED");
        assertThat(algorithmStatus(batchNo, "FCFS")).isEqualTo("SUCCEEDED");
        assertThat(algorithmStatus(batchNo, "GENETIC")).isEqualTo("SUCCEEDED");
        assertThat(reservationStatus(pendingId)).isEqualTo("PENDING_SCHEDULE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat WHERE resv_id = ?
                """, Integer.class, pendingId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result r
                JOIN t_schedule_task t ON t.task_id = r.task_id
                WHERE t.schedule_batch_no = ? AND r.is_published = TRUE
                """, Integer.class, batchNo)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT error_message FROM t_schedule_task
                WHERE schedule_batch_no = ? AND algorithm_type = 'GREEDY'
                """, String.class, batchNo)).doesNotContain("sensitive primary failure");
    }

    @Test
    void lostBatchLeaseBeforePublishMarksFailureWithoutOccupancy() {
        long labId = createLaboratory(2);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(userId, labId, DATE);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            redisTemplate.opsForValue().set(
                    "schedule:batch:" + DATE, "replacement-owner", java.time.Duration.ofSeconds(30));
            return result;
        }).when(algorithmExecutor).execute(eq(AlgorithmType.GENETIC), any(), any());

        assertThatThrownBy(() -> scheduleService.runBatch(DATE))
                .hasMessageContaining("BATCH_LOCK_LOST");

        String batchNo = onlyBatchNo();
        long primaryId = primaryTaskId(batchNo);
        assertThat(algorithmStatus(batchNo, "GREEDY")).isEqualTo("PUBLISH_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM t_schedule_task WHERE task_id = ?",
                String.class, primaryId)).isEqualTo("BATCH_LOCK_LOST");
        assertThat(reservationStatus(pendingId)).isEqualTo("PENDING_SCHEDULE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation_seat WHERE resv_id = ?",
                Integer.class, pendingId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result
                WHERE task_id = ? AND is_published = TRUE
                """, Integer.class, primaryId)).isZero();
    }

    @Test
    void emptyDemandDateDoesNotCreateTasks() {
        scheduleService.runBatch(DATE.plusDays(1));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_schedule_task", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_schedule_result", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_algorithm_experiment", Integer.class)).isZero();
    }

    @Test
    void concurrentSameDateRunsCreateOnlyOneThreeTaskBatch() throws Exception {
        long labId = createLaboratory(2);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        createPending(userId, labId, DATE);
        CountDownLatch bothAtLock = new CountDownLatch(2);
        CountDownLatch releaseLockAttempts = new CountDownLatch(1);
        CountDownLatch algorithmEntered = new CountDownLatch(1);
        CountDownLatch loserReturned = new CountDownLatch(1);
        CountDownLatch allowAlgorithm = new CountDownLatch(1);
        AtomicInteger emptyResults = new AtomicInteger();
        doAnswer(invocation -> {
            bothAtLock.countDown();
            assertThat(releaseLockAttempts.await(30, TimeUnit.SECONDS)).isTrue();
            Object result = invocation.callRealMethod();
            if (result instanceof java.util.Optional<?> optional && optional.isEmpty()) {
                emptyResults.incrementAndGet();
                loserReturned.countDown();
            }
            return result;
        }).when(batchLockManager).tryAcquire(eq(DATE));
        doAnswer(invocation -> {
            algorithmEntered.countDown();
            assertThat(allowAlgorithm.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(algorithmExecutor).execute(eq(AlgorithmType.FCFS), any(), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> scheduleService.runBatch(DATE));
            Future<?> second = executor.submit(() -> scheduleService.runBatch(DATE));
            assertThat(bothAtLock.await(30, TimeUnit.SECONDS)).isTrue();
            releaseLockAttempts.countDown();
            assertThat(algorithmEntered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(loserReturned.await(30, TimeUnit.SECONDS)).isTrue();
            allowAlgorithm.countDown();
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        } finally {
            releaseLockAttempts.countDown();
            allowAlgorithm.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(emptyResults).hasValue(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_schedule_task", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT schedule_batch_no) FROM t_schedule_task", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation_seat WHERE assignment_method = 'AUTO_PUBLISH'",
                Integer.class)).isOne();
    }

    private String algorithmStatus(String batchNo, String algorithmType) {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM t_schedule_task
                WHERE schedule_batch_no = ? AND algorithm_type = ?
                """, String.class, batchNo, algorithmType);
    }
}
