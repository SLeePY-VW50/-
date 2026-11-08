package com.campus.lab.reservation;

import java.util.List;

public record ResourceSnapshot(long roomMask, List<SeatSnapshot> seats) {

    public ResourceSnapshot {
        seats = List.copyOf(seats);
    }
}
