package com.campus.lab.schedule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DeterministicScheduler {

    private DeterministicScheduler() {
    }

    static SchedulePlan fcfs(CandidateCatalog catalog) {
        ScheduleResourceState state = catalog.freshResourceState();
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < catalog.demandCount(); index++) {
            order.add(index);
        }
        order.sort(Comparator
                .comparing((Integer index) -> catalog.demand(index).createdAt())
                .thenComparingLong(index -> catalog.demand(index).resvId()));
        List<ScheduleAssignment> assignments = new ArrayList<>();
        Map<Long, List<String>> unresolved = new HashMap<>();
        for (int demandIndex : order) {
            ScheduleDemand demand = catalog.demand(demandIndex);
            ScheduleCandidate selected = null;
            for (ScheduleCandidate candidate : catalog.candidates(demandIndex)) {
                if (state.canOccupy(demand, candidate)) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                unresolved.put(demand.resvId(), List.of("NO_FEASIBLE_CANDIDATE"));
                continue;
            }
            state.occupy(demand, selected);
            assignments.add(selected.assignment(demand.resvId()));
        }
        return new SchedulePlan(AlgorithmType.FCFS, assignments, unresolved);
    }

    static SchedulePlan greedy(CandidateCatalog catalog, ScheduleParameters parameters) {
        ScheduleResourceState state = catalog.freshResourceState();
        List<List<Choice>> scoredCandidates = new ArrayList<>();
        double[] priorities = new double[catalog.demandCount()];
        List<Integer> order = new ArrayList<>();
        for (int demandIndex = 0; demandIndex < catalog.demandCount(); demandIndex++) {
            ScheduleDemand demand = catalog.demand(demandIndex);
            List<Choice> choices = new ArrayList<>();
            double priority = Double.NEGATIVE_INFINITY;
            for (ScheduleCandidate candidate : catalog.candidates(demandIndex)) {
                double score = score(demand, candidate, state, parameters);
                choices.add(new Choice(demandIndex, candidate, score));
                priority = Math.max(priority, score);
            }
            scoredCandidates.add(choices);
            priorities[demandIndex] = priority;
            order.add(demandIndex);
        }
        order.sort(Comparator
                .comparingDouble((Integer index) -> priorities[index]).reversed()
                .thenComparing(index -> catalog.demand(index).createdAt())
                .thenComparingLong(index -> catalog.demand(index).resvId()));
        List<ScheduleAssignment> assignments = new ArrayList<>();
        Map<Long, List<String>> unresolved = new HashMap<>();
        for (int demandIndex : order) {
            ScheduleDemand demand = catalog.demand(demandIndex);
            Choice best = null;
            for (Choice choice : scoredCandidates.get(demandIndex)) {
                if (state.canOccupy(demand, choice.candidate())
                        && better(choice, best, catalog)) {
                    best = choice;
                }
            }
            if (best == null) {
                unresolved.put(demand.resvId(), List.of("NO_FEASIBLE_CANDIDATE"));
                continue;
            }
            state.occupy(demand, best.candidate());
            assignments.add(best.candidate().assignment(demand.resvId()));
        }
        return new SchedulePlan(AlgorithmType.GREEDY, assignments, unresolved);
    }

    private static double score(
            ScheduleDemand demand, ScheduleCandidate candidate,
            ScheduleResourceState state, ScheduleParameters parameters) {
        double urgency = (double) demand.durationMinutes()
                / (demand.windowEnd() - demand.windowStart());
        double credit = demand.credit() / 100.0;
        return parameters.urgencyWeight() * urgency
                + parameters.durationFitWeight() * durationFit(candidate, state)
                + parameters.creditWeight() * credit
                - parameters.greedyFragmentationWeight() * fragmentationPenalty(candidate, state);
    }

    private static double durationFit(ScheduleCandidate candidate, ScheduleResourceState state) {
        long openMask = state.resource(candidate).openMask();
        int left = candidate.startSlot();
        int right = candidate.endSlot() - 1;
        while (left > 0 && (openMask & (1L << (left - 1))) != 0) {
            left--;
        }
        while (right < 47 && (openMask & (1L << (right + 1))) != 0) {
            right++;
        }
        return (double) (candidate.endSlot() - candidate.startSlot()) / (right - left + 1);
    }

    private static double fragmentationPenalty(ScheduleCandidate candidate, ScheduleResourceState state) {
        ScheduleLabResource resource = state.resource(candidate);
        long before = state.roomBlockingMask(candidate);
        long after = before | candidate.timeMask().value();
        int minimumSlots = Math.max(1, resource.effectiveRule().minDurationMinutes() / 30);
        int freeBefore = TimeFragments.freeSlots(resource.openMask(), before);
        if (freeBefore == 0) {
            return 0;
        }
        int additional = Math.max(0,
                TimeFragments.fragmentedFreeSlots(resource.openMask(), after, minimumSlots)
                - TimeFragments.fragmentedFreeSlots(resource.openMask(), before, minimumSlots));
        return (double) additional / freeBefore;
    }

    private static boolean better(Choice choice, Choice best, CandidateCatalog catalog) {
        if (best == null) {
            return true;
        }
        int scoreComparison = Double.compare(choice.score(), best.score());
        if (scoreComparison != 0) {
            return scoreComparison > 0;
        }
        ScheduleDemand demand = catalog.demand(choice.demandIndex());
        ScheduleDemand bestDemand = catalog.demand(best.demandIndex());
        int createdComparison = demand.createdAt().compareTo(bestDemand.createdAt());
        if (createdComparison != 0) {
            return createdComparison < 0;
        }
        if (demand.resvId() != bestDemand.resvId()) {
            return demand.resvId() < bestDemand.resvId();
        }
        if (choice.candidate().labId() != best.candidate().labId()) {
            return choice.candidate().labId() < best.candidate().labId();
        }
        return choice.candidate().startSlot() < best.candidate().startSlot();
    }

    private record Choice(int demandIndex, ScheduleCandidate candidate, double score) {
    }
}
