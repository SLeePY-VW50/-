package com.campus.lab.resource;

import java.time.LocalTime;

public record EffectiveTimeRule(
        long ruleId,
        long labId,
        int dayOfWeek,
        LocalTime openTime,
        LocalTime closeTime,
        int openStartPeriod,
        int openEndPeriod,
        int minPeriods,
        int maxPeriods,
        int advanceMinDays,
        int advanceMaxDays) {

    public EffectiveTimeRule {
        if (openStartPeriod < 1 || openEndPeriod < openStartPeriod) {
            throw new IllegalArgumentException("开放节次范围无效");
        }
        RuleValidator.validatePeriodRange(minPeriods, maxPeriods);
    }
}
