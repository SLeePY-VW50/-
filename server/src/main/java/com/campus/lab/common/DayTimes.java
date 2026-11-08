package com.campus.lab.common;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;

public final class DayTimes {

    private static final int MINUTES_PER_DAY = 24 * 60;

    private DayTimes() {
    }

    public static LocalTime parse(String value, boolean endOfDayAllowed) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("时间不能为空");
        }
        if (endOfDayAllowed && (value.equals("24:00") || value.equals("24:00:00"))) {
            return LocalTime.MIDNIGHT;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("时间必须使用HH:mm或HH:mm:ss格式");
        }
    }

    public static LocalTime parseDatabaseTime(String value) {
        return value == null ? null : parse(value, true);
    }

    public static int minuteOfDay(LocalTime time, boolean endOfDay) {
        Objects.requireNonNull(time, "time");
        return endOfDay && LocalTime.MIDNIGHT.equals(time)
                ? MINUTES_PER_DAY : time.getHour() * 60 + time.getMinute();
    }

    public static String format(LocalTime time, boolean endOfDay) {
        Objects.requireNonNull(time, "time");
        if (endOfDay && LocalTime.MIDNIGHT.equals(time)) {
            return "24:00:00";
        }
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                time.getHour(), time.getMinute(), time.getSecond());
    }
}
