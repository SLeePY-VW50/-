package com.campus.lab.reservation.dto;

public record SlotRecommendationView(
        int startSlot,
        int endSlot,
        String startTime,
        String endTime,
        int durationPeriods) {
}
