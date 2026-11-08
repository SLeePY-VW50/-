package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.campus.lab.reservation.ReservationLifecycleService;
import com.campus.lab.schedule.AlgorithmType;
import com.campus.lab.schedule.ScheduleAlgorithmExecutor;
import com.campus.lab.schedule.ScheduleBatchLock;
import com.campus.lab.schedule.ScheduleBatchLockManager;
import com.campus.lab.schedule.ScheduleRepository;
import com.campus.lab.schedule.ScheduleService;
import com.campus.lab.schedule.ScheduleTaskTransactionService;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class ScheduleLifecycleRecoveryIntegrationTest extends AbstractScheduleIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ScheduleBatchLockManager batchLockManager;

    @Autowired
    private ReservationLifecycleService lifecycleService;

    @MockitoSpyBean
    private ScheduleTaskTransactionService transactionService;

    @MockitoSpyBean
    private ScheduleAlgorithmExecutor algorithmExecutor;

    @AfterEach
    void resetSpies() {
        Mockito.reset(transactionService, algorithmExecutor);
    }

    @Test
    void staleWorkerCannotLeavePendingBatchWhenSuccessorFinishesBeforeItsCreate() throws Exception {
        long labId = createLaboratory(2);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(userId, labId, DATE);
        AtomicInteger createCalls = new AtomicInteger();
        AtomicReference<String> staleBatchNo = new AtomicReference<>();
        AtomicReference<String> successorBatchNo = new AtomicReference<>();
        CountDownLatch staleAtCreate = new CountDownLatch(1);
        CountDownLatch allowStaleCreate = new CountDownLatch(1);
        doAnswer(invocation -> {
            ScheduleRepository.ScheduleSnapshot snapshot = invocation.getArgument(0);
            if (createCalls.incrementAndGet() == 1) {
                staleBatchNo.set(snapshot.batchNo());
                redisTemplate.delete("schedule:batch:" + DATE);
                staleAtCreate.countDown();
                assertThat(allowStaleCreate.await(30, TimeUnit.SECONDS)).isTrue();
            } else {
                successorBatchNo.set(snapshot.batchNo());
            }
            return invocation.callRealMethod();
        }).when(transactionService).createTasks(any(), any(), anyString(), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> stale = executor.submit(() -> {
                try {
                    scheduleService.runBatch(DATE);
                    return null;
                } catch (Throwable exception) {
                    return exception;
                }
            });
            assertThat(staleAtCreate.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> successor = executor.submit(() -> {
                try {
                    scheduleService.runBatch(DATE);
                    return null;
                } catch (Throwable exception) {
                    return exception;
                }
            });
            assertThat(successor.get(60, TimeUnit.SECONDS)).isNull();
            allowStaleCreate.countDown();
            assertThat(stale.get(60, TimeUnit.SECONDS))
                    .hasMessageContaining("BATCH_LOCK_LOST");
        } finally {
            allowStaleCreate.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(staleBatchNo.get()).isNotBlank();
        assertThat(successorBatchNo.get()).isNotBlank().isNotEqualTo(staleBatchNo.get());
        assertThat(reservationStatus(pendingId)).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_task
                WHERE schedule_batch_no = ? AND status IN ('PUBLISHED', 'SUCCEEDED')
                """, Integer.class, successorBatchNo.get())).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
                SELECT status FROM t_schedule_task WHERE schedule_batch_no = ? ORDER BY task_id
                """, String.class, staleBatchNo.get()))
                .containsExactly("FAILED", "FAILED", "FAILED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_task WHERE status IN ('PENDING', 'RUNNING')
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat WHERE assignment_method = 'AUTO_PUBLISH'
                """, Integer.class)).isOne();
    }

    @Test
    void legacyTaskDateIsScannedAndCleanedOnlyAfterDateLockIsAcquired() {
        long labId = createLaboratory(2);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(userId, labId, DATE);
        doThrow(new IllegalStateException("simulated primary failure"))
                .when(algorithmExecutor).execute(eq(AlgorithmType.GREEDY), any(), any());
        scheduleService.runBatch(DATE);
        String batchNo = onlyBatchNo();
        long primaryTaskId = primaryTaskId(batchNo);
        Long runningTaskId = jdbcTemplate.queryForObject("""
                SELECT task_id FROM t_schedule_task
                WHERE schedule_batch_no = ? AND algorithm_type = 'FCFS'
                """, Long.class, batchNo);
        jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'PENDING', progress = 0, finished_at = NULL, error_message = NULL
                WHERE task_id = ?
                """, primaryTaskId);
        jdbcTemplate.update("""
                UPDATE t_schedule_task
                SET status = 'RUNNING', progress = 40, finished_at = NULL, error_message = NULL
                WHERE task_id = ?
                """, runningTaskId);
        lifecycleService.cancel(pendingId, userId, "取消全部待排期预约");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation WHERE status = 'PENDING_SCHEDULE'
                """, Integer.class)).isZero();

        try (ScheduleBatchLock ignored = batchLockManager.tryAcquire(DATE).orElseThrow()) {
            scheduleService.runDueBatches();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM t_schedule_task WHERE task_id = ?",
                    String.class, primaryTaskId)).isEqualTo("PENDING");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM t_schedule_task WHERE task_id = ?",
                    String.class, runningTaskId)).isEqualTo("RUNNING");
        }
        scheduleService.runDueBatches();

        List<String> batchNos = jdbcTemplate.queryForList(
                "SELECT DISTINCT schedule_batch_no FROM t_schedule_task", String.class);
        assertThat(batchNos).containsExactly(batchNo);
        assertThat(jdbcTemplate.queryForList("""
                SELECT status FROM t_schedule_task WHERE task_id IN (?, ?) ORDER BY task_id
                """, String.class, runningTaskId, primaryTaskId))
                .containsExactly("FAILED", "FAILED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT error_message FROM t_schedule_task WHERE task_id IN (?, ?) ORDER BY task_id
                """, String.class, runningTaskId, primaryTaskId))
                .allSatisfy(reason -> assertThat(reason).contains("INTERRUPTED"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_task WHERE status IN ('PENDING', 'RUNNING')
                """, Integer.class)).isZero();
    }
}
