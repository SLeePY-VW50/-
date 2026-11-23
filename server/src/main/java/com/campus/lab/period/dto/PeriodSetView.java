package com.campus.lab.period.dto;

import com.campus.lab.period.ClassPeriod;
import com.campus.lab.period.PeriodSchedule;
import com.campus.lab.period.PeriodSession;
import java.util.List;

public record PeriodSetView(
        long periodSetId,
        String code,
        String name,
        List<PeriodView> periods) {

    public static PeriodSetView from(PeriodSchedule schedule) {
        return new PeriodSetView(
                schedule.periodSetId(), schedule.code(), schedule.name(),
                schedule.periods().stream().map(PeriodView::from).toList());
    }

    public record PeriodView(int periodNo, String start, String end, PeriodSession session) {
        static PeriodView from(ClassPeriod period) {
            return new PeriodView(
                    period.number(),
                    String.format("%02d:%02d", period.start().getHour(), period.start().getMinute()),
                    String.format("%02d:%02d", period.end().getHour(), period.end().getMinute()),
                    period.session());
        }
    }
}
