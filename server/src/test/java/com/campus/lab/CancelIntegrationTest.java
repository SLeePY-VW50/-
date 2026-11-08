package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.lab.reservation.LabLockKey;
import com.campus.lab.reservation.LockManager;
import com.campus.lab.reservation.ReservationLock;
import com.campus.lab.reservation.ReservationReleaseService;
import com.campus.lab.reservation.ReservationRepository;
import com.campus.lab.reservation.TimeSlotMask;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CancelIntegrationTest extends AbstractReservationNonTransactionalTest {

    private static final LocalDate FUTURE_DATE = LocalDate.of(2026, 11, 2);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private JdbcTemplate spyJdbcTemplate;

    @MockitoSpyBean
    private LockManager lockManager;

    @MockitoSpyBean
    private ReservationRepository reservationRepository;

    @MockitoSpyBean
    private ReservationReleaseService releaseService;

    @AfterEach
    void resetSpies() {
        Mockito.reset(spyJdbcTemplate, lockManager, reservationRepository, releaseService);
    }

    @Test
    void pendingCancellationIsIdempotentWithOneLogAndNoOccupancy() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        long reservationId = createPending(owner.token(), labId);

        cancel(owner.token(), reservationId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        cancel(owner.token(), reservationId, Map.of("reason", "重复取消"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED");
        assertThat(cancelLogCount(reservationId)).isEqualTo(1);
        assertThat(totalLogCount(reservationId)).isEqualTo(2);
        assertThat(count("t_reservation_seat", reservationId)).isZero();
        assertThat(count("t_violation", reservationId)).isZero();
        assertThat(creditRecordCount(owner.userId())).isZero();
        assertThat(bitmapRowCount(labId, FUTURE_DATE)).isZero();
    }

    @Test
    void seatCancellationReleasesExactlyKMasksAndAllowsAnotherUser() throws Exception {
        long labId = createLaboratory(6);
        Fixture owner = fixture("STUDENT");
        Fixture other = fixture("STUDENT");
        long reservationId = createFixed(owner.token(), labId, "SEAT", "09:00", "10:00", 2);
        long disjointId = createFixed(owner.token(), labId, "SEAT", "10:00", "11:00", 2);
        List<Long> seatIds = assignedSeatIds(reservationId);
        assertThat(seatIds).hasSize(2);
        assertThat(assignedSeatIds(disjointId)).containsExactlyElementsOf(seatIds);

        cancel(owner.token(), reservationId, Map.of("reason", "提前取消SEAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.seats.length()").value(2));

        long disjointMask = mask("10:00", "11:00");
        assertThat(seatMasks(seatIds)).allSatisfy((seatId, value) -> assertThat(value).isEqualTo(disjointMask));
        assertThat(count("t_reservation_seat", reservationId)).isEqualTo(2);
        assertThat(cancelLogCount(reservationId)).isEqualTo(1);

        long rebooked = createFixed(other.token(), labId, "SEAT", "09:00", "10:00", 2);
        assertThat(rebooked).isPositive();
        assertThat(statusOf(rebooked)).isEqualTo("APPROVED");
        long combinedMask = mask("09:00", "10:00") | disjointMask;
        assertThat(seatMasks(seatIds)).allSatisfy((seatId, value) -> assertThat(value).isEqualTo(combinedMask));
    }

    @Test
    void roomCancellationReleasesOnlyWholeRoomBitmap() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("TEACHER");
        Fixture other = fixture("TEACHER");
        long reservationId = createFixed(owner.token(), labId, "ROOM", "09:00", "10:00", 4);
        createFixed(owner.token(), labId, "ROOM", "10:00", "11:00", 4);
        int assignmentCount = count("t_reservation_seat", reservationId);
        assertThat(assignmentCount).isEqualTo(4);

        cancel(owner.token(), reservationId, Map.of("reason", "提前取消ROOM"))
                .andExpect(status().isOk());

        assertThat(roomMask(labId, FUTURE_DATE)).isEqualTo(mask("10:00", "11:00"));
        assertThat(count("t_reservation_seat", reservationId)).isEqualTo(assignmentCount);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_seat_bitmap b
                JOIN t_lab_seat s ON s.seat_id = b.seat_id
                WHERE s.lab_id = ? AND b.bitmap_date = ? AND b.mask <> 0
                """, Integer.class, labId, FUTURE_DATE)).isZero();

        long rebooked = createFixed(other.token(), labId, "ROOM", "09:00", "10:00", 4);
        assertThat(statusOf(rebooked)).isEqualTo("APPROVED");
        assertThat(roomMask(labId, FUTURE_DATE))
                .isEqualTo(mask("09:00", "10:00") | mask("10:00", "11:00"));
    }

    @Test
    void lateApprovedCancellationWritesViolationAndActualCreditLedger() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        long reservationId = createFixed(owner.token(), labId, "SEAT", "09:00", "10:00", 1);
        moveFixedReservationToToday(reservationId, labId);

        cancel(owner.token(), reservationId, Map.of("reason", "临时有事"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(creditScore(owner.userId())).isEqualTo(90);
        JsonNode violation = violationOf(owner.userId(), reservationId);
        assertThat(violation.path("violationType").asText()).isEqualTo("LATE_CANCEL");
        assertThat(violation.path("deductPoints").asInt()).isEqualTo(10);
        assertThat(violation.path("status").asText()).isEqualTo("ACTIVE");
        JsonNode ledger = creditRecordOf(owner.userId());
        assertThat(ledger.path("changeType").asText()).isEqualTo("DEDUCT");
        assertThat(ledger.path("changeAmount").asInt()).isEqualTo(-10);
        assertThat(ledger.path("balanceBefore").asInt()).isEqualTo(100);
        assertThat(ledger.path("balanceAfter").asInt()).isEqualTo(90);
        assertThat(ledger.path("violationId").asLong()).isEqualTo(violation.path("violationId").asLong());
        assertThat(roomMask(labId, TODAY)).isZero();
        assertThat(cancelLogCount(reservationId)).isEqualTo(1);
    }

    @Test
    void latePendingCancellationDeductsOnlyRemainingFivePoints() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        long reservationId = createPending(owner.token(), labId);
        jdbcTemplate.update("""
                UPDATE t_reservation
                SET request_date = ?, request_window_start = '09:00:00', request_window_end = '12:00:00'
                WHERE resv_id = ?
                """, TODAY, reservationId);
        jdbcTemplate.update("UPDATE sys_user SET credit_score = 5 WHERE user_id = ?", owner.userId());

        cancel(owner.token(), reservationId, null).andExpect(status().isOk());

        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED");
        assertThat(creditScore(owner.userId())).isZero();
        assertThat(violationOf(owner.userId(), reservationId).path("deductPoints").asInt()).isEqualTo(5);
        JsonNode ledger = creditRecordOf(owner.userId());
        assertThat(ledger.path("changeAmount").asInt()).isEqualTo(-5);
        assertThat(ledger.path("balanceBefore").asInt()).isEqualTo(5);
        assertThat(ledger.path("balanceAfter").asInt()).isZero();
    }

    @Test
    void cancellationExactlyTwoHoursBeforeStartIsFree() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        long reservationId = createFixed(owner.token(), labId, "SEAT", "10:00", "11:00", 1);
        moveFixedReservationToToday(reservationId, labId);

        cancel(owner.token(), reservationId, null).andExpect(status().isOk());

        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED");
        assertThat(creditScore(owner.userId())).isEqualTo(100);
        assertThat(count("t_violation", reservationId)).isZero();
        assertThat(creditRecordCount(owner.userId())).isZero();
    }

    @Test
    void nonOwnerAndAdministratorsCannotCancel() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        Fixture other = fixture("STUDENT");
        long reservationId = createFixed(owner.token(), labId, "SEAT", "09:00", "10:00", 1);

        cancel(other.token(), reservationId, null).andExpect(status().isNotFound());
        for (String account : new String[] {"L1001", "A1001"}) {
            cancel(loginAndGetToken(account, "Lab@2026"), reservationId, null)
                    .andExpect(status().isForbidden());
        }

        assertThat(statusOf(reservationId)).isEqualTo("APPROVED");
        assertThat(cancelLogCount(reservationId)).isZero();
    }

    @Test
    void startedReservationIsRejectedWithoutReleasingOccupancy() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        long reservationId = createFixed(owner.token(), labId, "SEAT", "09:00", "10:00", 1);
        moveFixedReservationToToday(reservationId, labId);
        jdbcTemplate.update("""
                UPDATE t_reservation
                SET request_window_start = '08:00:00', request_window_end = '09:00:00',
                    start_time = '2026-10-24 08:00:00', end_time = '2026-10-24 09:00:00'
                WHERE resv_id = ?
                """, reservationId);
        Map<Long, Long> before = allSeatMasks(labId, TODAY);

        cancel(owner.token(), reservationId, null).andExpect(status().isConflict());

        assertThat(statusOf(reservationId)).isEqualTo("APPROVED");
        assertThat(allSeatMasks(labId, TODAY)).isEqualTo(before);
        assertThat(cancelLogCount(reservationId)).isZero();
    }

    @Test
    void illegalStatesAreRejected() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        long reservationId = createFixed(owner.token(), labId, "SEAT", "09:00", "10:00", 1);

        for (String illegalState : new String[] {"IN_USE", "COMPLETED", "NO_SHOW", "EXPIRED"}) {
            jdbcTemplate.update("UPDATE t_reservation SET status = ? WHERE resv_id = ?", illegalState, reservationId);
            cancel(owner.token(), reservationId, null).andExpect(status().isConflict());
            assertThat(statusOf(reservationId)).isEqualTo(illegalState);
        }
        assertThat(cancelLogCount(reservationId)).isZero();
    }

    @Test
    void missingReleaseBitsRollBackEarlierSeatReleaseAndEveryOtherChange() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        long reservationId = createFixed(owner.token(), labId, "SEAT", "09:00", "10:00", 2);
        moveFixedReservationToToday(reservationId, labId);
        List<Long> seatIds = assignedSeatIds(reservationId);
        Map<Long, Long> before = seatMasks(seatIds);
        jdbcTemplate.update("UPDATE t_seat_bitmap SET mask = 0 WHERE seat_id = ? AND bitmap_date = ?",
                seatIds.get(1), TODAY);

        cancel(owner.token(), reservationId, null).andExpect(status().isConflict());

        assertThat(statusOf(reservationId)).isEqualTo("APPROVED");
        assertThat(seatMask(seatIds.get(0))).isEqualTo(before.get(seatIds.get(0)));
        assertThat(seatMask(seatIds.get(1))).isZero();
        assertThat(creditScore(owner.userId())).isEqualTo(100);
        assertThat(count("t_violation", reservationId)).isZero();
        assertThat(creditRecordCount(owner.userId())).isZero();
        assertThat(totalLogCount(reservationId)).isEqualTo(1);
    }

    @Test
    void creditInsertFailureRollsBackStatusBitmapViolationAndLog() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        long reservationId = createFixed(owner.token(), labId, "SEAT", "09:00", "10:00", 1);
        moveFixedReservationToToday(reservationId, labId);
        long seatId = assignedSeatIds(reservationId).getFirst();
        long maskBefore = seatMask(seatId);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("INSERT INTO t_credit_record")) {
                throw new IllegalStateException("注入信用流水失败");
            }
            return invocation.callRealMethod();
        }).when(spyJdbcTemplate).update(anyString(), any(Object[].class));

        cancel(owner.token(), reservationId, null).andExpect(status().isInternalServerError());
        Mockito.reset(spyJdbcTemplate);

        assertThat(statusOf(reservationId)).isEqualTo("APPROVED");
        assertThat(seatMask(seatId)).isEqualTo(maskBefore);
        assertThat(creditScore(owner.userId())).isEqualTo(100);
        assertThat(count("t_violation", reservationId)).isZero();
        assertThat(creditRecordCount(owner.userId())).isZero();
        assertThat(totalLogCount(reservationId)).isEqualTo(1);
    }

    @Test
    void cancelLockIsReleasedOnlyAfterTransactionCommit() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        long reservationId = createFixed(owner.token(), labId, "SEAT", "09:00", "10:00", 1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(statusOf(reservationId)).isEqualTo("CANCELLED");
            return invocation.callRealMethod();
        }).when(lockManager).release(any(ReservationLock.class));

        cancel(owner.token(), reservationId, null).andExpect(status().isOk());

        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED");
    }

    @Test
    void lockKeyChangeRetriesWithCorrectLabDateAndReleasesOnlyOnce() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        long reservationId = createFixed(owner.token(), labId, "SEAT", "09:00", "10:00", 1);
        long seatId = assignedSeatIds(reservationId).getFirst();
        Mockito.clearInvocations(lockManager, reservationRepository, releaseService);
        AtomicInteger targetReads = new AtomicInteger();
        List<LabLockKey> acquiredKeys = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            if (targetReads.incrementAndGet() == 1) {
                return new ReservationRepository.CancellationTarget(null, FUTURE_DATE);
            }
            return invocation.callRealMethod();
        }).when(reservationRepository).findCancellationTarget(reservationId);
        doAnswer(invocation -> {
            List<LabLockKey> keys = invocation.getArgument(0);
            acquiredKeys.addAll(keys);
            return invocation.callRealMethod();
        }).when(lockManager).acquireAll(anyList());

        cancel(owner.token(), reservationId, null).andExpect(status().isOk());

        assertThat(targetReads).hasValue(2);
        assertThat(acquiredKeys).containsExactly(new LabLockKey(labId, FUTURE_DATE));
        Mockito.verify(lockManager).acquireAll(anyList());
        Mockito.verify(lockManager).release(any(ReservationLock.class));
        Mockito.verify(releaseService).release(any(ReservationRepository.CancellationReservation.class));
        assertThat(redisTemplate.hasKey("resv:lab:" + labId + ":" + FUTURE_DATE)).isFalse();
        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED");
        assertThat(cancelLogCount(reservationId)).isEqualTo(1);
        assertThat(count("t_violation", reservationId)).isZero();
        assertThat(seatMask(seatId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT version FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?
                """, Integer.class, seatId, FUTURE_DATE)).isEqualTo(2);
    }

    @Test
    void lockKeyStillChangingAfterRetryReturnsConflictWithoutRelease() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        long reservationId = createFixed(owner.token(), labId, "SEAT", "09:00", "10:00", 1);
        long seatId = assignedSeatIds(reservationId).getFirst();
        Mockito.clearInvocations(lockManager, reservationRepository, releaseService);
        doAnswer(invocation -> new ReservationRepository.CancellationTarget(null, FUTURE_DATE))
                .when(reservationRepository).findCancellationTarget(reservationId);

        cancel(owner.token(), reservationId, null).andExpect(status().isConflict());

        Mockito.verify(reservationRepository, Mockito.times(2)).findCancellationTarget(reservationId);
        Mockito.verify(lockManager, Mockito.never()).acquireAll(anyList());
        Mockito.verify(releaseService, Mockito.never())
                .release(any(ReservationRepository.CancellationReservation.class));
        assertThat(statusOf(reservationId)).isEqualTo("APPROVED");
        assertThat(cancelLogCount(reservationId)).isZero();
        assertThat(seatMask(seatId)).isEqualTo(mask("09:00", "10:00"));
    }

    @Test
    void concurrentRepeatedLateCancellationReleasesAndPenalizesOnlyOnce() throws Exception {
        long labId = createLaboratory(4);
        Fixture owner = fixture("STUDENT");
        long reservationId = createFixed(owner.token(), labId, "SEAT", "09:00", "10:00", 1);
        moveFixedReservationToToday(reservationId, labId);
        long seatId = assignedSeatIds(reservationId).getFirst();
        Mockito.clearInvocations(lockManager, reservationRepository, releaseService);
        CountDownLatch firstReleaseEntered = new CountDownLatch(1);
        CountDownLatch allowFirstRelease = new CountDownLatch(1);
        CountDownLatch secondLockAttempt = new CountDownLatch(1);
        AtomicInteger releaseCalls = new AtomicInteger();
        AtomicInteger lockCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (releaseCalls.incrementAndGet() == 1) {
                firstReleaseEntered.countDown();
                assertThat(allowFirstRelease.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(releaseService).release(any(ReservationRepository.CancellationReservation.class));
        doAnswer(invocation -> {
            if (lockCalls.incrementAndGet() == 2) {
                List<LabLockKey> keys = invocation.getArgument(0);
                LabLockKey key = keys.getFirst();
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                        "resv:lab:" + key.labId() + ":" + key.date(),
                        "second-cancel-competitor",
                        java.time.Duration.ofSeconds(30));
                assertThat(acquired).isFalse();
                secondLockAttempt.countDown();
            }
            return invocation.callRealMethod();
        }).when(lockManager).acquireAll(anyList());

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Integer> first = executor.submit(() -> cancel(owner.token(), reservationId, null)
                    .andReturn().getResponse().getStatus());
            assertThat(firstReleaseEntered.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Integer> second = executor.submit(() -> cancel(owner.token(), reservationId, null)
                    .andReturn().getResponse().getStatus());
            assertThat(secondLockAttempt.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(second.isDone()).isFalse();

            allowFirstRelease.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            allowFirstRelease.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(releaseCalls).hasValue(1);
        assertThat(lockCalls).hasValue(2);
        assertThat(redisTemplate.hasKey("resv:lab:" + labId + ":" + TODAY)).isFalse();
        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED");
        assertThat(cancelLogCount(reservationId)).isEqualTo(1);
        assertThat(count("t_violation", reservationId)).isEqualTo(1);
        assertThat(creditRecordCount(owner.userId())).isEqualTo(1);
        assertThat(creditScore(owner.userId())).isEqualTo(90);
        assertThat(seatMask(seatId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT version FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?
                """, Integer.class, seatId, TODAY)).isEqualTo(2);
    }

    private Fixture fixture(String role) throws Exception {
        String account = createTestAccount(role);
        return new Fixture(userIdByAccount(account), loginAndGetToken(account, "Lab@2026"));
    }

    private long createFixed(
            String token, long labId, String mode, String start, String end, int attendees) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("allocationMode", "FIXED");
        request.put("labId", labId);
        request.put("date", FUTURE_DATE.toString());
        request.put("startTime", start);
        request.put("endTime", end);
        request.put("reservationMode", mode);
        request.put("attendeeCount", attendees);
        if ("SEAT".equals(mode)) {
            request.put("seatCount", attendees);
        }
        request.put("purpose", "取消集成测试");
        request.put("preferences", Map.of());
        JsonNode response = responseJson(mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn());
        return response.path("data").path("resvId").asLong();
    }

    private long createPending(String token, long labId) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("allocationMode", "SCHEDULABLE");
        request.put("date", FUTURE_DATE.toString());
        request.put("windowStart", "08:00");
        request.put("windowEnd", "12:00");
        request.put("durationMinutes", 60);
        request.put("reservationMode", "SEAT");
        request.put("attendeeCount", 1);
        request.put("seatCount", 1);
        request.put("purpose", "待排期取消集成测试");
        request.put("preferences", Map.of("allowedLabIds", List.of(labId)));
        JsonNode response = responseJson(mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(response.path("data").path("status").asText()).isEqualTo("PENDING_SCHEDULE");
        return response.path("data").path("resvId").asLong();
    }

    private ResultActions cancel(String token, long reservationId, Map<String, String> body) throws Exception {
        var request = post("/api/reservations/{id}/cancel", reservationId)
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    private void moveFixedReservationToToday(long reservationId, long labId) {
        jdbcTemplate.update("""
                UPDATE t_reservation
                SET request_date = ?, start_time = TIMESTAMP(?, TIME(start_time)),
                    end_time = TIMESTAMP(?, TIME(end_time))
                WHERE resv_id = ?
                """, TODAY, TODAY, TODAY, reservationId);
        jdbcTemplate.update("UPDATE t_slot_bitmap SET bitmap_date = ? WHERE lab_id = ? AND bitmap_date = ?",
                TODAY, labId, FUTURE_DATE);
        jdbcTemplate.update("""
                UPDATE t_seat_bitmap b JOIN t_lab_seat s ON s.seat_id = b.seat_id
                SET b.bitmap_date = ? WHERE s.lab_id = ? AND b.bitmap_date = ?
                """, TODAY, labId, FUTURE_DATE);
    }

    private String statusOf(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_reservation WHERE resv_id = ?", String.class, reservationId);
    }

    private int count(String table, long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE resv_id = ?", Integer.class, reservationId);
    }

    private int cancelLogCount(long reservationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log
                WHERE resv_id = ? AND from_status <> 'CANCELLED' AND to_status = 'CANCELLED'
                """, Integer.class, reservationId);
    }

    private int totalLogCount(long reservationId) {
        return count("t_reservation_log", reservationId);
    }

    private int creditScore(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT credit_score FROM sys_user WHERE user_id = ?", Integer.class, userId);
    }

    private int creditRecordCount(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_credit_record WHERE user_id = ?", Integer.class, userId);
    }

    private JsonNode violationOf(long userId, long reservationId) throws Exception {
        String json = jdbcTemplate.queryForObject("""
                SELECT JSON_OBJECT('violationId', violation_id, 'violationType', violation_type,
                                   'deductPoints', deduct_points, 'status', status)
                FROM t_violation WHERE user_id = ? AND resv_id = ?
                """, String.class, userId, reservationId);
        return objectMapper.readTree(json);
    }

    private JsonNode creditRecordOf(long userId) throws Exception {
        String json = jdbcTemplate.queryForObject("""
                SELECT JSON_OBJECT('violationId', violation_id, 'changeType', change_type,
                                   'changeAmount', change_amount, 'balanceBefore', balance_before,
                                   'balanceAfter', balance_after)
                FROM t_credit_record WHERE user_id = ? ORDER BY record_id DESC LIMIT 1
                """, String.class, userId);
        return objectMapper.readTree(json);
    }

    private List<Long> assignedSeatIds(long reservationId) {
        return jdbcTemplate.queryForList("""
                SELECT rs.seat_id FROM t_reservation_seat rs
                JOIN t_lab_seat s ON s.seat_id = rs.seat_id
                WHERE rs.resv_id = ? ORDER BY s.seat_index, rs.seat_id
                """, Long.class, reservationId);
    }

    private Map<Long, Long> seatMasks(List<Long> seatIds) {
        Map<Long, Long> values = new HashMap<>();
        for (long seatId : seatIds) {
            values.put(seatId, seatMask(seatId));
        }
        return values;
    }

    private long seatMask(long seatId) {
        return jdbcTemplate.queryForObject(
                "SELECT mask FROM t_seat_bitmap WHERE seat_id = ?",
                Long.class, seatId);
    }

    private Map<Long, Long> allSeatMasks(long labId, LocalDate date) {
        return jdbcTemplate.query("""
                SELECT b.seat_id, b.mask FROM t_seat_bitmap b
                JOIN t_lab_seat s ON s.seat_id = b.seat_id
                WHERE s.lab_id = ? AND b.bitmap_date = ? ORDER BY b.seat_id
                """, resultSet -> {
            Map<Long, Long> values = new HashMap<>();
            while (resultSet.next()) {
                values.put(resultSet.getLong(1), resultSet.getLong(2));
            }
            return values;
        }, labId, date);
    }

    private long roomMask(long labId, LocalDate date) {
        return jdbcTemplate.queryForObject(
                "SELECT mask FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, date);
    }

    private int bitmapRowCount(long labId, LocalDate date) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Integer.class, labId, date);
    }

    private static long mask(String start, String end) {
        return TimeSlotMask.between(LocalTime.parse(start), LocalTime.parse(end)).value();
    }

    private record Fixture(long userId, String token) {
    }
}
