package com.campus.lab.resource.dto;

import com.campus.lab.common.DayTimes;
import com.campus.lab.resource.EffectiveTimeRule;

public record TimeRuleView(
        long ruleId,
        long labId,
        int dayOfWeek,
        String openTime,
        String closeTime,
        int openStartPeriod,
        int openEndPeriod,
        int minPeriods,
        int maxPeriods,
        int advanceMinDays,
        int advanceMaxDays) {

    public static TimeRuleView from(EffectiveTimeRule rule) {
        return new TimeRuleView(
                rule.ruleId(), rule.labId(), rule.dayOfWeek(),
                DayTimes.format(rule.openTime(), false), DayTimes.format(rule.closeTime(), true),
                rule.openStartPeriod(), rule.openEndPeriod(),
                rule.minPeriods(), rule.maxPeriods(),
                rule.advanceMinDays(), rule.advanceMaxDays());
    }
}
