package com.campus.lab.resource;

import com.campus.lab.period.DongfangPeriodTable;
import java.time.LocalTime;

public record TimeRule(
        long ruleId,
        long labId,
        int dayOfWeek,
        LocalTime openTime,
        LocalTime closeTime,
        int openStartPeriod,
        int openEndPeriod,
        Integer minPeriods,
        Integer maxPeriods,
        Integer advanceMinDays,
        Integer advanceMaxDays) {
    public TimeRule {
        if (openStartPeriod < 1 || openEndPeriod < openStartPeriod) {
            throw new IllegalArgumentException("开放节次范围无效");
        }
    }

    public static int defaultOpenStart() {
        return DongfangPeriodTable.DEFAULT_OPEN_START;
    }

    public static int defaultOpenEnd() {
        return DongfangPeriodTable.DEFAULT_OPEN_END;
    }
}
