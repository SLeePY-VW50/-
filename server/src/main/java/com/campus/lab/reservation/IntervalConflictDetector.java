package com.campus.lab.reservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class IntervalConflictDetector {

    private final GreedySeatAllocator allocator = new GreedySeatAllocator();

    public ConflictDecision detect(ResourceSnapshot resource, ConflictQuery query) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(query, "query");
        long roomConflict = roomConflict(resource.roomMask(), query.timeMask());
        if (query.mode() == ReservationMode.SEAT) {
            return detectSeats(resource, query, roomConflict);
        }

        long conflict = roomConflict;
        long seatConflict = 0;
        boolean roomReason = roomConflict != 0;
        boolean seatReason = false;
        for (int slot = query.timeMask().startSlot(); slot < query.timeMask().endSlot(); slot++) {
            for (SeatSnapshot seat : resource.seats()) {
                if (occupied(seat.mask(), slot)) {
                    conflict |= 1L << slot;
                    seatConflict |= 1L << slot;
                    seatReason = true;
                    break;
                }
            }
        }
        if (conflict == 0) {
            return ConflictDecision.success(List.of());
        }
        List<String> reasons = new ArrayList<>();
        if (roomReason) {
            reasons.add("ROOM_TIME_CONFLICT");
        }
        if (seatReason) {
            reasons.add("SEAT_TIME_CONFLICT");
        }
        return ConflictDecision.failure(conflict, reasons);
    }

    private ConflictDecision detectSeats(
            ResourceSnapshot resource, ConflictQuery query, long roomConflict) {
        if (roomConflict != 0) {
            return ConflictDecision.failure(roomConflict, List.of("ROOM_TIME_CONFLICT"));
        }
        List<SeatSnapshot> intervalCheckedSeats = resource.seats().stream()
                .map(seat -> new SeatSnapshot(
                        seat.seatId(), seat.seatCode(), seat.zoneCode(), seat.seatIndex(),
                        seat.specLevel(), seat.nearWindow(), seat.status(),
                        overlaps(seat.mask(), query.timeMask()) ? seat.mask() : 0))
                .toList();
        SeatAllocationResult allocation = allocator.allocate(intervalCheckedSeats, query);
        if (allocation.success()) {
            return ConflictDecision.success(allocation.seats());
        }
        long seatConflict = 0;
        for (SeatSnapshot seat : resource.seats()) {
            for (int slot = query.timeMask().startSlot(); slot < query.timeMask().endSlot(); slot++) {
                if (occupied(seat.mask(), slot)) {
                    seatConflict |= 1L << slot;
                }
            }
        }
        List<String> reasons = new ArrayList<>(allocation.reasons());
        if (seatConflict != 0) {
            reasons.add("SEAT_TIME_CONFLICT");
        }
        return ConflictDecision.failure(seatConflict, reasons);
    }

    private static long roomConflict(long roomMask, TimeSlotMask queryMask) {
        long conflict = 0;
        for (int slot = queryMask.startSlot(); slot < queryMask.endSlot(); slot++) {
            if (occupied(roomMask, slot)) {
                conflict |= 1L << slot;
            }
        }
        return conflict;
    }

    private static boolean overlaps(long mask, TimeSlotMask queryMask) {
        for (int slot = queryMask.startSlot(); slot < queryMask.endSlot(); slot++) {
            if (occupied(mask, slot)) {
                return true;
            }
        }
        return false;
    }

    private static boolean occupied(long mask, int slot) {
        return (mask & (1L << slot)) != 0;
    }
}
