package com.campus.lab.statistics;

import java.time.LocalDate;
import java.util.List;

public final class StatisticsViews {

    private StatisticsViews() {
    }

    public record LabDateTarget(long labId, LocalDate date) {
    }

    public record DailyMetric(
            long labId, LocalDate date, double plannedUtilization, double actualUtilization,
            double noShowRate, long reservationCount, long availableSeatMinutes) {
    }

    public record Dashboard(
            LocalDate date, long labCount, long reservationCount, long pendingScheduleCount,
            long inUseCount, long approvedCount, long completedCount, long noShowCount,
            long cancelledCount, double plannedUtilization, double actualUtilization,
            double noShowRate, int creditScore, List<TrendDay> trend) {
    }

    public record TrendDay(
            LocalDate date, long reservationCount, long noShowCount,
            double plannedUtilization, double actualUtilization, double noShowRate) {
    }

    public record HeatmapDay(long labId, LocalDate date, List<HeatmapSlot> slots) {
    }

    public record HeatmapSlot(
            int slot, String start, String end, double plannedRatio, double actualRatio) {
    }
}
