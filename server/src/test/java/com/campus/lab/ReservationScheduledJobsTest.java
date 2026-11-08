package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.campus.lab.checkin.TerminalCheckinService;
import com.campus.lab.checkin.TerminalSessionService;
import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.LockManager;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class ReservationScheduledJobsTest extends AbstractCheckinNonTransactionalTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TerminalCheckinService terminalCheckinService;

    @MockitoSpyBean
    private Clock clock;

    @MockitoSpyBean
    private JdbcTemplate spyJdbcTemplate;

    @MockitoSpyBean
    private LockManager lockManager;

    @AfterEach
    void resetSpies() {
        Mockito.reset(clock, spyJdbcTemplate, lockManager);
    }

    @Test
    void noShowUsesActualCreditDeductionReleasesRemainingAndIsIdempotent() throws Exception {
        at("2026-10-24T02:10:00Z");
        long labId = createLaboratory(2);
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        long reservationId = createReservation(
                userId, labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 12, 0), true);
        jdbcTemplate.update("UPDATE sys_user SET credit_score = 5 WHERE user_id = ?", userId);
        long seatId = assignedSeatId(reservationId);

        invokeJobs("processNoShows");

        assertThat(statusOf(reservationId)).isEqualTo("NO_SHOW");
        assertThat(creditScore(userId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT deduct_points FROM t_violation
                WHERE resv_id = ? AND violation_type = 'NO_SHOW'
                """, Integer.class, reservationId)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT change_amount FROM t_credit_record WHERE user_id = ?
                """, Integer.class, userId)).isEqualTo(-5);
        assertThat(seatMask(seatId)).isEqualTo(mask("08:00", "10:30"));
        assertThat(transitionCount(reservationId, "APPROVED", "NO_SHOW")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT reason FROM t_reservation_log
                WHERE resv_id = ? AND from_status = 'APPROVED' AND to_status = 'NO_SHOW'
                """, String.class, reservationId)).contains("5");
        int version = bitmapVersion(seatId);

        invokeJobs("processNoShows");
        assertThat(creditScore(userId)).isZero();
        assertThat(count("t_violation", reservationId)).isEqualTo(1);
        assertThat(creditRecordCount(userId)).isEqualTo(1);
        assertThat(transitionCount(reservationId, "APPROVED", "NO_SHOW")).isEqualTo(1);
        assertThat(bitmapVersion(seatId)).isEqualTo(version);
    }

    @Test
    void checkinHoldingLabLockWinsAgainstNoShowWithoutCreditOrDuplicateTransition() throws Exception {
        Instant checkinNow = Instant.parse("2026-10-24T00:15:00Z");
        Instant noShowNow = Instant.parse("2026-10-24T00:15:01Z");
        doAnswer(invocation -> "checkin-competitor".equals(Thread.currentThread().getName())
                ? checkinNow : noShowNow).when(clock).instant();
        long labId = createLaboratory(2);
        TerminalFixture terminal = createTerminal(labId);
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        long reservationId = createReservation(
                userId, labId, "SEAT", "APPROVED",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 10, 0), true);
        Object jobs = jobsBean();
        Method processNoShows = jobs.getClass().getMethod("processNoShows");
        TerminalSessionService.TerminalContext context =
                new TerminalSessionService.TerminalContext(terminal.deviceId(), labId);
        CountDownLatch checkinLockHeld = new CountDownLatch(1);
        CountDownLatch allowCheckinToFinish = new CountDownLatch(1);
        CountDownLatch noShowNxFailed = new CountDownLatch(1);
        CountDownLatch noShowAcquireReturned = new CountDownLatch(1);
        doAnswer(invocation -> {
            if ("checkin-competitor".equals(Thread.currentThread().getName())) {
                Object result = invocation.callRealMethod();
                checkinLockHeld.countDown();
                assertThat(allowCheckinToFinish.await(30, TimeUnit.SECONDS)).isTrue();
                return result;
            }
            if ("no-show-competitor".equals(Thread.currentThread().getName())) {
                List<com.campus.lab.reservation.LabLockKey> keys = invocation.getArgument(0);
                com.campus.lab.reservation.LabLockKey key = keys.getFirst();
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                        "resv:lab:" + key.labId() + ":" + key.date(),
                        "no-show-competitor",
                        java.time.Duration.ofSeconds(30));
                assertThat(acquired).isFalse();
                noShowNxFailed.countDown();
                Object result = invocation.callRealMethod();
                noShowAcquireReturned.countDown();
                return result;
            }
            return invocation.callRealMethod();
        }).when(lockManager).acquireAll(Mockito.anyList());
        AtomicReference<Throwable> checkinFailure = new AtomicReference<>();
        AtomicReference<Throwable> jobFailure = new AtomicReference<>();
        Thread checkinThread = new Thread(() -> {
            try {
                terminalCheckinService.checkin(context, account, USER_PASSWORD);
            } catch (Throwable exception) {
                checkinFailure.set(exception);
            }
        }, "checkin-competitor");
        Thread jobThread = new Thread(
                () -> invoke(jobs, processNoShows, jobFailure), "no-show-competitor");

        try {
            checkinThread.start();
            assertThat(checkinLockHeld.await(30, TimeUnit.SECONDS)).isTrue();
            jobThread.start();
            assertThat(noShowNxFailed.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(noShowAcquireReturned.getCount()).isEqualTo(1);
            assertThat(jobThread.isAlive()).isTrue();
            allowCheckinToFinish.countDown();
        } finally {
            allowCheckinToFinish.countDown();
            checkinThread.join(30_000);
            jobThread.join(30_000);
        }
        assertThat(checkinThread.isAlive()).isFalse();
        assertThat(jobThread.isAlive()).isFalse();

        assertThat(checkinFailure.get()).isNull();
        assertThat(jobFailure.get()).isNull();
        assertThat(statusOf(reservationId)).isEqualTo("IN_USE");
        assertThat(transitionCount(reservationId, "APPROVED", "IN_USE")).isEqualTo(1);
        assertThat(transitionCount(reservationId, "APPROVED", "NO_SHOW")).isZero();
        assertThat(count("t_violation", reservationId)).isZero();
        assertThat(creditRecordCount(userId)).isZero();
        assertThat(creditScore(userId)).isEqualTo(100);
    }

    @Test
    void endedReservationCompletesAtItsEndWithoutReleasingHistory() throws Exception {
        at("2026-10-24T02:10:00Z");
        long labId = createLaboratory(2);
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        long reservationId = createReservation(
                userId, labId, "SEAT", "IN_USE",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 10, 0), true);
        long seatId = assignedSeatId(reservationId);
        Map<String, Object> before = bitmap(seatId);

        invokeJobs("processEndedReservations");

        assertThat(statusOf(reservationId)).isEqualTo("COMPLETED");
        assertThat(checkoutTime(reservationId)).isEqualTo(LocalDateTime.of(2026, 10, 24, 10, 0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT checkout_channel FROM t_reservation WHERE resv_id = ?",
                String.class, reservationId)).isNull();
        assertThat(bitmap(seatId)).isEqualTo(before);
        assertThat(transitionCount(reservationId, "IN_USE", "COMPLETED")).isEqualTo(1);
        invokeJobs("processEndedReservations");
        assertThat(transitionCount(reservationId, "IN_USE", "COMPLETED")).isEqualTo(1);
        assertThat(bitmap(seatId)).isEqualTo(before);
    }

    @Test
    void pendingScheduleExpiresWithoutAnyOccupancyOrCreditChange() throws Exception {
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        long reservationId = createPending(userId, "07:00:00", "08:00:00");
        Map<String, Long> masksBefore = maskTotals();

        invokeJobs("processExpiredPendingReservations");

        assertThat(statusOf(reservationId)).isEqualTo("EXPIRED");
        assertThat(transitionCount(reservationId, "PENDING_SCHEDULE", "EXPIRED")).isEqualTo(1);
        assertThat(count("t_reservation_seat", reservationId)).isZero();
        assertThat(count("t_violation", reservationId)).isZero();
        assertThat(creditRecordCount(userId)).isZero();
        assertThat(creditScore(userId)).isEqualTo(100);
        assertThat(maskTotals()).isEqualTo(masksBefore);
        invokeJobs("processExpiredPendingReservations");
        assertThat(transitionCount(reservationId, "PENDING_SCHEDULE", "EXPIRED")).isEqualTo(1);
    }

    @Test
    void oneFailureDoesNotBlockOthersAndSecondHundredContinuesThenRetries() throws Exception {
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        List<Long> reservationIds = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            reservationIds.add(createPending(userId, "06:00:00", "07:00:00"));
        }
        AtomicInteger failures = new AtomicInteger();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SET status = 'EXPIRED'") && failures.compareAndSet(0, 1)) {
                throw new IllegalStateException("注入单条过期失败");
            }
            return invocation.callRealMethod();
        }).when(spyJdbcTemplate).update(anyString(), Mockito.any(Object[].class));

        invokeJobs("processExpiredPendingReservations");

        assertThat(failures).hasValue(1);
        assertThat(countByStatus(userId, "EXPIRED")).isEqualTo(100);
        assertThat(countByStatus(userId, "PENDING_SCHEDULE")).isEqualTo(1);
        Mockito.reset(spyJdbcTemplate);
        invokeJobs("processExpiredPendingReservations");
        assertThat(countByStatus(userId, "EXPIRED")).isEqualTo(101);
        assertThat(countByStatus(userId, "PENDING_SCHEDULE")).isZero();
        assertThat(reservationIds).hasSize(101);
    }

    @Test
    void noShowCandidateQueryUsesDirectStartTimeRangeComparison() {
        Mockito.clearInvocations(spyJdbcTemplate);

        invokeJobs("processNoShows");

        List<String> statements = Mockito.mockingDetails(spyJdbcTemplate).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("queryForList")
                        && invocation.getArguments().length > 0
                        && invocation.getArgument(0) instanceof String)
                .map(invocation -> (String) invocation.getArgument(0))
                .filter(sql -> sql.contains("SELECT resv_id FROM t_reservation"))
                .toList();
        assertThat(statements).isNotEmpty();
        assertThat(statements).allSatisfy(statement -> assertThat(statement)
                .contains("start_time < DATE_SUB")
                .doesNotContain("DATE_ADD(start_time"));
    }

    private Object jobsBean() {
        assertThat(applicationContext.containsBean("reservationScheduledJobs")).isTrue();
        return applicationContext.getBean("reservationScheduledJobs");
    }

    private void invokeJobs(String methodName) {
        Object jobs = jobsBean();
        try {
            Method method = jobs.getClass().getMethod(methodName);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            invoke(jobs, method, failure);
            assertThat(failure.get()).isNull();
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void invoke(Object target, Method method, AtomicReference<Throwable> failure) {
        try {
            method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            failure.set(exception.getCause() == null ? exception : exception.getCause());
        }
    }

    private void at(String instant) {
        doReturn(Instant.parse(instant)).when(clock).instant();
    }

    private long createPending(long userId, String windowStart, String windowEnd) {
        String purpose = "scheduled-job-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO t_reservation
                    (user_id, allocation_mode, reservation_mode, request_date,
                     request_window_start, request_window_end, requested_duration_minutes,
                     lab_id, start_time, end_time, attendee_count, seat_count, purpose, status)
                VALUES (?, 'SCHEDULABLE', 'SEAT', ?, ?, ?, 30,
                        NULL, NULL, NULL, 1, 1, ?, 'PENDING_SCHEDULE')
                """, userId, TODAY, windowStart, windowEnd, purpose);
        Long reservationId = jdbcTemplate.queryForObject(
                "SELECT resv_id FROM t_reservation WHERE purpose = ?", Long.class, purpose);
        assertThat(reservationId).isNotNull();
        jdbcTemplate.update("""
                INSERT INTO t_reservation_log (resv_id, from_status, to_status, operator_id, reason)
                VALUES (?, NULL, 'PENDING_SCHEDULE', ?, '自动任务测试待排期')
                """, reservationId, userId);
        return reservationId;
    }

    private long assignedSeatId(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT seat_id FROM t_reservation_seat WHERE resv_id = ? ORDER BY seat_id LIMIT 1",
                Long.class, reservationId);
    }

    private long seatMask(long seatId) {
        return jdbcTemplate.queryForObject(
                "SELECT mask FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?",
                Long.class, seatId, TODAY);
    }

    private int bitmapVersion(long seatId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?",
                Integer.class, seatId, TODAY);
    }

    private Map<String, Object> bitmap(long seatId) {
        return jdbcTemplate.queryForMap(
                "SELECT mask, version FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?",
                seatId, TODAY);
    }

    private Map<String, Long> maskTotals() {
        Long room = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(mask), 0) FROM t_slot_bitmap", Long.class);
        Long seats = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(mask), 0) FROM t_seat_bitmap", Long.class);
        return Map.of("room", room, "seats", seats);
    }

    private LocalDateTime checkoutTime(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT checkout_time FROM t_reservation WHERE resv_id = ?",
                LocalDateTime.class, reservationId);
    }

    private String statusOf(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_reservation WHERE resv_id = ?", String.class, reservationId);
    }

    private int transitionCount(long reservationId, String from, String to) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log
                WHERE resv_id = ? AND from_status = ? AND to_status = ?
                """, Integer.class, reservationId, from, to);
    }

    private int count(String table, long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE resv_id = ?", Integer.class, reservationId);
    }

    private int creditScore(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT credit_score FROM sys_user WHERE user_id = ?", Integer.class, userId);
    }

    private int creditRecordCount(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_credit_record WHERE user_id = ?", Integer.class, userId);
    }

    private int countByStatus(long userId, String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE user_id = ? AND status = ?",
                Integer.class, userId, status);
    }

    private static long mask(String start, String end) {
        return com.campus.lab.reservation.TimeSlotMask.between(
                LocalTime.parse(start), LocalTime.parse(end)).value();
    }
}
