package com.campus.lab.reservation;

import java.util.List;

public record SeatAllocationResult(
        boolean success,
        List<SeatAssignment> seats,
        List<String> reasons) {

    public SeatAllocationResult {
        seats = List.copyOf(seats);
        reasons = List.copyOf(reasons);
        if (!success && !seats.isEmpty()) {
            throw new IllegalArgumentException("失败结果不能包含部分分配");
        }
    }

    public static SeatAllocationResult success(List<SeatAssignment> seats) {
        return new SeatAllocationResult(true, seats, List.of());
    }

    public static SeatAllocationResult failure(String reason) {
        return new SeatAllocationResult(false, List.of(), List.of(reason));
    }
}
