package com.campus.lab.resource;

public record ReservationRuleConfig(
        int minPeriods,
        int maxPeriods,
        int advanceMinDays,
        int advanceMaxDays) {

    public ReservationRuleConfig {
        RuleValidator.validatePeriodRange(minPeriods, maxPeriods);
    }
}
