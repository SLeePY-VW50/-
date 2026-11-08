package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.campus.lab.checkin.ReservationCheckoutService;
import com.campus.lab.reservation.ReservationRepository;
import com.campus.lab.reservation.ReservationScheduledJobs;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

class CheckoutEndIntegrationTest extends AbstractCheckinNonTransactionalTest {

    @Autowired
    private ReservationScheduledJobs scheduledJobs;

    @Autowired
    private ReservationCheckoutService checkoutService;

    @MockitoSpyBean
    private Clock clock;

    @MockitoSpyBean
    private ReservationRepository reservationRepository;

    @AfterEach
    void resetSpies() {
        Mockito.reset(clock, reservationRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-24T02:00:00Z", "2026-10-24T02:10:00Z"})
    void manualCheckoutAtOrAfterEndUsesAutomaticCompletionSemantics(String instant) throws Exception {
        at(instant);
        long labId = createLaboratory(2);
        String account = createTestAccount("STUDENT");
        long userId = userIdByAccount(account);
        String token = loginAndGetToken(account, USER_PASSWORD);
        long reservationId = createReservation(
                userId, labId, "SEAT", "IN_USE",
                LocalDateTime.of(2026, 10, 24, 8, 0),
                LocalDateTime.of(2026, 10, 24, 10, 0), true);
        long seatId = assignedSeatId(reservationId);
        Map<String, Object> before = bitmap(seatId);

        MvcResult result = mockMvc.perform(post("/api/checkin/{id}/checkout", reservationId)
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(statusOf(reservationId)).isEqualTo("COMPLETED");
        assertThat(checkoutTime(reservationId)).isEqualTo(LocalDateTime.of(2026, 10, 24, 10, 0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT checkout_channel FROM t_reservation WHERE resv_id = ?",
                String.class, reservationId)).isNull();
        assertThat(systemCompletionLogCount(reservationId)).isEqualTo(1);
        assertThat(webCompletionLogCount(reservationId)).isZero();
        assertThat(bitmap(seatId)).isEqualTo(before);
    }

    @Test
    void manualAndAutomaticCompletionCompeteWithOneSystemLogAndUnchangedHistory() throws Exception {
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
        CountDownLatch manualRowLockHeld = new CountDownLatch(1);
        CountDownLatch allowManualToFinish = new CountDownLatch(1);
        CountDownLatch automaticLockQueryEntered = new CountDownLatch(1);
        CountDownLatch automaticLockQueryReturned = new CountDownLatch(1);
        doReturn(Instant.parse("2026-10-24T02:10:00Z")).when(clock).instant();
        Mockito.doAnswer(invocation -> {
            if ("manual-end-checkout".equals(Thread.currentThread().getName())) {
                Object result = invocation.callRealMethod();
                manualRowLockHeld.countDown();
                assertThat(allowManualToFinish.await(30, TimeUnit.SECONDS)).isTrue();
                return result;
            }
            if ("automatic-end-job".equals(Thread.currentThread().getName())) {
                automaticLockQueryEntered.countDown();
                Object result = invocation.callRealMethod();
                automaticLockQueryReturned.countDown();
                return result;
            }
            return invocation.callRealMethod();
        }).when(reservationRepository).lockReservationForCancellation(reservationId);
        AtomicReference<Throwable> manualFailure = new AtomicReference<>();
        AtomicReference<Throwable> automaticFailure = new AtomicReference<>();
        Thread manualThread = new Thread(() -> {
            try {
                checkoutService.checkout(reservationId, userId);
            } catch (Throwable exception) {
                manualFailure.set(exception);
            }
        }, "manual-end-checkout");
        Thread automaticThread = new Thread(() -> {
            try {
                scheduledJobs.processEndedReservations();
            } catch (Throwable exception) {
                automaticFailure.set(exception);
            }
        }, "automatic-end-job");

        try {
            manualThread.start();
            assertThat(manualRowLockHeld.await(30, TimeUnit.SECONDS)).isTrue();
            automaticThread.start();
            assertThat(automaticLockQueryEntered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(automaticLockQueryReturned.getCount()).isEqualTo(1);
            assertThat(automaticThread.isAlive()).isTrue();
            allowManualToFinish.countDown();
        } finally {
            allowManualToFinish.countDown();
            manualThread.join(30_000);
            automaticThread.join(30_000);
        }

        assertThat(manualThread.isAlive()).isFalse();
        assertThat(automaticThread.isAlive()).isFalse();
        assertThat(manualFailure.get()).isNull();
        assertThat(automaticFailure.get()).isNull();
        assertThat(statusOf(reservationId)).isEqualTo("COMPLETED");
        assertThat(checkoutTime(reservationId)).isEqualTo(LocalDateTime.of(2026, 10, 24, 10, 0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT checkout_channel FROM t_reservation WHERE resv_id = ?",
                String.class, reservationId)).isNull();
        assertThat(systemCompletionLogCount(reservationId)).isEqualTo(1);
        assertThat(webCompletionLogCount(reservationId)).isZero();
        assertThat(bitmap(seatId)).isEqualTo(before);
    }

    private void at(String instant) {
        doReturn(Instant.parse(instant)).when(clock).instant();
    }

    private long assignedSeatId(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT seat_id FROM t_reservation_seat WHERE resv_id = ? ORDER BY seat_id LIMIT 1",
                Long.class, reservationId);
    }

    private Map<String, Object> bitmap(long seatId) {
        return jdbcTemplate.queryForMap(
                "SELECT mask, version FROM t_seat_bitmap WHERE seat_id = ? AND bitmap_date = ?",
                seatId, TODAY);
    }

    private String statusOf(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_reservation WHERE resv_id = ?", String.class, reservationId);
    }

    private LocalDateTime checkoutTime(long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT checkout_time FROM t_reservation WHERE resv_id = ?",
                LocalDateTime.class, reservationId);
    }

    private int systemCompletionLogCount(long reservationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log
                WHERE resv_id = ? AND from_status = 'IN_USE' AND to_status = 'COMPLETED'
                  AND operator_id IS NULL AND reason = '预约到期自动结束'
                """, Integer.class, reservationId);
    }

    private int webCompletionLogCount(long reservationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log
                WHERE resv_id = ? AND from_status = 'IN_USE' AND to_status = 'COMPLETED'
                  AND reason = 'Web账号签退'
                """, Integer.class, reservationId);
    }
}
