package com.campus.lab.schedule;

final class ScheduleSpecRank {

    private ScheduleSpecRank() {
    }

    static int of(String specLevel) {
        if (specLevel == null) {
            return 0;
        }
        return switch (specLevel) {
            case "STANDARD" -> 1;
            case "HIGH" -> 2;
            case "GPU" -> 3;
            default -> 0;
        };
    }
}
