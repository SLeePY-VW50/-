package com.campus.lab.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BitmapConflictDetectorTest {

    private final BitmapConflictDetector bitmap = new BitmapConflictDetector();
    private final IntervalConflictDetector interval = new IntervalConflictDetector();

    @Test
    void roomAfterSeatsConflictsWithTheUnionOfEverySeatMask() {
        TimeSlotMask queryMask = TimeSlotMask.of(16, 18);
        ResourceSnapshot resource = new ResourceSnapshot(0, List.of(
                seat(1, 1, "GPU", ResourceStatus.ACTIVE, TimeSlotMask.of(16, 17).value()),
                seat(2, 2, "GPU", ResourceStatus.DISABLED, TimeSlotMask.of(17, 18).value()),
                seat(3, 3, "GPU", ResourceStatus.ACTIVE, TimeSlotMask.of(20, 21).value())));

        ConflictDecision decision = bitmap.detect(resource, roomQuery(queryMask));

        assertThat(decision.available()).isFalse();
        assertThat(decision.conflictMask()).isEqualTo(queryMask.value());
        assertThat(decision.conflictSlots()).containsExactly(16, 17);
        assertThat(decision.selectedSeats()).isEmpty();
        assertThat(decision.reasons()).contains("SEAT_TIME_CONFLICT");
        assertThat(interval.detect(resource, roomQuery(queryMask))).isEqualTo(decision);
    }

    @Test
    void seatsAfterWholeRoomConflictBeforeAnySeatSelection() {
        TimeSlotMask queryMask = TimeSlotMask.of(16, 18);
        ResourceSnapshot resource = new ResourceSnapshot(queryMask.value(), List.of(
                seat(1, 1, "GPU", ResourceStatus.ACTIVE, 0),
                seat(2, 2, "GPU", ResourceStatus.ACTIVE, 0)));

        ConflictDecision decision = bitmap.detect(resource, seatQuery(queryMask, 1, null));

        assertThat(decision.available()).isFalse();
        assertThat(decision.conflictMask()).isEqualTo(queryMask.value());
        assertThat(decision.conflictSlots()).containsExactly(16, 17);
        assertThat(decision.selectedSeats()).isEmpty();
        assertThat(decision.reasons()).contains("ROOM_TIME_CONFLICT");
        assertThat(interval.detect(resource, seatQuery(queryMask, 1, null))).isEqualTo(decision);
    }

    @Test
    void nonOverlappingRoomAndSeatRequestsAreAvailable() {
        TimeSlotMask queryMask = TimeSlotMask.of(16, 18);
        ResourceSnapshot resource = new ResourceSnapshot(
                TimeSlotMask.of(20, 22).value(),
                List.of(seat(1, 1, "GPU", ResourceStatus.ACTIVE,
                        TimeSlotMask.of(18, 20).value())));

        ConflictDecision roomDecision = bitmap.detect(resource, roomQuery(queryMask));
        ConflictDecision seatDecision = bitmap.detect(resource, seatQuery(queryMask, 1, null));

        assertThat(roomDecision.available()).isTrue();
        assertThat(roomDecision.conflictSlots()).isEmpty();
        assertThat(seatDecision.available()).isTrue();
        assertThat(seatDecision.selectedSeats()).extracting(SeatAssignment::seatId).containsExactly(1L);
        assertThat(interval.detect(resource, roomQuery(queryMask))).isEqualTo(roomDecision);
        assertThat(interval.detect(resource, seatQuery(queryMask, 1, null))).isEqualTo(seatDecision);
    }

    @Test
    void occupiedSeatIsSkippedWhenAnotherSeatIsFree() {
        TimeSlotMask queryMask = TimeSlotMask.of(16, 18);
        ResourceSnapshot resource = new ResourceSnapshot(0, List.of(
                seat(1, 1, "GPU", ResourceStatus.ACTIVE, queryMask.value()),
                seat(2, 2, "GPU", ResourceStatus.ACTIVE, 0)));

        ConflictDecision decision = bitmap.detect(resource, seatQuery(queryMask, 1, null));

        assertThat(decision.available()).isTrue();
        assertThat(decision.conflictMask()).isZero();
        assertThat(decision.selectedSeats()).extracting(SeatAssignment::seatId).containsExactly(2L);
        assertThat(interval.detect(resource, seatQuery(queryMask, 1, null))).isEqualTo(decision);
    }

    @Test
    void hardSpecShortageFailsWithoutPartialAllocation() {
        TimeSlotMask queryMask = TimeSlotMask.of(16, 18);
        ResourceSnapshot resource = new ResourceSnapshot(0, List.of(
                seat(1, 1, "STANDARD", ResourceStatus.ACTIVE, 0),
                seat(2, 2, "HIGH", ResourceStatus.ACTIVE, queryMask.value())));

        ConflictDecision decision = bitmap.detect(resource, seatQuery(queryMask, 1, "GPU"));

        assertThat(decision.available()).isFalse();
        assertThat(decision.selectedSeats()).isEmpty();
        assertThat(decision.reasons()).contains("INSUFFICIENT_AVAILABLE_SEATS");
        assertThat(interval.detect(resource, seatQuery(queryMask, 1, "GPU"))).isEqualTo(decision);
    }

    @Test
    void insufficientFreeSeatsExposeConflictSlotsButNeverPartialSeats() {
        TimeSlotMask queryMask = TimeSlotMask.of(16, 18);
        ResourceSnapshot resource = new ResourceSnapshot(0, List.of(
                seat(1, 1, "GPU", ResourceStatus.ACTIVE, TimeSlotMask.of(17, 18).value()),
                seat(2, 2, "GPU", ResourceStatus.ACTIVE, 0)));

        ConflictDecision decision = bitmap.detect(resource, seatQuery(queryMask, 2, null));

        assertThat(decision.available()).isFalse();
        assertThat(decision.conflictMask()).isEqualTo(TimeSlotMask.of(17, 18).value());
        assertThat(decision.conflictSlots()).containsExactly(17);
        assertThat(decision.selectedSeats()).isEmpty();
        assertThat(interval.detect(resource, seatQuery(queryMask, 2, null))).isEqualTo(decision);
    }

    private static ConflictQuery roomQuery(TimeSlotMask mask) {
        return new ConflictQuery(mask, ReservationMode.ROOM, 0, null, null, false);
    }

    private static ConflictQuery seatQuery(TimeSlotMask mask, int count, String minSpecLevel) {
        return new ConflictQuery(mask, ReservationMode.SEAT, count, minSpecLevel, null, false);
    }

    private static SeatSnapshot seat(
            long id, int index, String spec, ResourceStatus status, long mask) {
        return new SeatSnapshot(id, "S" + id, "A", index, spec, false, status, mask);
    }
}
