package com.campus.lab.resource;

public record ReservationRuleConfig(
        int minDurationMinutes,
        int maxDurationMinutes,
        int advanceMinDays,
        int advanceMaxDays) {

    public ReservationRuleConfig {
        RuleValidator.validateDurationRange(minDurationMinutes, maxDurationMinutes);
    }
}
