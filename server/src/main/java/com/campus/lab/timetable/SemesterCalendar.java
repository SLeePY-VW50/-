package com.campus.lab.timetable;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Expands "weeks 3–16, Wednesday" from the Monday of week 1. */
public final class SemesterCalendar {

    private SemesterCalendar() {
    }

    public static List<LocalDate> dates(LocalDate week1Monday, int weekStart, int weekEnd, int dayOfWeek) {
        if (week1Monday == null || week1Monday.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("学期第一周必须从周一开始");
        }
        if (weekStart < 1 || weekEnd < weekStart || dayOfWeek < 1 || dayOfWeek > 7) {
            throw new IllegalArgumentException("周次或星期无效");
        }
        List<LocalDate> dates = new ArrayList<>();
        for (int week = weekStart; week <= weekEnd; week++) {
            dates.add(week1Monday.plusWeeks(week - 1L).plusDays(dayOfWeek - 1L));
        }
        return List.copyOf(dates);
    }
}
