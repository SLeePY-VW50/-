package com.campus.lab.schedule;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public record ScheduleSeatingPlan(
        List<ScheduleSeatedAssignment> assignments,
        Map<Long, List<String>> unresolvedReasons) {

    public ScheduleSeatingPlan {
        Objects.requireNonNull(assignments, "assignments");
        Objects.requireNonNull(unresolvedReasons, "unresolvedReasons");
        assignments = assignments.stream()
                .sorted(java.util.Comparator.comparingLong(ScheduleSeatedAssignment::resvId))
                .toList();
        Set<Long> assigned = new HashSet<>();
        if (assignments.stream().anyMatch(assignment -> !assigned.add(assignment.resvId()))) {
            throw new IllegalArgumentException("每个需求最多只能有一个落座结果");
        }
        Map<Long, List<String>> copy = new TreeMap<>();
        unresolvedReasons.forEach((resvId, reasons) -> copy.put(resvId, List.copyOf(reasons)));
        unresolvedReasons = Collections.unmodifiableMap(copy);
    }
}
