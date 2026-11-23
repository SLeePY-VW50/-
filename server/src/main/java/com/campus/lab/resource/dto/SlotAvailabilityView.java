package com.campus.lab.resource.dto;

public record SlotAvailabilityView(
        int index,
        int periodNo,
        String session,
        String label,
        String start,
        String end,
        boolean bookable,
        boolean roomOccupied,
        int occupiedSeatCount,
        int availableSeatCount) {
}
