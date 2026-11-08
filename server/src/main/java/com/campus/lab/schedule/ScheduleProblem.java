package com.campus.lab.schedule;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ScheduleProblem(
        List<ScheduleDemand> demands,
        List<ScheduleLabResource> resources) {

    public ScheduleProblem {
        Objects.requireNonNull(demands, "demands");
        Objects.requireNonNull(resources, "resources");
        demands = List.copyOf(demands);
        resources = List.copyOf(resources);
        Set<Long> demandIds = new HashSet<>();
        if (demands.stream().anyMatch(demand -> !demandIds.add(demand.resvId()))) {
            throw new IllegalArgumentException("预约需求ID不能重复");
        }
        Set<LabDateKey> resourceKeys = new HashSet<>();
        if (resources.stream().anyMatch(resource ->
                !resourceKeys.add(new LabDateKey(resource.labId(), resource.date())))) {
            throw new IllegalArgumentException("同一实验室同一天不能有重复快照");
        }
    }
}
