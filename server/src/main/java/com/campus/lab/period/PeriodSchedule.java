package com.campus.lab.period;

import com.campus.lab.common.DayTimes;
import com.campus.lab.reservation.TimeSlotMask;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One lab day's bookable grid. Slot {@code n} is period {@code n + 1}.
 * Breaks are gaps between periods and are not slots.
 */
public record PeriodSchedule(long periodSetId, String code, String name, List<ClassPeriod> periods) {

    public PeriodSchedule {
        code = Objects.requireNonNull(code, "code");
        name = Objects.requireNonNull(name, "name");
        periods = List.copyOf(Objects.requireNonNull(periods, "periods"));
        if (periods.isEmpty() || periods.size() > TimeSlotMask.MAX_BITS) {
            throw new IllegalArgumentException("节次数量必须在1到" + TimeSlotMask.MAX_BITS + "之间");
        }
        for (int index = 0; index < periods.size(); index++) {
            ClassPeriod period = periods.get(index);
            if (period.number() != index + 1) {
                throw new IllegalArgumentException("节次编号必须从1连续排列");
            }
            if (index > 0 && period.start().isBefore(periods.get(index - 1).end())) {
                throw new IllegalArgumentException("节次时间不能重叠");
            }
        }
    }

    public int slotCount() {
        return periods.size();
    }

    public ClassPeriod periodAt(int slot) {
        if (slot < 0 || slot >= periods.size()) {
            throw new IllegalArgumentException("节次下标无效");
        }
        return periods.get(slot);
    }

    public LocalTime startTime(int slot) {
        return periodAt(slot).start();
    }

    public LocalTime endTime(int exclusiveEndSlot) {
        if (exclusiveEndSlot <= 0 || exclusiveEndSlot > periods.size()) {
            throw new IllegalArgumentException("节次下标无效");
        }
        return periods.get(exclusiveEndSlot - 1).end();
    }

    public TimeSlotMask between(LocalTime start, LocalTime end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        int startSlot = indexOfStart(start);
        int endSlot = indexOfEnd(end);
        if (startSlot < 0 || endSlot < 0 || endSlot <= startSlot) {
            throw new IllegalArgumentException("预约起止必须落在节次的开始和结束");
        }
        return TimeSlotMask.of(startSlot, endSlot);
    }

    public int indexOfStart(LocalTime time) {
        for (ClassPeriod period : periods) {
            if (period.start().equals(time)) {
                return period.slot();
            }
        }
        return -1;
    }

    public int indexOfEnd(LocalTime time) {
        for (ClassPeriod period : periods) {
            if (period.end().equals(time)) {
                return period.slot() + 1;
            }
        }
        return -1;
    }

    /**
     * First slot whose period has not started yet. A period that contains {@code now}
     * stays occupied; a break releases the following period.
     */
    public int firstReleasableSlot(LocalTime now) {
        Objects.requireNonNull(now, "now");
        for (ClassPeriod period : periods) {
            if (!now.isBefore(period.start()) && now.isBefore(period.end())) {
                return period.slot() + 1;
            }
            if (now.isBefore(period.start())) {
                return period.slot();
            }
        }
        return periods.size();
    }

    public long bookableMask(LocalTime openTime, LocalTime closeTime, int openStartPeriod, int openEndPeriod) {
        int openMinute = minute(openTime);
        int closeMinute = DayTimes.minuteOfDay(closeTime, true);
        long mask = 0;
        for (ClassPeriod period : periods) {
            if (period.number() < openStartPeriod || period.number() > openEndPeriod) {
                continue;
            }
            int start = minute(period.start());
            int end = minute(period.end());
            if (start >= openMinute && end <= closeMinute) {
                mask |= 1L << period.slot();
            }
        }
        return mask;
    }

    public long overlapMask(LocalTime start, LocalTime end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        long mask = 0;
        for (ClassPeriod period : periods) {
            if (start.isBefore(period.end()) && end.isAfter(period.start())) {
                mask |= 1L << period.slot();
            }
        }
        return mask;
    }

    public List<TimeSlotMask> placements(
            int windowStartMinute, int windowEndMinute, int periodCount, long openMask) {
        if (periodCount < 1 || windowEndMinute <= windowStartMinute) {
            return List.of();
        }
        List<TimeSlotMask> found = new ArrayList<>();
        for (int start = 0; start + periodCount <= periods.size(); start++) {
            ClassPeriod first = periods.get(start);
            ClassPeriod last = periods.get(start + periodCount - 1);
            if (minute(first.start()) < windowStartMinute || minute(last.end()) > windowEndMinute) {
                continue;
            }
            TimeSlotMask mask = TimeSlotMask.of(start, start + periodCount);
            if ((openMask & mask.value()) == mask.value()) {
                found.add(mask);
            }
        }
        return List.copyOf(found);
    }

    public String format(LocalTime time) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
    }

    private static int minute(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
