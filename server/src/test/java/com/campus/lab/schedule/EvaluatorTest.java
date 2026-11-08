package com.campus.lab.schedule;

import static com.campus.lab.schedule.ScheduleTestFixtures.CREATED_AT;
import static com.campus.lab.schedule.ScheduleTestFixtures.demand;
import static com.campus.lab.schedule.ScheduleTestFixtures.lab;
import static com.campus.lab.schedule.ScheduleTestFixtures.mask;
import static com.campus.lab.schedule.ScheduleTestFixtures.seat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.reservation.SeatAssignment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluatorTest {

    private final ScheduleParameters parameters = ScheduleParameters.defaults();

    @Test
    void actualSecondStageSeatsDriveSuccessAndBothSoftPreferenceMismatches() {
        ScheduleDemand preferred = ScheduleTestFixtures.withPreferences(demand(1), "A", true);
        ScheduleDemand noLongerFits = demand(2);
        ScheduleLabResource resource = lab(1, 20, mask(16, 18), 0,
                List.of(seat(11, 1, "STANDARD", "B", false, 0)));
        ScheduleProblem problem = new ScheduleProblem(List.of(preferred, noLongerFits), List.of(resource));
        SchedulePlan firstStage = new FcfsEngine().schedule(problem, parameters);
        ScheduleSeatingPlan seated = new ScheduleSeatingService().allocate(problem, firstStage);

        EvaluationResult result = new Evaluator().evaluate(problem, seated, 5, 7);

        assertThat(seated.assignments()).hasSize(1);
        assertThat(seated.assignments().getFirst().candidateSeatAllocations())
                .extracting(SeatAssignment::seatId).containsExactly(11L);
        assertThat(result.assignmentSuccessRate()).isEqualTo(0.5);
        assertThat(result.softPreferenceMismatchRate()).isEqualTo(1.0);
        assertThat(result.solveTime().totalNanos()).isEqualTo(12);
    }

    @Test
    void zoneMismatchIsCountedOncePerAllocatedSeat() {
        ScheduleDemand demand = ScheduleTestFixtures.withCapacityNeeds(
                ScheduleTestFixtures.withPreferences(demand(1), "A", false), 2, 2);
        ScheduleLabResource resource = lab(1, 20, mask(16, 18), 0, List.of(
                seat(1, 1, "STANDARD", "A", false, 0),
                seat(2, 2, "STANDARD", "B", true, 0)));
        ScheduleProblem problem = new ScheduleProblem(List.of(demand), List.of(resource));
        ScheduleSeatingPlan seated = new ScheduleSeatingService().allocate(
                problem, new FcfsEngine().schedule(problem, parameters));

        EvaluationResult result = new Evaluator().evaluate(problem, seated, 0, 0);

        assertThat(seated.assignments().getFirst().candidateSeatAllocations())
                .extracting(SeatAssignment::nearWindowMismatch).containsExactly(false, false);
        assertThat(result.softPreferenceMismatchRate()).isEqualTo(0.5);
    }

    @Test
    void mismatchDenominatorIncludesAllocatedSeatsWithoutPreferences() {
        ScheduleDemand zoneMismatch = ScheduleTestFixtures.withPreferences(demand(1), "A", false);
        ScheduleDemand noPreference = demand(2);
        ScheduleLabResource resource = lab(1, 20, mask(16, 20), 0, List.of(
                seat(1, 1, "STANDARD", "B", false, 0),
                seat(2, 2, "STANDARD", "B", true, 0)));
        ScheduleProblem problem = new ScheduleProblem(List.of(zoneMismatch, noPreference), List.of(resource));
        ScheduleSeatingPlan seated = new ScheduleSeatingService().allocate(
                problem, new FcfsEngine().schedule(problem, parameters));

        EvaluationResult result = new Evaluator().evaluate(problem, seated, 0, 0);

        assertThat(seated.assignments()).hasSize(2);
        assertThat(seated.assignments().stream()
                .mapToInt(assignment -> assignment.candidateSeatAllocations().size()).sum())
                .isEqualTo(2);
        assertThat(result.softPreferenceMismatchRate()).isEqualTo(0.5);
    }

    @Test
    void zoneAndWindowMismatchOnTheSameSeatCountsAsOneMismatchSeat() {
        ScheduleDemand doubleMismatch = ScheduleTestFixtures.withPreferences(demand(1), "A", true);
        ScheduleDemand noPreference = demand(2);
        ScheduleLabResource resource = lab(1, 20, mask(16, 20), 0, List.of(
                seat(1, 1, "STANDARD", "B", false, 0),
                seat(2, 2, "STANDARD", "B", false, 0)));
        ScheduleProblem problem = new ScheduleProblem(List.of(doubleMismatch, noPreference), List.of(resource));
        ScheduleSeatingPlan seated = new ScheduleSeatingService().allocate(
                problem, new FcfsEngine().schedule(problem, parameters));

        EvaluationResult result = new Evaluator().evaluate(problem, seated, 0, 0);

        SeatAssignment mismatchedSeat = seated.assignments().stream()
                .filter(assignment -> assignment.resvId() == 1)
                .findFirst().orElseThrow()
                .candidateSeatAllocations().getFirst();
        assertThat(mismatchedSeat.zoneMismatch()).isTrue();
        assertThat(mismatchedSeat.nearWindowMismatch()).isTrue();
        assertThat(result.softPreferenceMismatchRate()).isEqualTo(0.5);
    }

    @Test
    void utilizationIncludesFixedAndNewOccupancyInTheGlobalDenominator() {
        ScheduleLabResource resource = lab(1, 20, mask(16, 20), 0, List.of(
                seat(1, 1, "STANDARD", "A", true, mask(16, 18)),
                seat(2, 2, "STANDARD", "A", true, 0)));
        ScheduleProblem problem = new ScheduleProblem(List.of(demand(1)), List.of(resource));
        ScheduleSeatingPlan seated = new ScheduleSeatingService().allocate(
                problem, new FcfsEngine().schedule(problem, parameters));

        EvaluationResult result = new Evaluator().evaluate(problem, seated, 0, 0);

        assertThat(seated.assignments().getFirst().candidateSeatAllocations())
                .extracting(SeatAssignment::seatId).containsExactly(2L);
        assertThat(result.averageSeatUtilization()).isEqualTo(0.5);
    }

    @Test
    void roomUtilizationOccupiesEveryActiveSeatForTheWholeRoomInterval() {
        ScheduleDemand room = demand(1, 1, CREATED_AT, ReservationMode.ROOM, 60);
        ScheduleLabResource resource = lab(1, 20, mask(16, 18), 0, List.of(
                seat(1, 1, "STANDARD", "A", true, 0),
                seat(2, 2, "STANDARD", "A", true, ResourceStatus.DISABLED, 0),
                seat(3, 3, "STANDARD", "B", false, 0)));
        ScheduleProblem problem = new ScheduleProblem(List.of(room), List.of(resource));
        ScheduleSeatingPlan seated = new ScheduleSeatingService().allocate(
                problem, new FcfsEngine().schedule(problem, parameters));

        EvaluationResult result = new Evaluator().evaluate(problem, seated, 0, 0);

        assertThat(seated.assignments().getFirst().candidateSeatAllocations()).hasSize(2);
        assertThat(result.averageSeatUtilization()).isEqualTo(1.0);
    }

    @Test
    void fragmentationIsShortFreeSlotsDividedByAllFreeSlotsUsingEffectiveMinimum() {
        ScheduleLabResource resource = ScheduleTestFixtures.withRule(
                lab(1, 20, mask(0, 4), 0,
                        List.of(seat(1, 1, "STANDARD", "A", true, mask(2, 3)))), 60, 240);
        ScheduleProblem problem = new ScheduleProblem(List.of(), List.of(resource));
        ScheduleSeatingPlan empty = new ScheduleSeatingPlan(List.of(), Map.of());

        EvaluationResult sixtyMinuteMinimum = new Evaluator().evaluate(problem, empty, 0, 0);
        ScheduleProblem thirtyMinuteMinimum = new ScheduleProblem(List.of(),
                List.of(ScheduleTestFixtures.withRule(resource, 30, 240)));
        EvaluationResult defaultMinimum = new Evaluator().evaluate(thirtyMinuteMinimum, empty, 0, 0);

        assertThat(sixtyMinuteMinimum.timeFragmentationRate())
                .isCloseTo(1.0 / 3, offset(0.000000001));
        assertThat(sixtyMinuteMinimum.averageSeatUtilization()).isEqualTo(0.25);
        assertThat(defaultMinimum.timeFragmentationRate()).isZero();
    }

    @Test
    void solveUsesMonotonicNonnegativePhaseTimingsAndTotalIsTheirSum() {
        ScheduleProblem problem = new ScheduleProblem(List.of(demand(1)), List.of(lab(1)));

        EvaluationResult result = new Evaluator().solve(problem, parameters, new FcfsEngine());

        assertThat(result.solveTime().phase1Nanos()).isNotNegative();
        assertThat(result.solveTime().phase2Nanos()).isNotNegative();
        assertThat(result.solveTime().totalNanos())
                .isEqualTo(result.solveTime().phase1Nanos() + result.solveTime().phase2Nanos());
    }

    @Test
    void emptyDemandAndZeroAvailableSeatTimeProduceZeroRatherThanInventedRates() {
        ScheduleProblem problem = new ScheduleProblem(List.of(),
                List.of(lab(1, 20, mask(16, 18), 0, List.of())));

        EvaluationResult result = new Evaluator().evaluate(
                problem, new ScheduleSeatingPlan(List.of(), Map.of()), 3, 4);

        assertThat(result.assignmentSuccessRate()).isZero();
        assertThat(result.averageSeatUtilization()).isZero();
        assertThat(result.softPreferenceMismatchRate()).isZero();
        assertThat(result.timeFragmentationRate()).isZero();
        assertThat(result.solveTime().totalNanos()).isEqualTo(7);
    }
}
