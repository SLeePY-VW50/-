package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.AllocationMode;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationService;
import com.campus.lab.reservation.TimeSlotMask;
import com.campus.lab.reservation.dto.CreateReservationRequest;
import com.campus.lab.reservation.dto.ReservationPreferences;
import com.campus.lab.reservation.dto.ReservationView;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ReservationConflictIntegrationTest extends AbstractReservationNonTransactionalTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Autowired
    private ReservationService reservationService;

    private long labId;
    private long userId;

    @BeforeEach
    void createIndependentFixture() {
        labId = createLaboratory(4);
        userId = userIdByAccount(createTestAccount("STUDENT"));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void seatAfterRoomReturns409AndKeepsCommittedRoomOccupancy() {
        ReservationView room = reservationService.create(userId, request(ReservationMode.ROOM, 4));
        OccupancySnapshot before = snapshot(room.resvId());
        long expectedMask = TimeSlotMask.between(LocalTime.of(9, 0), LocalTime.of(10, 0)).value();
        assertThat(before.roomMask()).isEqualTo(expectedMask);
        assertThat(before.roomVersion()).isEqualTo(1);
        assertThat(before.assignments()).hasSize(4);
        assertThat(before.seats().values()).allMatch(seat -> seat.mask() == 0 && seat.version() == 0);
        long otherUserId = userIdByAccount(createTestAccount("STUDENT"));

        assertConflict(otherUserId, request(ReservationMode.SEAT, 1));

        assertThat(snapshot(room.resvId())).isEqualTo(before);
    }

    @Test
    void roomAfterSeatReturns409AndKeepsCommittedSeatOccupancy() {
        ReservationView seat = reservationService.create(userId, request(ReservationMode.SEAT, 2));
        OccupancySnapshot before = snapshot(seat.resvId());
        long expectedMask = TimeSlotMask.between(LocalTime.of(9, 0), LocalTime.of(10, 0)).value();
        assertThat(before.roomMask()).isZero();
        assertThat(before.roomVersion()).isZero();
        assertThat(before.assignments()).hasSize(2);
        assertThat(before.seats().values().stream().filter(value -> value.mask() == expectedMask))
                .hasSize(2);
        assertThat(before.seats().values().stream().filter(value -> value.mask() == 0)).hasSize(2);
        long otherUserId = userIdByAccount(createTestAccount("TEACHER"));

        assertConflict(otherUserId, request(ReservationMode.ROOM, 4));

        assertThat(snapshot(seat.resvId())).isEqualTo(before);
    }

    private void assertConflict(long otherUserId, CreateReservationRequest request) {
        assertThatThrownBy(() -> reservationService.create(otherUserId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).getStatus().value()).isEqualTo(409));
    }

    private CreateReservationRequest request(ReservationMode mode, int count) {
        return new CreateReservationRequest(
                AllocationMode.FIXED, labId, DATE, "09:00", "10:00",
                null, null, null, mode, count, mode == ReservationMode.SEAT ? count : null,
                "独立事务双向冲突测试", ReservationPreferences.empty());
    }

    private OccupancySnapshot snapshot(long originalReservationId) {
        int reservations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE lab_id = ? AND request_date = ?",
                Integer.class, labId, DATE);
        int assignments = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_seat rs
                JOIN t_reservation r ON r.resv_id = rs.resv_id
                WHERE r.lab_id = ? AND r.request_date = ?
                """, Integer.class, labId, DATE);
        int logs = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log l
                JOIN t_reservation r ON r.resv_id = l.resv_id
                WHERE r.lab_id = ? AND r.request_date = ?
                """, Integer.class, labId, DATE);
        long roomMask = jdbcTemplate.queryForObject(
                "SELECT mask FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, DATE);
        long roomVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM t_slot_bitmap WHERE lab_id = ? AND bitmap_date = ?",
                Long.class, labId, DATE);
        Map<Long, SeatState> seats = jdbcTemplate.query("""
                SELECT s.seat_id, b.mask, b.version FROM t_lab_seat s
                JOIN t_seat_bitmap b ON b.seat_id = s.seat_id AND b.bitmap_date = ?
                WHERE s.lab_id = ? ORDER BY s.seat_id
                """, resultSet -> {
            Map<Long, SeatState> values = new HashMap<>();
            while (resultSet.next()) {
                values.put(resultSet.getLong(1),
                        new SeatState(resultSet.getLong(2), resultSet.getLong(3)));
            }
            return values;
        }, DATE, labId);
        Map<Long, Long> originalAssignments = jdbcTemplate.query(
                "SELECT seat_id, time_mask FROM t_reservation_seat WHERE resv_id = ?", resultSet -> {
                    Map<Long, Long> values = new HashMap<>();
                    while (resultSet.next()) {
                        values.put(resultSet.getLong(1), resultSet.getLong(2));
                    }
                    return values;
                }, originalReservationId);
        return new OccupancySnapshot(
                reservations, assignments, logs, roomMask, roomVersion, seats, originalAssignments);
    }

    private record SeatState(long mask, long version) {
    }

    private record OccupancySnapshot(
            int reservationCount,
            int assignmentCount,
            int logCount,
            long roomMask,
            long roomVersion,
            Map<Long, SeatState> seats,
            Map<Long, Long> originalAssignments) {

        private OccupancySnapshot {
            seats = Map.copyOf(seats);
            originalAssignments = Map.copyOf(originalAssignments);
        }

        private Map<Long, Long> assignments() {
            return originalAssignments;
        }
    }
}
