package com.campus.lab.resource;

import java.time.LocalTime;

public record EffectiveTimeRule(
        long ruleId,
        long labId,
        int dayOfWeek,
        LocalTime openTime,
        LocalTime closeTime,
        int minDurationMinutes,
        int maxDurationMinutes,
        int advanceMinDays,
        int advanceMaxDays) {

    public EffectiveTimeRule {
        RuleValidator.validateDurationRange(minDurationMinutes, maxDurationMinutes);
    }
}
