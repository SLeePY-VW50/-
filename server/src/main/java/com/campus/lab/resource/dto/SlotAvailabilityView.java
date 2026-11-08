package com.campus.lab.resource.dto;

public record SlotAvailabilityView(
        int index,
        String start,
        String end,
        boolean roomOccupied,
        int occupiedSeatCount,
        int availableSeatCount) {
}
