package com.campus.lab.schedule;

import static com.campus.lab.schedule.ScheduleTestFixtures.CREATED_AT;
import static com.campus.lab.schedule.ScheduleTestFixtures.demand;
import static com.campus.lab.schedule.ScheduleTestFixtures.lab;
import static com.campus.lab.schedule.ScheduleTestFixtures.mask;
import static com.campus.lab.schedule.ScheduleTestFixtures.seat;
import static org.assertj.core.api.Assertions.assertThat;

import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatAssignment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScheduleSeatingServiceTest {

    private final ScheduleSeatingService service = new ScheduleSeatingService();
    private final ScheduleParameters parameters = ScheduleParameters.defaults();

    @Test
    void usesExistingAllocatorToReturnActualKSeatsAndSoftMismatchFlags() {
        ScheduleDemand demand = ScheduleTestFixtures.withCapacityNeeds(
                ScheduleTestFixtures.withPreferences(demand(1), "A", true), 2, 2);
        ScheduleLabResource resource = lab(1, 20, mask(16, 40), 0, List.of(
                seat(3, 3, "STANDARD", "B", false, 0),
                seat(1, 1, "STANDARD", "A", true, 0),
                seat(2, 2, "STANDARD", "A", true, 0)));
        ScheduleProblem problem = new ScheduleProblem(List.of(demand), List.of(resource));
        SchedulePlan firstStage = new FcfsEngine().schedule(problem, parameters);

        ScheduleSeatingPlan seated = service.allocate(problem, firstStage);

        assertThat(seated.assignments()).hasSize(1);
        assertThat(seated.assignments().getFirst().candidateSeatAllocations())
                .extracting(SeatAssignment::seatId).containsExactly(1L, 2L);
        assertThat(seated.assignments().getFirst().candidateSeatAllocations())
                .allMatch(seat -> !seat.zoneMismatch() && !seat.nearWindowMismatch());
        assertThat(seated.unresolvedReasons()).isEmpty();
        assertThat(resource.roomMask()).isZero();
        assertThat(resource.seats().stream().map(seat -> seat.mask()).toList())
                .containsExactly(0L, 0L, 0L);
    }

    @Test
    void roomAllocationContainsEveryActiveSeatButNoInactiveSeat() {
        ScheduleDemand demand = demand(1, 1, CREATED_AT, ReservationMode.ROOM, 60);
        ScheduleLabResource resource = lab(1, 20, mask(16, 40), 0, List.of(
                seat(1, 1, "STANDARD", "A", true, 0),
                seat(2, 2, "STANDARD", "A", false, ResourceStatus.DISABLED, 0),
                seat(3, 3, "STANDARD", "B", false, 0)));
        ScheduleProblem problem = new ScheduleProblem(List.of(demand), List.of(resource));

        ScheduleSeatingPlan seated = service.allocate(
                problem, new FcfsEngine().schedule(problem, parameters));

        assertThat(seated.assignments().getFirst().candidateSeatAllocations())
                .extracting(SeatAssignment::seatId).containsExactly(1L, 3L);
    }

    @Test
    void hardConflictInSecondStageDropsTheWholeConflictingAssignment() {
        ScheduleDemand first = demand(1, 1, CREATED_AT, ReservationMode.SEAT, 60);
        ScheduleDemand second = demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.SEAT, 60);
        ScheduleLabResource oneSeat = lab(1, 20, mask(16, 40), 0,
                List.of(seat(11, 1, "STANDARD", "A", true, 0)));
        ScheduleProblem problem = new ScheduleProblem(List.of(first, second), List.of(oneSeat));
        SchedulePlan conflictingPlan = new SchedulePlan(AlgorithmType.FCFS,
                List.of(new ScheduleAssignment(1, 1, 16, 18),
                        new ScheduleAssignment(2, 1, 16, 18)), Map.of());

        ScheduleSeatingPlan seated = service.allocate(problem, conflictingPlan);

        assertThat(seated.assignments()).hasSize(1);
        assertThat(seated.assignments().getFirst().resvId()).isEqualTo(1);
        assertThat(seated.unresolvedReasons()).containsOnlyKeys(2L);
        assertThat(seated.unresolvedReasons().get(2L)).isNotEmpty();
    }

    @Test
    void sameUserConflictIsRejectedAcrossLabsDuringSecondStage() {
        ScheduleDemand first = demand(1, 7, CREATED_AT, ReservationMode.SEAT, 60);
        ScheduleDemand second = demand(2, 7, CREATED_AT.plusMinutes(1), ReservationMode.SEAT, 60);
        ScheduleProblem problem = new ScheduleProblem(List.of(first, second), List.of(lab(1), lab(2)));
        SchedulePlan conflictingPlan = new SchedulePlan(AlgorithmType.FCFS,
                List.of(new ScheduleAssignment(1, 1, 16, 18),
                        new ScheduleAssignment(2, 2, 16, 18)), Map.of());

        ScheduleSeatingPlan seated = service.allocate(problem, conflictingPlan);

        assertThat(seated.assignments()).hasSize(1);
        assertThat(seated.unresolvedReasons().get(2L)).contains("USER_TIME_CONFLICT");
    }

    @Test
    void firstStageUnresolvedReasonsArePreserved() {
        ScheduleDemand outside = ScheduleTestFixtures.withWindow(demand(1), 0, 60, 60);
        ScheduleProblem problem = new ScheduleProblem(List.of(outside), List.of(lab(1)));
        SchedulePlan firstStage = new FcfsEngine().schedule(problem, parameters);

        ScheduleSeatingPlan seated = service.allocate(problem, firstStage);

        assertThat(seated.assignments()).isEmpty();
        assertThat(seated.unresolvedReasons().get(1L)).contains("NO_FEASIBLE_CANDIDATE");
    }

    @Test
    void higherHardSpecIsSeatedBeforeLowerSpecRegardlessOfFirstStageOrder() {
        ScheduleDemand standard = ScheduleTestFixtures.withSpec(
                demand(1, 1, CREATED_AT, ReservationMode.SEAT, 60), "STANDARD");
        ScheduleDemand gpu = ScheduleTestFixtures.withSpec(
                demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.SEAT, 60), "GPU");
        ScheduleLabResource resource = lab(1, 20, mask(16, 18), 0, List.of(
                seat(1, 1, "GPU", "A", true, 0),
                seat(2, 2, "STANDARD", "A", true, 0)));
        ScheduleProblem problem = new ScheduleProblem(List.of(standard, gpu), List.of(resource));
        SchedulePlan firstStage = new SchedulePlan(AlgorithmType.FCFS,
                List.of(new ScheduleAssignment(1, 1, 16, 18),
                        new ScheduleAssignment(2, 1, 16, 18)), Map.of());

        ScheduleSeatingPlan seated = service.allocate(problem, firstStage);

        assertThat(seated.unresolvedReasons()).isEmpty();
        assertThat(seated.assignments()).hasSize(2);
        assertThat(seated.assignments().get(0).candidateSeatAllocations())
                .extracting(SeatAssignment::seatId).containsExactly(2L);
        assertThat(seated.assignments().get(1).candidateSeatAllocations())
                .extracting(SeatAssignment::seatId).containsExactly(1L);
    }

    @Test
    void anExternalPlanCannotUseATimeOutsideTheDemandWindow() {
        ScheduleDemand demand = demand(1);
        ScheduleProblem problem = new ScheduleProblem(List.of(demand), List.of(lab(1)));
        SchedulePlan invalidPlan = new SchedulePlan(AlgorithmType.FCFS,
                List.of(new ScheduleAssignment(1, 1, 0, 2)), Map.of());

        ScheduleSeatingPlan seated = service.allocate(problem, invalidPlan);

        assertThat(seated.assignments()).isEmpty();
        assertThat(seated.unresolvedReasons().get(1L)).contains("INVALID_FIRST_STAGE_ASSIGNMENT");
    }
}
