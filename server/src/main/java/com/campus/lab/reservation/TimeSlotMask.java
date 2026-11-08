package com.campus.lab.reservation;

import java.time.LocalTime;
import java.util.Objects;

public record TimeSlotMask(int startSlot, int endSlot, long value) {

    public static final int SLOTS_PER_DAY = 48;
    public static final int MAX_SLOTS = 8;

    public TimeSlotMask {
        if (startSlot < 0 || endSlot > SLOTS_PER_DAY || endSlot <= startSlot) {
            throw new IllegalArgumentException("时间片范围无效");
        }
        if (endSlot - startSlot > MAX_SLOTS) {
            throw new IllegalArgumentException("预约时长不能超过4小时");
        }
        long expectedValue = maskValue(startSlot, endSlot);
        if (value != expectedValue) {
            throw new IllegalArgumentException("位图值必须与时间片范围完全一致");
        }
    }

    public static TimeSlotMask of(int startSlot, int endSlot) {
        return new TimeSlotMask(startSlot, endSlot, maskValue(startSlot, endSlot));
    }

    public static TimeSlotMask between(LocalTime start, LocalTime end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        validateAlignment(start);
        validateAlignment(end);
        int startSlot = slotOf(start);
        int endSlot = LocalTime.MIDNIGHT.equals(end) ? SLOTS_PER_DAY : slotOf(end);
        return of(startSlot, endSlot);
    }

    public boolean contains(TimeSlotMask other) {
        Objects.requireNonNull(other, "other");
        return (value & other.value) == other.value;
    }

    public boolean overlaps(TimeSlotMask other) {
        Objects.requireNonNull(other, "other");
        return (value & other.value) != 0;
    }

    private static long maskValue(int startSlot, int endSlot) {
        int length = endSlot - startSlot;
        if (length <= 0 || length > MAX_SLOTS) {
            return 0L;
        }
        return ((1L << length) - 1L) << startSlot;
    }

    private static void validateAlignment(LocalTime time) {
        if (time.getSecond() != 0 || time.getNano() != 0
                || (time.getMinute() != 0 && time.getMinute() != 30)) {
            throw new IllegalArgumentException("时间必须按30分钟对齐，且秒和纳秒必须为0");
        }
    }

    private static int slotOf(LocalTime time) {
        return time.getHour() * 2 + time.getMinute() / 30;
    }
}
