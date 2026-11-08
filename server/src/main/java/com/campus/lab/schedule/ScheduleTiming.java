package com.campus.lab.schedule;

public record ScheduleTiming(long phase1Nanos, long phase2Nanos) {

    public ScheduleTiming {
        if (phase1Nanos < 0 || phase2Nanos < 0) {
            throw new IllegalArgumentException("阶段耗时不能为负数");
        }
    }

    public long totalNanos() {
        return phase1Nanos + phase2Nanos;
    }
}
