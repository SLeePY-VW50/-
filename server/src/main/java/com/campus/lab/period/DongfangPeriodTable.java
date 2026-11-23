package com.campus.lab.period;

import java.time.LocalTime;
import java.util.List;

/** Official Dongfang College 2026–2027 teaching periods. The database seed is the runtime source. */
public final class DongfangPeriodTable {

    public static final String CODE = "DONGFANG-2026-2027";
    public static final int DEFAULT_OPEN_START = 1;
    public static final int DEFAULT_OPEN_END = 10;
    public static final int DEFAULT_MIN_PERIODS = 1;
    public static final int DEFAULT_MAX_PERIODS = 5;

    private DongfangPeriodTable() {
    }

    public static PeriodSchedule schedule() {
        return new PeriodSchedule(0, CODE, "浙江财经大学东方学院2026-2027学年教学作息", List.of(
                period(1, "08:30", "09:15", PeriodSession.MORNING),
                period(2, "09:15", "10:00", PeriodSession.MORNING),
                period(3, "10:05", "10:50", PeriodSession.MORNING),
                period(4, "11:00", "11:45", PeriodSession.MORNING),
                period(5, "11:45", "12:30", PeriodSession.MORNING),
                period(6, "13:30", "14:15", PeriodSession.AFTERNOON),
                period(7, "14:15", "15:00", PeriodSession.AFTERNOON),
                period(8, "15:05", "15:50", PeriodSession.AFTERNOON),
                period(9, "16:00", "16:45", PeriodSession.AFTERNOON),
                period(10, "16:45", "17:30", PeriodSession.AFTERNOON),
                period(11, "18:30", "19:15", PeriodSession.EVENING),
                period(12, "19:15", "20:00", PeriodSession.EVENING),
                period(13, "20:05", "20:50", PeriodSession.EVENING)));
    }

    private static ClassPeriod period(int number, String start, String end, PeriodSession session) {
        return new ClassPeriod(number, LocalTime.parse(start), LocalTime.parse(end), session);
    }
}
