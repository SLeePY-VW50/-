package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.AllocationMode;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationService;
import com.campus.lab.reservation.ReservationStatus;
import com.campus.lab.reservation.TimeSlotMask;
import com.campus.lab.reservation.dto.CreateReservationRequest;
import com.campus.lab.reservation.dto.ReservationPreferences;
import com.campus.lab.reservation.dto.ReservationView;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationConcurrencyTest extends AbstractReservationNonTransactionalTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Autowired
    private ReservationService reservationService;

    @Test
    void oneHundredUsersCompeteForTwentySeatsWithoutOverAllocation() throws Exception {
        long labId = createLaboratory(20);
        List<Long> userIds = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            userIds.add(userIdByAccount(createTestAccount("STUDENT")));
        }

        List<Outcome> outcomes = compete(userIds.stream()
                .map(userId -> new Attempt(userId, labId))
                .toList());
        long succeeded = outcomes.stream().filter(outcome -> outcome.view != null).count();
        long conflicts = outcomes.stream()
                .filter(outcome -> outcome.conflict != null && outcome.conflict.getStatus().value() == 409)
                .count();
        List<Throwable> errors = outcomes.stream().map(outcome -> outcome.error)
                .filter(java.util.Objects::nonNull).toList();
        long p95AllMillis = percentile95Millis(outcomes);
        long p95SuccessMillis = percentile95Millis(outcomes.stream()
                .filter(outcome -> outcome.view != null)
                .toList());
        String mysqlVersion = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        System.out.printf(Locale.ROOT,
                "RESERVATION_CONCURRENCY MySQL=%s Redis=127.0.0.1:6379 requests=100 "
                        + "success=%d conflicts409=%d errors=%d p95AllMs=%d p95SuccessMs=%d%n",
                mysqlVersion, succeeded, conflicts, errors.size(), p95AllMillis, p95SuccessMillis);

        assertThat(errors).isEmpty();
        assertThat(succeeded).isEqualTo(20);
        assertThat(conflicts).isEqualTo(80);
        assertThat(outcomes).hasSize(100);
        assertThat(outcomes.stream().filter(outcome -> outcome.view != null)
                .map(outcome -> outcome.view.status())).allMatch(ReservationStatus.APPROVED::equals);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation
                WHERE lab_id = ? AND allocation_mode = 'FIXED'
                  AND reservation_mode = 'SEAT' AND status = 'APPROVED'
                """, Integer.class, labId)).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat rs
                JOIN t_reservation r ON r.resv_id = rs.resv_id WHERE r.lab_id = ?
                """, Integer.class, labId)).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT rs.seat_id) FROM t_reservation_seat rs
                JOIN t_reservation r ON r.resv_id = rs.resv_id WHERE r.lab_id = ?
                """, Integer.class, labId)).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT r.resv_id
                    FROM t_reservation r
                    LEFT JOIN t_reservation_seat rs ON rs.resv_id = r.resv_id
                    WHERE r.lab_id = ? AND r.reservation_mode = 'SEAT' AND r.status = 'APPROVED'
                    GROUP BY r.resv_id HAVING COUNT(rs.id) <> 1
                ) invalid_reservations
                """, Integer.class, labId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat rs
                LEFT JOIN t_reservation r ON r.resv_id = rs.resv_id
                WHERE r.resv_id IS NULL
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log l
                JOIN t_reservation r ON r.resv_id = l.resv_id
                WHERE r.lab_id = ? AND l.from_status IS NULL AND l.to_status = 'APPROVED'
                """, Integer.class, labId)).isEqualTo(20);

        long expectedMask = TimeSlotMask.between(LocalTime.of(9, 0), LocalTime.of(10, 0)).value();
        Map<Long, Long> bitmapMasks = bitmapMasks(labId);
        Map<Long, Long> assignmentMasks = assignmentMasks(labId);
        assertThat(bitmapMasks).hasSize(20);
        assertThat(assignmentMasks).hasSize(20);
        assertThat(bitmapMasks).isEqualTo(assignmentMasks);
        assertThat(bitmapMasks.values()).allMatch(mask -> mask == expectedMask);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT mask FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, DATE)).isZero();
    }

    @Test
    void sameUserConcurrentAcrossLabsHasOnlyOneEffectiveReservation() throws Exception {
        long firstLabId = createLaboratory(4);
        long secondLabId = createLaboratory(4);
        long userId = userIdByAccount(createTestAccount("STUDENT"));

        List<Outcome> outcomes = compete(List.of(
                new Attempt(userId, firstLabId), new Attempt(userId, secondLabId)));

        assertThat(outcomes.stream().map(outcome -> outcome.error)
                .filter(java.util.Objects::nonNull)).isEmpty();
        assertThat(outcomes.stream().filter(outcome -> outcome.view != null)).hasSize(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.conflict != null
                && outcome.conflict.getStatus().value() == 409)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat rs
                JOIN t_reservation r ON r.resv_id = rs.resv_id WHERE r.user_id = ?
                """, Integer.class, userId)).isEqualTo(1);
    }

    private List<Outcome> compete(List<Attempt> attempts) throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(attempts.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (Attempt attempt : attempts) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    long started = System.nanoTime();
                    try {
                        ReservationView view = reservationService.create(
                                attempt.userId, fixedRequest(attempt.labId));
                        return new Outcome(System.nanoTime() - started, view, null, null);
                    } catch (BusinessException exception) {
                        return new Outcome(System.nanoTime() - started, null, exception, null);
                    } catch (Throwable exception) {
                        return new Outcome(System.nanoTime() - started, null, null, exception);
                    }
                }));
            }
            boolean allReady = ready.await(30, TimeUnit.SECONDS);
            start.countDown();
            assertThat(allReady).isTrue();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(90, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
        }
    }

    private CreateReservationRequest fixedRequest(long labId) {
        return new CreateReservationRequest(
                AllocationMode.FIXED, labId, DATE, "09:00", "10:00",
                null, null, null, ReservationMode.SEAT, 1, 1,
                "真实并发测试", ReservationPreferences.empty());
    }

    private Map<Long, Long> bitmapMasks(long labId) {
        return jdbcTemplate.query("""
                SELECT b.seat_id, b.mask FROM t_seat_bitmap b
                JOIN t_lab_seat s ON s.seat_id = b.seat_id
                WHERE s.lab_id = ? AND b.bitmap_date = ?
                """, resultSet -> {
            Map<Long, Long> values = new HashMap<>();
            while (resultSet.next()) {
                values.put(resultSet.getLong(1), resultSet.getLong(2));
            }
            return values;
        }, labId, DATE);
    }

    private Map<Long, Long> assignmentMasks(long labId) {
        return jdbcTemplate.query("""
                SELECT rs.seat_id, rs.time_mask FROM t_reservation_seat rs
                JOIN t_reservation r ON r.resv_id = rs.resv_id
                WHERE r.lab_id = ? AND r.request_date = ? AND r.status = 'APPROVED'
                """, resultSet -> {
            Map<Long, Long> values = new HashMap<>();
            while (resultSet.next()) {
                values.put(resultSet.getLong(1), resultSet.getLong(2));
            }
            return values;
        }, labId, DATE);
    }

    private static long percentile95Millis(List<Outcome> outcomes) {
        List<Long> sorted = outcomes.stream().map(outcome -> outcome.durationNanos)
                .sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return TimeUnit.NANOSECONDS.toMillis(sorted.get(index));
    }

    private record Attempt(long userId, long labId) {
    }

    private record Outcome(
            long durationNanos,
            ReservationView view,
            BusinessException conflict,
            Throwable error) {
    }
}
