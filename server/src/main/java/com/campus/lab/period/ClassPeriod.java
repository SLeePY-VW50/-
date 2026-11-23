package com.campus.lab.period;

import java.time.LocalTime;
import java.util.Objects;

public record ClassPeriod(int number, LocalTime start, LocalTime end, PeriodSession session) {

    public ClassPeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(session, "session");
        if (number < 1) {
            throw new IllegalArgumentException("节次编号必须从1开始");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("节次结束必须晚于开始");
        }
    }

    public int slot() {
        return number - 1;
    }

    public int lengthMinutes() {
        return end.getHour() * 60 + end.getMinute() - (start.getHour() * 60 + start.getMinute());
    }

    public String label() {
        return "第" + number + "节";
    }
}
