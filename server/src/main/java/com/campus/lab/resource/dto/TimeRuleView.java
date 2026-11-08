package com.campus.lab.resource.dto;

import com.campus.lab.common.DayTimes;
import com.campus.lab.resource.EffectiveTimeRule;

public record TimeRuleView(
        long ruleId,
        long labId,
        int dayOfWeek,
        String openTime,
        String closeTime,
        int minDurationMinutes,
        int maxDurationMinutes,
        int advanceMinDays,
        int advanceMaxDays) {

    public static TimeRuleView from(EffectiveTimeRule rule) {
        return new TimeRuleView(
                rule.ruleId(), rule.labId(), rule.dayOfWeek(),
                DayTimes.format(rule.openTime(), false), DayTimes.format(rule.closeTime(), true),
                rule.minDurationMinutes(), rule.maxDurationMinutes(),
                rule.advanceMinDays(), rule.advanceMaxDays());
    }
}
