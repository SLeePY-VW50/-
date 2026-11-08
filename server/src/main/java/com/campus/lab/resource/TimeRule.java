package com.campus.lab.resource;

import java.time.LocalTime;

public record TimeRule(
        long ruleId,
        long labId,
        int dayOfWeek,
        LocalTime openTime,
        LocalTime closeTime,
        Integer minDurationMinutes,
        Integer maxDurationMinutes,
        Integer advanceMinDays,
        Integer advanceMaxDays) {
}
