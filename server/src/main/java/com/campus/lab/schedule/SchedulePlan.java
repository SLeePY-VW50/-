package com.campus.lab.schedule;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public record SchedulePlan(
        AlgorithmType algorithmType,
        List<ScheduleAssignment> assignments,
        Map<Long, List<String>> unresolvedReasons) {

    public SchedulePlan {
        Objects.requireNonNull(algorithmType, "algorithmType");
        Objects.requireNonNull(assignments, "assignments");
        Objects.requireNonNull(unresolvedReasons, "unresolvedReasons");
        assignments = assignments.stream()
                .sorted(java.util.Comparator.comparingLong(ScheduleAssignment::resvId))
                .toList();
        Set<Long> assigned = new HashSet<>();
        if (assignments.stream().anyMatch(assignment -> !assigned.add(assignment.resvId()))) {
            throw new IllegalArgumentException("每个需求最多只能有一个排期分配");
        }
        Map<Long, List<String>> copy = new TreeMap<>();
        unresolvedReasons.forEach((resvId, reasons) -> copy.put(resvId, List.copyOf(reasons)));
        unresolvedReasons = Collections.unmodifiableMap(copy);
    }
}
