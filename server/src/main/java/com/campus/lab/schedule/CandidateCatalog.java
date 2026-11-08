package com.campus.lab.schedule;

import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatSnapshot;
import com.campus.lab.resource.EffectiveTimeRule;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CandidateCatalog {

    private final ScheduleProblem problem;
    private final List<List<ScheduleCandidate>> candidates;
    private final ScheduleResourceState initialState;
    private final List<Integer> repairOrder;

    private CandidateCatalog(ScheduleProblem problem, List<List<ScheduleCandidate>> candidates) {
        this.problem = problem;
        this.candidates = candidates;
        initialState = new ScheduleResourceState(problem);
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < problem.demands().size(); index++) {
            order.add(index);
        }
        order.sort(Comparator
                .comparing((Integer index) -> problem.demands().get(index).createdAt())
                .thenComparingLong(index -> problem.demands().get(index).resvId()));
        repairOrder = List.copyOf(order);
    }

    static CandidateCatalog create(ScheduleProblem problem) {
        List<List<ScheduleCandidate>> candidates = new ArrayList<>();
        for (ScheduleDemand demand : problem.demands()) {
            candidates.add(generate(problem, demand));
        }
        return new CandidateCatalog(problem, List.copyOf(candidates));
    }

    ScheduleResourceState freshResourceState() {
        return initialState.copy();
    }

    int demandCount() {
        return problem.demands().size();
    }

    ScheduleDemand demand(int index) {
        return problem.demands().get(index);
    }

    List<Integer> repairOrder() {
        return repairOrder;
    }

    List<ScheduleCandidate> candidates(int demandIndex) {
        return candidates.get(demandIndex);
    }

    int candidateCount(int demandIndex) {
        return candidates.get(demandIndex).size();
    }

    int indexOfCandidate(int demandIndex, ScheduleAssignment assignment) {
        List<ScheduleCandidate> demandCandidates = candidates.get(demandIndex);
        for (int index = 0; index < demandCandidates.size(); index++) {
            ScheduleCandidate candidate = demandCandidates.get(index);
            if (candidate.labId() == assignment.labId()
                    && candidate.startSlot() == assignment.startSlot()
                    && candidate.endSlot() == assignment.endSlot()) {
                return index;
            }
        }
        return -1;
    }

    int[] genesFor(SchedulePlan plan) {
        int[] genes = new int[demandCount()];
        java.util.Arrays.fill(genes, -1);
        for (ScheduleAssignment assignment : plan.assignments()) {
            for (int demandIndex = 0; demandIndex < demandCount(); demandIndex++) {
                if (demand(demandIndex).resvId() == assignment.resvId()) {
                    genes[demandIndex] = indexOfCandidate(demandIndex, assignment);
                    break;
                }
            }
        }
        return genes;
    }

    private static List<ScheduleCandidate> generate(ScheduleProblem problem, ScheduleDemand demand) {
        List<ScheduleCandidate> result = new ArrayList<>();
        problem.resources().stream()
                .filter(resource -> resource.date().equals(demand.date()))
                .filter(resource -> demand.allowedLabIds().isEmpty()
                        || demand.allowedLabIds().contains(resource.labId()))
                .filter(resource -> staticallySuitable(resource, demand))
                .sorted(Comparator.comparingLong(ScheduleLabResource::labId))
                .forEach(resource -> addTimeCandidates(resource, demand, result));
        return List.copyOf(result);
    }

    private static boolean staticallySuitable(ScheduleLabResource resource, ScheduleDemand demand) {
        EffectiveTimeRule rule = resource.effectiveRule();
        if (resource.status() != ResourceStatus.ACTIVE || rule == null
                || rule.labId() != resource.labId()
                || rule.dayOfWeek() != demand.date().getDayOfWeek().getValue()
                || demand.durationMinutes() < rule.minDurationMinutes()
                || demand.durationMinutes() > rule.maxDurationMinutes()
                || demand.attendeeCount() > resource.capacity()
                || demand.seatCount() > resource.capacity()
                || rule.openTime() == null || rule.closeTime() == null) {
            return false;
        }
        int minimumSpec = ScheduleSpecRank.of(demand.minSpecLevel());
        if (demand.minSpecLevel() != null && minimumSpec == 0) {
            return false;
        }
        List<SeatSnapshot> activeSeats = resource.seats().stream()
                .filter(seat -> seat.status() == ResourceStatus.ACTIVE)
                .toList();
        if (activeSeats.isEmpty()) {
            return false;
        }
        if (demand.mode() == ReservationMode.ROOM) {
            return activeSeats.stream().allMatch(seat ->
                    ScheduleSpecRank.of(seat.specLevel()) >= minimumSpec);
        }
        return activeSeats.stream()
                .filter(seat -> ScheduleSpecRank.of(seat.specLevel()) >= minimumSpec)
                .count() >= demand.seatCount();
    }

    private static void addTimeCandidates(
            ScheduleLabResource resource, ScheduleDemand demand, List<ScheduleCandidate> result) {
        int openMinute = minuteOfDay(resource.effectiveRule().openTime());
        int closeMinute = LocalTime.MIDNIGHT.equals(resource.effectiveRule().closeTime())
                ? 1440 : minuteOfDay(resource.effectiveRule().closeTime());
        int earliest = ((Math.max(demand.windowStart(), openMinute) + 29) / 30) * 30;
        int latestEnd = (Math.min(demand.windowEnd(), closeMinute) / 30) * 30;
        for (int start = earliest; start + demand.durationMinutes() <= latestEnd; start += 30) {
            int startSlot = start / 30;
            int endSlot = (start + demand.durationMinutes()) / 30;
            long requested = com.campus.lab.reservation.TimeSlotMask.of(startSlot, endSlot).value();
            if ((resource.openMask() & requested) == requested) {
                result.add(new ScheduleCandidate(resource.labId(), resource.date(), startSlot, endSlot));
            }
        }
    }

    private static int minuteOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
