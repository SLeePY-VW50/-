package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.AllocationMode;
import com.campus.lab.reservation.NormalizedReservation;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationRepository;
import com.campus.lab.reservation.ReservationTransactionService;
import com.campus.lab.reservation.TimeSlotMask;
import com.campus.lab.reservation.dto.ReservationPreferences;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ReservationSnapshotIsolationIntegrationTest extends AbstractReservationNonTransactionalTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Autowired
    private ReservationTransactionService transactionService;

    @MockitoSpyBean
    private ReservationRepository reservationRepository;

    private final CountDownLatch seatCommitted = new CountDownLatch(1);

    private long labId;
    private long seatUserId;
    private long roomUserId;
    private Thread roomThread;
    private FutureTask<Outcome> roomTask;

    @BeforeEach
    void createIndependentFixture() {
        labId = createLaboratory(2);
        seatUserId = userIdByAccount(createTestAccount("STUDENT"));
        roomUserId = userIdByAccount(createTestAccount("STUDENT"));
    }

    @AfterEach
    void releaseThreadAndResetSpy() throws InterruptedException {
        try {
            seatCommitted.countDown();
            if (roomTask != null) {
                roomTask.cancel(true);
            }
            if (roomThread != null) {
                roomThread.interrupt();
                roomThread.join(TimeUnit.SECONDS.toMillis(30));
                assertThat(roomThread.isAlive()).isFalse();
            }
        } finally {
            seatCommitted.countDown();
            Mockito.reset(reservationRepository);
        }
    }

    @Test
    void roomMustSeeSeatCommittedAfterItsRepeatableReadSnapshotWasOpened() throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualTo("REPEATABLE-READ");
        jdbcTemplate.update(
                "INSERT INTO t_slot_bitmap (lab_id, bitmap_date, mask, version) VALUES (?, ?, 0, 0)",
                labId, DATE);
        jdbcTemplate.update("""
                INSERT INTO t_seat_bitmap (seat_id, bitmap_date, mask, version)
                SELECT seat_id, ?, 0, 0 FROM t_lab_seat WHERE lab_id = ?
                """, DATE, labId);
        CountDownLatch roomAtLabBoundary = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("ROOM-SNAPSHOT")) {
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_seat_bitmap", Integer.class);
                roomAtLabBoundary.countDown();
                assertThat(seatCommitted.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(reservationRepository).ensureLabBitmap(anyLong(), any(LocalDate.class));

        roomTask = new FutureTask<>(() -> {
            try {
                long reservationId = transactionService.createFixed(roomUserId, request(ReservationMode.ROOM));
                return new Outcome(reservationId, null, null);
            } catch (BusinessException exception) {
                return new Outcome(null, exception, null);
            } catch (Throwable exception) {
                return new Outcome(null, null, exception);
            }
        });
        roomThread = new Thread(roomTask, "ROOM-SNAPSHOT");
        roomThread.start();

        long seatReservationId;
        try {
            assertThat(roomAtLabBoundary.await(30, TimeUnit.SECONDS)).isTrue();
            seatReservationId = transactionService.createFixed(seatUserId, request(ReservationMode.SEAT));
        } finally {
            seatCommitted.countDown();
        }
        Outcome roomOutcome = roomTask.get(30, TimeUnit.SECONDS);
        roomThread.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(roomThread.isAlive()).isFalse();
        System.out.printf(Locale.ROOT,
                "SNAPSHOT_INTERLEAVING roomReservationId=%s roomStatus=%s roomMessage=%s%n",
                roomOutcome.reservationId(),
                roomOutcome.conflict() == null ? "NONE" : roomOutcome.conflict().getStatus().value(),
                roomOutcome.conflict() == null ? "NONE" : roomOutcome.conflict().getMessage());

        assertThat(roomOutcome.error()).isNull();
        assertThat(roomOutcome.conflict())
                .as("ROOM must reject the SEAT committed after its snapshot; reservationId=%s",
                        roomOutcome.reservationId())
                .isNotNull();
        assertThat(roomOutcome.conflict().getStatus().value()).isEqualTo(409);
        assertThat(roomOutcome.reservationId()).isNull();

        long expectedMask = TimeSlotMask.between(LocalTime.of(9, 0), LocalTime.of(10, 0)).value();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE lab_id = ?", Integer.class, labId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE user_id = ?", Integer.class, roomUserId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation_seat WHERE resv_id = ?",
                Integer.class, seatReservationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT b.mask FROM t_seat_bitmap b
                JOIN t_reservation_seat rs ON rs.seat_id = b.seat_id
                WHERE rs.resv_id = ? AND b.bitmap_date = ?
                """, Long.class, seatReservationId, DATE)).isEqualTo(expectedMask);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT b.version FROM t_seat_bitmap b
                JOIN t_reservation_seat rs ON rs.seat_id = b.seat_id
                WHERE rs.resv_id = ? AND b.bitmap_date = ?
                """, Long.class, seatReservationId, DATE)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT mask FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, DATE)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, DATE)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log l
                JOIN t_reservation r ON r.resv_id = l.resv_id WHERE r.lab_id = ?
                """, Integer.class, labId)).isEqualTo(1);
    }

    @Test
    void abandonedRoomThreadIsInterruptedBeforeFixtureCleanup() {
        CountDownLatch abandoned = new CountDownLatch(1);
        roomTask = new FutureTask<>(() -> {
            try {
                abandoned.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new Outcome(null, null, null);
        });
        roomThread = new Thread(roomTask, "ROOM-SNAPSHOT");
        roomThread.setDaemon(true);
        roomThread.start();
    }

    private NormalizedReservation request(ReservationMode mode) {
        LocalTime start = LocalTime.of(9, 0);
        LocalTime end = LocalTime.of(10, 0);
        int attendees = mode == ReservationMode.ROOM ? 2 : 1;
        return new NormalizedReservation(
                AllocationMode.FIXED, labId, DATE, start, end, 60, mode, attendees,
                mode == ReservationMode.SEAT ? 1 : null, "确定性快照隔离测试",
                ReservationPreferences.empty(), TimeSlotMask.between(start, end));
    }

    private record Outcome(Long reservationId, BusinessException conflict, Throwable error) {
    }
}
