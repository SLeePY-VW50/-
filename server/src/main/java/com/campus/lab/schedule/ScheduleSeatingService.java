package com.campus.lab.schedule;

import com.campus.lab.reservation.BitmapConflictDetector;
import com.campus.lab.reservation.ConflictDecision;
import com.campus.lab.reservation.ConflictQuery;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatAssignment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ScheduleSeatingService {

    private final BitmapConflictDetector conflictDetector = new BitmapConflictDetector();

    public ScheduleSeatingPlan allocate(ScheduleProblem problem, SchedulePlan firstStage) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(firstStage, "firstStage");
        CandidateCatalog catalog = CandidateCatalog.create(problem);
        ScheduleResourceState state = catalog.freshResourceState();
        Map<Long, Integer> demandIndices = new HashMap<>();
        for (int index = 0; index < catalog.demandCount(); index++) {
            demandIndices.put(catalog.demand(index).resvId(), index);
        }
        firstStage.assignments().forEach(assignment -> {
            if (!demandIndices.containsKey(assignment.resvId())) {
                throw new IllegalArgumentException("第一阶段包含未知需求");
            }
        });
        List<ScheduleAssignment> seatingOrder = new ArrayList<>(firstStage.assignments());
        seatingOrder.sort(Comparator
                .comparingInt((ScheduleAssignment assignment) -> ScheduleSpecRank.of(
                        catalog.demand(demandIndices.get(assignment.resvId())).minSpecLevel())).reversed()
                .thenComparing(assignment ->
                        catalog.demand(demandIndices.get(assignment.resvId())).createdAt())
                .thenComparingLong(ScheduleAssignment::resvId));
        Map<Long, List<String>> unresolved = new HashMap<>();
        problem.demands().forEach(demand -> {
            List<String> reasons = firstStage.unresolvedReasons().get(demand.resvId());
            if (reasons != null) {
                unresolved.put(demand.resvId(), reasons);
            }
        });
        List<ScheduleSeatedAssignment> seated = new ArrayList<>();
        Set<Long> completed = new HashSet<>();
        for (ScheduleAssignment assignment : seatingOrder) {
            Integer demandIndex = demandIndices.get(assignment.resvId());
            if (demandIndex == null) {
                throw new IllegalArgumentException("第一阶段包含未知需求");
            }
            ScheduleDemand demand = catalog.demand(demandIndex);
            unresolved.remove(demand.resvId());
            int candidateIndex = catalog.indexOfCandidate(demandIndex, assignment);
            if (candidateIndex < 0) {
                unresolved.put(demand.resvId(), List.of("INVALID_FIRST_STAGE_ASSIGNMENT"));
                continue;
            }
            ScheduleCandidate candidate = catalog.candidates(demandIndex).get(candidateIndex);
            if (state.hasUserConflict(demand, candidate)) {
                unresolved.put(demand.resvId(), List.of("USER_TIME_CONFLICT"));
                continue;
            }
            ConflictQuery query = new ConflictQuery(
                    assignment.timeMask(), demand.mode(), demand.seatCount(), demand.minSpecLevel(),
                    demand.preferredZone(), demand.preferNearWindow());
            ConflictDecision decision = conflictDetector.detect(state.snapshot(candidate), query);
            if (!decision.available()) {
                unresolved.put(demand.resvId(), decision.reasons().isEmpty()
                        ? List.of("SECOND_STAGE_CONFLICT") : decision.reasons());
                continue;
            }
            List<SeatAssignment> seats = demand.mode() == ReservationMode.ROOM
                    ? roomSeats(state.resource(candidate), demand) : decision.selectedSeats();
            state.occupyActual(demand, candidate, seats);
            seated.add(new ScheduleSeatedAssignment(
                    demand.resvId(), candidate.labId(), candidate.startSlot(), candidate.endSlot(), seats));
            completed.add(demand.resvId());
        }
        problem.demands().forEach(demand -> {
            if (!completed.contains(demand.resvId()) && !unresolved.containsKey(demand.resvId())) {
                unresolved.put(demand.resvId(), List.of("NOT_ASSIGNED_IN_FIRST_STAGE"));
            }
        });
        return new ScheduleSeatingPlan(seated, unresolved);
    }

    private static List<SeatAssignment> roomSeats(ScheduleLabResource resource, ScheduleDemand demand) {
        return resource.seats().stream()
                .filter(seat -> seat.status() == ResourceStatus.ACTIVE)
                .sorted(Comparator.comparingInt(com.campus.lab.reservation.SeatSnapshot::seatIndex)
                        .thenComparingLong(com.campus.lab.reservation.SeatSnapshot::seatId))
                .map(seat -> new SeatAssignment(
                        seat.seatId(), seat.seatCode(), seat.seatIndex(),
                        demand.preferredZone() != null && !demand.preferredZone().equals(seat.zoneCode()),
                        demand.preferNearWindow() && !seat.nearWindow()))
                .toList();
    }
}
