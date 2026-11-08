package com.campus.lab.reservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class BitmapConflictDetector {

    private final GreedySeatAllocator allocator = new GreedySeatAllocator();

    public ConflictDecision detect(ResourceSnapshot resource, ConflictQuery query) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(query, "query");
        long requested = query.timeMask().value();
        long roomConflict = requested & resource.roomMask();
        if (query.mode() == ReservationMode.SEAT) {
            return detectSeats(resource, query, roomConflict);
        }

        long seatConflict = requested & unionSeatMasks(resource.seats());
        long conflict = roomConflict | seatConflict;
        if (conflict == 0) {
            return ConflictDecision.success(List.of());
        }
        List<String> reasons = new ArrayList<>();
        if (roomConflict != 0) {
            reasons.add("ROOM_TIME_CONFLICT");
        }
        if (seatConflict != 0) {
            reasons.add("SEAT_TIME_CONFLICT");
        }
        return ConflictDecision.failure(conflict, reasons);
    }

    private ConflictDecision detectSeats(
            ResourceSnapshot resource, ConflictQuery query, long roomConflict) {
        if (roomConflict != 0) {
            return ConflictDecision.failure(roomConflict, List.of("ROOM_TIME_CONFLICT"));
        }
        SeatAllocationResult allocation = allocator.allocate(resource.seats(), query);
        if (allocation.success()) {
            return ConflictDecision.success(allocation.seats());
        }
        long seatConflict = query.timeMask().value() & unionSeatMasks(resource.seats());
        List<String> reasons = new ArrayList<>(allocation.reasons());
        if (seatConflict != 0) {
            reasons.add("SEAT_TIME_CONFLICT");
        }
        return ConflictDecision.failure(seatConflict, reasons);
    }

    private static long unionSeatMasks(List<SeatSnapshot> seats) {
        long union = 0;
        for (SeatSnapshot seat : seats) {
            union |= seat.mask();
        }
        return union;
    }
}
