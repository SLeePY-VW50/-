package com.campus.lab.reservation;

import java.util.Objects;

/**
 * Contiguous occupancy bits. One bit is one class period; the period table,
 * not this mask, decides how many periods a day has and which clock times they cover.
 */
public record TimeSlotMask(int startSlot, int endSlot, long value) {

    /** Signed long bit operations stay defined for bits 0 through 62. */
    public static final int MAX_BITS = 63;

    public TimeSlotMask {
        if (startSlot < 0 || endSlot > MAX_BITS || endSlot <= startSlot) {
            throw new IllegalArgumentException("节次范围无效");
        }
        long expectedValue = maskValue(startSlot, endSlot);
        if (value != expectedValue) {
            throw new IllegalArgumentException("位图值必须与节次范围完全一致");
        }
    }

    public static TimeSlotMask of(int startSlot, int endSlot) {
        return new TimeSlotMask(startSlot, endSlot, maskValue(startSlot, endSlot));
    }

    public int periodCount() {
        return endSlot - startSlot;
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
        if (length <= 0 || startSlot < 0 || endSlot > MAX_BITS) {
            return 0L;
        }
        return ((1L << length) - 1L) << startSlot;
    }
}
