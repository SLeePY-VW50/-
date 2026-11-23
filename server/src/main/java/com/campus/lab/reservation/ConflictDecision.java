package com.campus.lab.reservation;

import java.util.ArrayList;
import java.util.List;

public record ConflictDecision(
        boolean available,
        long conflictMask,
        List<Integer> conflictSlots,
        List<SeatAssignment> selectedSeats,
        List<String> reasons) {

    public ConflictDecision {
        conflictSlots = List.copyOf(conflictSlots);
        selectedSeats = List.copyOf(selectedSeats);
        reasons = List.copyOf(reasons);
        if (!available && !selectedSeats.isEmpty()) {
            throw new IllegalArgumentException("冲突失败结果不能包含部分分配");
        }
    }

    public static ConflictDecision success(List<SeatAssignment> selectedSeats) {
        return new ConflictDecision(true, 0, List.of(), selectedSeats, List.of());
    }

    public static ConflictDecision failure(long conflictMask, List<String> reasons) {
        List<Integer> slots = new ArrayList<>();
        long remaining = conflictMask;
        int slot = 0;
        while (remaining != 0) {
            if ((remaining & 1L) != 0) {
                slots.add(slot);
            }
            remaining >>>= 1;
            slot++;
        }
        return new ConflictDecision(false, conflictMask, slots, List.of(), reasons);
    }
}
