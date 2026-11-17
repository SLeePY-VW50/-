package com.campus.lab.reservation.dto;

import com.campus.lab.reservation.RecommendationOutcome;
import java.util.List;

public record SlotRecommendationsView(
        RecommendationOutcome outcome,
        List<SlotRecommendationView> recommendations) {

    public SlotRecommendationsView {
        recommendations = List.copyOf(recommendations);
    }
}
