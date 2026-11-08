package com.campus.lab.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GreedySeatAllocatorTest {

    private final GreedySeatAllocator allocator = new GreedySeatAllocator();

    @Test
    void prefersContiguousSeatsInTheSameZone() {
        List<SeatSnapshot> seats = List.of(
                seat(3, "A", 3, "STANDARD", false, ResourceStatus.ACTIVE, 0),
                seat(1, "A", 1, "STANDARD", false, ResourceStatus.ACTIVE, 0),
                seat(2, "A", 2, "STANDARD", false, ResourceStatus.ACTIVE, 0),
                seat(4, "B", 4, "GPU", true, ResourceStatus.ACTIVE, 0));

        SeatAllocationResult result = allocator.allocate(seats, query(2, null, null, false));

        assertThat(result.success()).isTrue();
        assertThat(result.seats()).extracting(SeatAssignment::seatId).containsExactly(1L, 2L);
    }

    @Test
    void preferenceMatchingContiguousSegmentWinsOverHigherSpecFallback() {
        List<SeatSnapshot> seats = List.of(
                seat(1, "A", 1, "HIGH", true, ResourceStatus.ACTIVE, 0),
                seat(2, "A", 2, "HIGH", true, ResourceStatus.ACTIVE, 0),
                seat(3, "A", 3, "GPU", false, ResourceStatus.ACTIVE, 0),
                seat(4, "A", 4, "GPU", false, ResourceStatus.ACTIVE, 0));

        SeatAllocationResult result = allocator.allocate(seats, query(2, null, "A", true));

        assertThat(result.success()).isTrue();
        assertThat(result.seats()).extracting(SeatAssignment::seatId).containsExactly(1L, 2L);
        assertThat(result.seats()).allMatch(seat -> !seat.zoneMismatch() && !seat.nearWindowMismatch());
    }

    @Test
    void fallbackStillAllocatesKAndOrdersBySpecWindowAndIndex() {
        List<SeatSnapshot> seats = List.of(
                seat(1, "A", 1, "STANDARD", false, ResourceStatus.ACTIVE, 0),
                seat(2, "B", 2, "HIGH", true, ResourceStatus.ACTIVE, 0),
                seat(4, "A", 4, "GPU", false, ResourceStatus.ACTIVE, 0),
                seat(7, "B", 7, "STANDARD", true, ResourceStatus.ACTIVE, 0));

        SeatAllocationResult result = allocator.allocate(seats, query(3, null, "MISSING", true));

        assertThat(result.success()).isTrue();
        assertThat(result.seats()).extracting(SeatAssignment::seatId).containsExactly(4L, 2L, 7L);
    }

    @Test
    void hardFiltersRemoveOccupiedInactiveAndInsufficientSpecSeats() {
        long occupied = TimeSlotMask.of(16, 18).value();
        List<SeatSnapshot> seats = List.of(
                seat(1, "A", 1, "STANDARD", false, ResourceStatus.ACTIVE, 0),
                seat(2, "A", 2, "GPU", false, ResourceStatus.DISABLED, 0),
                seat(3, "A", 3, "GPU", false, ResourceStatus.MAINTENANCE, 0),
                seat(4, "A", 4, "GPU", false, ResourceStatus.ACTIVE, occupied),
                seat(5, "A", 5, "HIGH", false, ResourceStatus.ACTIVE, 0),
                seat(6, "A", 6, "GPU", false, ResourceStatus.ACTIVE, 0));

        SeatAllocationResult result = allocator.allocate(seats, query(2, "HIGH", null, false));

        assertThat(result.success()).isTrue();
        assertThat(result.seats()).extracting(SeatAssignment::seatId).containsExactly(5L, 6L);
    }

    @Test
    void insufficientCandidatesFailWithoutPartialAllocation() {
        List<SeatSnapshot> seats = List.of(
                seat(1, "A", 1, "HIGH", false, ResourceStatus.ACTIVE, 0),
                seat(2, "A", 2, "HIGH", false, ResourceStatus.ACTIVE, 0));

        SeatAllocationResult result = allocator.allocate(seats, query(3, "HIGH", null, false));

        assertThat(result.success()).isFalse();
        assertThat(result.seats()).isEmpty();
        assertThat(result.reasons()).contains("INSUFFICIENT_AVAILABLE_SEATS");
    }

    @Test
    void softPreferencesNeverCauseFailureAndMismatchIsReportedPerSeat() {
        List<SeatSnapshot> seats = List.of(
                seat(1, "A", 1, "STANDARD", false, ResourceStatus.ACTIVE, 0),
                seat(2, "B", 2, "HIGH", true, ResourceStatus.ACTIVE, 0));

        SeatAllocationResult result = allocator.allocate(seats, query(2, null, "A", true));

        assertThat(result.success()).isTrue();
        assertThat(result.seats()).hasSize(2);
        SeatAssignment high = result.seats().get(0);
        SeatAssignment standard = result.seats().get(1);
        assertThat(high.zoneMismatch()).isTrue();
        assertThat(high.nearWindowMismatch()).isFalse();
        assertThat(standard.zoneMismatch()).isFalse();
        assertThat(standard.nearWindowMismatch()).isTrue();
    }

    @Test
    void largeFragmentedInputFindsTheOnlyCompletePreferenceSegment() {
        int count = 64;
        int fragmentedGroups = 160;
        int fragmentedSeatCount = fragmentedGroups * (count - 1);
        List<SeatSnapshot> seats = new java.util.ArrayList<>();
        long seatId = 1;
        int seatIndex = 1;
        for (int group = 0; group < fragmentedGroups; group++) {
            for (int offset = 0; offset < count - 1; offset++) {
                seats.add(seat(seatId++, "FRAGMENT-" + group, seatIndex++,
                        "STANDARD", true, ResourceStatus.ACTIVE, 0));
            }
        }
        for (int index = 0; index < count; index++) {
            seats.add(seat(seatId++, "COMPLETE", seatIndex++,
                    "STANDARD", true, ResourceStatus.ACTIVE, 0));
        }

        SeatAllocationResult result = allocator.allocate(seats, query(count, null, null, true));

        List<Long> expectedIds = java.util.stream.LongStream
                .rangeClosed(fragmentedSeatCount + 1, fragmentedSeatCount + count)
                .boxed()
                .toList();
        assertThat(result.success()).isTrue();
        assertThat(result.seats()).hasSize(count);
        assertThat(result.seats()).extracting(SeatAssignment::seatId)
                .containsExactlyElementsOf(expectedIds);
        assertThat(result.seats()).allMatch(seat -> !seat.nearWindowMismatch());
    }

    private static ConflictQuery query(
            int count, String minSpecLevel, String preferredZone, boolean preferNearWindow) {
        return new ConflictQuery(
                TimeSlotMask.of(16, 18), ReservationMode.SEAT, count,
                minSpecLevel, preferredZone, preferNearWindow);
    }

    private static SeatSnapshot seat(
            long id, String zone, int index, String spec, boolean nearWindow,
            ResourceStatus status, long mask) {
        return new SeatSnapshot(id, "S" + id, zone, index, spec, nearWindow, status, mask);
    }
}
