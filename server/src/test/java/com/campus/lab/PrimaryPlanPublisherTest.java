package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.campus.lab.reservation.AllocationMode;
import com.campus.lab.reservation.LabLockKey;
import com.campus.lab.reservation.LockManager;
import com.campus.lab.reservation.ReservationLifecycleService;
import com.campus.lab.reservation.ReservationLock;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationRepository;
import com.campus.lab.reservation.ReservationStatus;
import com.campus.lab.reservation.TimeSlotMask;
import com.campus.lab.reservation.dto.CreateReservationRequest;
import com.campus.lab.reservation.dto.ReservationPreferences;
import com.campus.lab.schedule.PrimaryPlanPublisher;
import com.campus.lab.schedule.ScheduleService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PrimaryPlanPublisherTest extends AbstractScheduleIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ReservationLifecycleService lifecycleService;

    @MockitoSpyBean
    private PrimaryPlanPublisher primaryPlanPublisher;

    @MockitoSpyBean
    private ReservationRepository reservationRepository;

    @MockitoSpyBean
    private LockManager lockManager;

    @AfterEach
    void resetSpies() {
        Mockito.reset(primaryPlanPublisher, reservationRepository, lockManager);
    }

    @Test
    void successfulAndRepeatedPublicationIsIdempotent() {
        long labId = createLaboratory(2);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(userId, labId, DATE);
        scheduleService.runBatch(DATE);
        long taskId = primaryTaskId(onlyBatchNo());
        int assignmentCount = countAssignments(pendingId);
        int logCount = countPublishLogs(pendingId);
        int bitmapVersion = bitmapVersionAt(labId);

        primaryPlanPublisher.publish(taskId);
        primaryPlanPublisher.publish(taskId);

        assertThat(reservationStatus(pendingId)).isEqualTo("APPROVED");
        assertThat(taskStatus(taskId)).isEqualTo("PUBLISHED");
        assertThat(countAssignments(pendingId)).isEqualTo(assignmentCount);
        assertThat(countPublishLogs(pendingId)).isEqualTo(logCount);
        assertThat(bitmapVersionAt(labId)).isEqualTo(bitmapVersion);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result
                WHERE task_id = ? AND is_published = TRUE AND published_at IS NOT NULL
                """, Integer.class, taskId)).isOne();
    }

    @Test
    void comparisonTaskIsRejectedAndNeverChangesProductionRows() {
        long labId = createLaboratory(2);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(userId, labId, DATE);
        scheduleService.runBatch(DATE);
        String batchNo = onlyBatchNo();
        Long comparisonTaskId = jdbcTemplate.queryForObject("""
                SELECT task_id FROM t_schedule_task
                WHERE schedule_batch_no = ? AND algorithm_type = 'FCFS'
                """, Long.class, batchNo);
        assertThat(comparisonTaskId).isNotNull();
        int assignmentCount = countAssignments(pendingId);
        int bitmapVersion = bitmapVersionAt(labId);

        assertThatThrownBy(() -> primaryPlanPublisher.publish(comparisonTaskId))
                .hasMessageContaining("PRIMARY_TASK_REQUIRED");

        assertThat(taskStatus(comparisonTaskId)).isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result
                WHERE task_id = ? AND is_published = TRUE
                """, Integer.class, comparisonTaskId)).isZero();
        assertThat(countAssignments(pendingId)).isEqualTo(assignmentCount);
        assertThat(bitmapVersionAt(labId)).isEqualTo(bitmapVersion);
    }

    @Test
    void unresolvedPrimaryKeepsDemandPendingWithoutFallback() {
        long labId = createLaboratory(1);
        long pendingUserId = userIdByAccount(createTestAccount("STUDENT"));
        long fixedUserId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(pendingUserId, labId, DATE);
        createFixed(fixedUserId, labId, DATE, "09:00", "12:00");

        scheduleService.runBatch(DATE);

        String batchNo = onlyBatchNo();
        long primaryId = primaryTaskId(batchNo);
        assertThat(taskStatus(primaryId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM t_schedule_task WHERE task_id = ?",
                String.class, primaryId)).contains("UNRESOLVED");
        assertThat(reservationStatus(pendingId)).isEqualTo("PENDING_SCHEDULE");
        assertThat(countAssignments(pendingId)).isZero();
        assertThatThrownBy(() -> primaryPlanPublisher.publish(primaryId))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result
                WHERE task_id = ? AND is_published = TRUE
                """, Integer.class, primaryId)).isZero();
    }

    @Test
    void unresolvedResultAtPublishEntryMarksPrimaryPublishFailed() throws Exception {
        long labId = createLaboratory(2);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(userId, labId, DATE);
        CountDownLatch publishEntered = new CountDownLatch(1);
        CountDownLatch allowPublish = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishEntered.countDown();
            assertThat(allowPublish.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(primaryPlanPublisher).publish(anyLong());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        long primaryId = 0;
        try {
            Future<?> run = executor.submit(() -> scheduleService.runBatch(DATE));
            assertThat(publishEntered.await(30, TimeUnit.SECONDS)).isTrue();
            primaryId = primaryTaskId(onlyBatchNo());
            assertThat(taskStatus(primaryId)).isEqualTo("SUCCEEDED");
            jdbcTemplate.update("""
                    UPDATE t_schedule_result
                    SET assigned_lab_id = NULL, assigned_start_time = NULL, assigned_end_time = NULL
                    WHERE task_id = ? AND resv_id = ?
                    """, primaryId, pendingId);
            allowPublish.countDown();
            run.get(60, TimeUnit.SECONDS);
        } finally {
            allowPublish.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(taskStatus(primaryId)).isEqualTo("PUBLISH_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM t_schedule_task WHERE task_id = ?",
                String.class, primaryId)).isEqualTo("UNRESOLVED_PRIMARY_PLAN");
        assertThat(reservationStatus(pendingId)).isEqualTo("PENDING_SCHEDULE");
        assertThat(countAssignments(pendingId)).isZero();
        assertThat(countPublishLogs(pendingId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result
                WHERE task_id = ? AND is_published = TRUE
                """, Integer.class, primaryId)).isZero();
    }

    @Test
    void newFixedOccupancyAfterSnapshotRollsBackEveryDemand() throws Exception {
        long labId = createLaboratory(2);
        long firstUserId = userIdByAccount(createTestAccount("STUDENT"));
        long secondUserId = userIdByAccount(createTestAccount("STUDENT"));
        long fixedUserId = userIdByAccount(createTestAccount("STUDENT"));
        long firstPendingId = createPending(firstUserId, labId, DATE);
        long secondPendingId = createPending(secondUserId, labId, DATE);
        CountDownLatch publishEntered = new CountDownLatch(1);
        CountDownLatch allowPublish = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishEntered.countDown();
            assertThat(allowPublish.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(primaryPlanPublisher).publish(anyLong());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> run = executor.submit(() -> scheduleService.runBatch(DATE));
            assertThat(publishEntered.await(30, TimeUnit.SECONDS)).isTrue();
            LocalDateTime assignedStart = jdbcTemplate.queryForObject("""
                    SELECT r.assigned_start_time FROM t_schedule_result r
                    JOIN t_schedule_task t ON t.task_id = r.task_id
                    WHERE r.resv_id = ? AND r.assigned_start_time IS NOT NULL AND t.is_primary = TRUE
                    """, LocalDateTime.class, firstPendingId);
            LocalDateTime assignedEnd = jdbcTemplate.queryForObject("""
                    SELECT r.assigned_end_time FROM t_schedule_result r
                    JOIN t_schedule_task t ON t.task_id = r.task_id
                    WHERE r.resv_id = ? AND r.assigned_end_time IS NOT NULL AND t.is_primary = TRUE
                    """, LocalDateTime.class, firstPendingId);
            LocalTime start = assignedStart.toLocalTime();
            LocalTime end = assignedEnd.toLocalTime();
            createFixed(fixedUserId, labId, DATE, start.toString(), end.toString());
            allowPublish.countDown();
            run.get(60, TimeUnit.SECONDS);
        } finally {
            allowPublish.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        long primaryId = primaryTaskId(onlyBatchNo());
        assertThat(taskStatus(primaryId)).isEqualTo("PUBLISH_FAILED");
        assertThat(reservationStatus(firstPendingId)).isEqualTo("PENDING_SCHEDULE");
        assertThat(reservationStatus(secondPendingId)).isEqualTo("PENDING_SCHEDULE");
        assertThat(countAssignments(firstPendingId)).isZero();
        assertThat(countAssignments(secondPendingId)).isZero();
        assertThat(countPublishLogs(firstPendingId)).isZero();
        assertThat(countPublishLogs(secondPendingId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result
                WHERE task_id = ? AND is_published = TRUE
                """, Integer.class, primaryId)).isZero();
        assertThat(redisTemplate.hasKey("resv:lab:" + labId + ":" + DATE)).isFalse();
        assertThat(redisTemplate.hasKey("schedule:batch:" + DATE)).isFalse();
    }

    @Test
    void failureAfterWritesRollsBackEverythingAndReleasesLocksAfterFailureCommit() {
        long labId = createLaboratory(2);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(userId, labId, DATE);
        doThrow(new IllegalStateException("injected schedule log failure"))
                .when(reservationRepository).insertSystemLog(
                        anyLong(), eq(ReservationStatus.PENDING_SCHEDULE),
                        eq(ReservationStatus.APPROVED), anyString());
        AtomicBoolean releaseObserved = new AtomicBoolean();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT status FROM t_schedule_task WHERE is_primary = TRUE
                    """, String.class)).isEqualTo("PUBLISH_FAILED");
            releaseObserved.set(true);
            return invocation.callRealMethod();
        }).when(lockManager).release(any(ReservationLock.class));

        scheduleService.runBatch(DATE);
        Mockito.reset(reservationRepository);

        long primaryId = primaryTaskId(onlyBatchNo());
        assertThat(releaseObserved).isTrue();
        assertThat(taskStatus(primaryId)).isEqualTo("PUBLISH_FAILED");
        assertThat(reservationStatus(pendingId)).isEqualTo("PENDING_SCHEDULE");
        assertThat(countAssignments(pendingId)).isZero();
        assertThat(countPublishLogs(pendingId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result
                WHERE task_id = ? AND (is_published = TRUE OR published_at IS NOT NULL)
                """, Integer.class, primaryId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_seat_bitmap b
                JOIN t_lab_seat s ON s.seat_id = b.seat_id
                WHERE s.lab_id = ? AND b.bitmap_date = ? AND b.mask <> 0
                """, Integer.class, labId, DATE)).isZero();
        assertThat(redisTemplate.hasKey("resv:lab:" + labId + ":" + DATE)).isFalse();
    }

    @Test
    void publicationAcquiresEveryLabDateLockInSortedOrder() {
        long firstLabId = createLaboratory(2);
        long secondLabId = createLaboratory(2);
        long firstUserId = userIdByAccount(createTestAccount("STUDENT"));
        long secondUserId = userIdByAccount(createTestAccount("STUDENT"));
        createPending(firstUserId, secondLabId, DATE);
        createPending(secondUserId, firstLabId, DATE);
        List<LabLockKey> acquired = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            List<LabLockKey> keys = invocation.getArgument(0);
            acquired.addAll(keys);
            return invocation.callRealMethod();
        }).when(lockManager).acquireAll(anyList());

        scheduleService.runBatch(DATE);

        assertThat(acquired).containsExactly(
                new LabLockKey(firstLabId, DATE), new LabLockKey(secondLabId, DATE));
        assertThat(redisTemplate.hasKey("resv:lab:" + firstLabId + ":" + DATE)).isFalse();
        assertThat(redisTemplate.hasKey("resv:lab:" + secondLabId + ":" + DATE)).isFalse();
    }

    @Test
    void pendingCancellationWinningRaceLeavesWholePlanUnpublished() throws Exception {
        long labId = createLaboratory(2);
        long userId = userIdByAccount(createTestAccount("STUDENT"));
        long pendingId = createPending(userId, labId, DATE);
        CountDownLatch publishEntered = new CountDownLatch(1);
        CountDownLatch allowPublish = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishEntered.countDown();
            assertThat(allowPublish.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(primaryPlanPublisher).publish(anyLong());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> run = executor.submit(() -> scheduleService.runBatch(DATE));
            assertThat(publishEntered.await(30, TimeUnit.SECONDS)).isTrue();
            lifecycleService.cancel(pendingId, userId, "取消与发布竞争");
            allowPublish.countDown();
            run.get(60, TimeUnit.SECONDS);
        } finally {
            allowPublish.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(reservationStatus(pendingId)).isEqualTo("CANCELLED");
        assertThat(taskStatus(primaryTaskId(onlyBatchNo()))).isEqualTo("PUBLISH_FAILED");
        assertThat(countAssignments(pendingId)).isZero();
        assertThat(countPublishLogs(pendingId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result r
                JOIN t_schedule_task t ON t.task_id = r.task_id
                WHERE t.is_primary = TRUE AND r.is_published = TRUE
                """, Integer.class)).isZero();
    }

    @Test
    void roomPublicationWritesAllFormalSeatsButOnlyRoomBitmap() {
        long labId = createLaboratory(3);
        long userId = userIdByAccount(createTestAccount("TEACHER"));
        long pendingId = createPendingRoom(userId, labId, 3);

        scheduleService.runBatch(DATE);

        assertThat(reservationStatus(pendingId)).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT seat_count FROM t_reservation WHERE resv_id = ?",
                Integer.class, pendingId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat
                WHERE resv_id = ? AND assignment_method = 'AUTO_PUBLISH'
                """, Integer.class, pendingId)).isEqualTo(3);
        LocalDateTime start = jdbcTemplate.queryForObject(
                "SELECT start_time FROM t_reservation WHERE resv_id = ?",
                LocalDateTime.class, pendingId);
        LocalDateTime end = jdbcTemplate.queryForObject(
                "SELECT end_time FROM t_reservation WHERE resv_id = ?",
                LocalDateTime.class, pendingId);
        long expectedMask = TimeSlotMask.between(start.toLocalTime(), end.toLocalTime()).value();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT mask FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, DATE)).isEqualTo(expectedMask);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(mask), 0) FROM t_seat_bitmap b
                JOIN t_lab_seat s ON s.seat_id = b.seat_id
                WHERE s.lab_id = ? AND b.bitmap_date = ?
                """, Long.class, labId, DATE)).isZero();
        assertThat(countPublishLogs(pendingId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_schedule_result r
                JOIN t_schedule_task t ON t.task_id = r.task_id
                WHERE t.is_primary = TRUE AND r.resv_id = ? AND r.is_published = TRUE
                """, Integer.class, pendingId)).isOne();
    }

    @Test
    void disjointAssignmentsOnSameSeatAdvanceVersionAndMergeMasks() {
        long labId = createLaboratory(1);
        long firstUserId = userIdByAccount(createTestAccount("STUDENT"));
        long secondUserId = userIdByAccount(createTestAccount("STUDENT"));
        long firstPendingId = createPending(firstUserId, labId, DATE);
        long secondPendingId = createPending(secondUserId, labId, DATE);

        scheduleService.runBatch(DATE);

        assertThat(reservationStatus(firstPendingId)).isEqualTo("APPROVED");
        assertThat(reservationStatus(secondPendingId)).isEqualTo("APPROVED");
        List<LocalDateTime> starts = jdbcTemplate.queryForList("""
                SELECT start_time FROM t_reservation WHERE resv_id IN (?, ?) ORDER BY resv_id
                """, LocalDateTime.class, firstPendingId, secondPendingId);
        List<LocalDateTime> ends = jdbcTemplate.queryForList("""
                SELECT end_time FROM t_reservation WHERE resv_id IN (?, ?) ORDER BY resv_id
                """, LocalDateTime.class, firstPendingId, secondPendingId);
        assertThat(starts).doesNotHaveDuplicates();
        long firstSeatId = jdbcTemplate.queryForObject(
                "SELECT seat_id FROM t_reservation_seat WHERE resv_id = ?",
                Long.class, firstPendingId);
        long secondSeatId = jdbcTemplate.queryForObject(
                "SELECT seat_id FROM t_reservation_seat WHERE resv_id = ?",
                Long.class, secondPendingId);
        assertThat(secondSeatId).isEqualTo(firstSeatId);
        long expectedMask = 0;
        for (int index = 0; index < starts.size(); index++) {
            expectedMask |= TimeSlotMask.between(
                    starts.get(index).toLocalTime(), ends.get(index).toLocalTime()).value();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT mask FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?",
                Long.class, firstSeatId, DATE)).isEqualTo(expectedMask);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?",
                Integer.class, firstSeatId, DATE)).isEqualTo(2);
    }

    private long createPendingRoom(long userId, long labId, int attendees) {
        return reservationService.create(userId, new CreateReservationRequest(
                AllocationMode.SCHEDULABLE, labId, DATE, null, null,
                "09:00", "12:00", 60, ReservationMode.ROOM, attendees, null,
                "排期整室发布测试", new ReservationPreferences(null, null, null, List.of(labId)))).resvId();
    }

    private String taskStatus(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_schedule_task WHERE task_id = ?", String.class, taskId);
    }

    private int countAssignments(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation_seat WHERE resv_id = ?",
                Integer.class, reservationId);
    }

    private int countPublishLogs(long reservationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log
                WHERE resv_id = ? AND from_status = 'PENDING_SCHEDULE' AND to_status = 'APPROVED'
                """, Integer.class, reservationId);
    }

    private int bitmapVersionAt(long labId) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(version), 0) FROM t_seat_bitmap b
                JOIN t_lab_seat s ON s.seat_id = b.seat_id
                WHERE s.lab_id = ? AND b.bitmap_date = ?
                """, Integer.class, labId, DATE);
    }
}
