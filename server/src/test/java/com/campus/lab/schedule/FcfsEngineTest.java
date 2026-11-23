package com.campus.lab.schedule;

import static com.campus.lab.schedule.ScheduleTestFixtures.CREATED_AT;
import static com.campus.lab.schedule.ScheduleTestFixtures.assignment;
import static com.campus.lab.schedule.ScheduleTestFixtures.demand;
import static com.campus.lab.schedule.ScheduleTestFixtures.lab;
import static com.campus.lab.schedule.ScheduleTestFixtures.mask;
import static com.campus.lab.schedule.ScheduleTestFixtures.seat;
import static org.assertj.core.api.Assertions.assertThat;

import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ResourceStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FcfsEngineTest {

    private final FcfsEngine engine = new FcfsEngine();
    private final ScheduleParameters parameters = ScheduleParameters.defaults();

    @Test
    void ordersDemandsByCreatedAtAndThenReservationId() {
        ScheduleDemand later = demand(1, 1, CREATED_AT.plusMinutes(1), ReservationMode.ROOM, 2);
        ScheduleDemand earlier = demand(2, 2, CREATED_AT, ReservationMode.ROOM, 2);
        ScheduleProblem problem = new ScheduleProblem(List.of(later, earlier), List.of(lab(1)));

        SchedulePlan plan = engine.schedule(problem, parameters);

        assertThat(assignment(plan, 2).startSlot()).isEqualTo(0);
        assertThat(assignment(plan, 1).startSlot()).isEqualTo(2);
    }

    @Test
    void equalCreationTimesUseReservationIdAsTheDeterministicTieBreak() {
        ScheduleDemand higherId = demand(9, 9, CREATED_AT, ReservationMode.ROOM, 2);
        ScheduleDemand lowerId = demand(3, 3, CREATED_AT, ReservationMode.ROOM, 2);

        SchedulePlan plan = engine.schedule(
                new ScheduleProblem(List.of(higherId, lowerId), List.of(lab(1))), parameters);

        assertThat(assignment(plan, 3).startSlot()).isEqualTo(0);
        assertThat(assignment(plan, 9).startSlot()).isEqualTo(2);
    }

    @Test
    void scansLabIdBeforeStartSlotRegardlessOfInputOrder() {
        ScheduleDemand first = demand(1, 1, CREATED_AT, ReservationMode.ROOM, 2);
        ScheduleDemand second = demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.ROOM, 2);
        ScheduleProblem problem = new ScheduleProblem(List.of(first, second), List.of(lab(2), lab(1)));

        SchedulePlan plan = engine.schedule(problem, parameters);

        assertThat(assignment(plan, 1).labId()).isEqualTo(1);
        assertThat(assignment(plan, 1).startSlot()).isEqualTo(0);
        assertThat(assignment(plan, 2).labId()).isEqualTo(1);
        assertThat(assignment(plan, 2).startSlot()).isEqualTo(2);
    }

    @Test
    void intersectsDemandWindowOpenMaskAndEffectiveDurationRule() {
        ScheduleDemand demand = ScheduleTestFixtures.withWindow(demand(1), 605, 750, 2);
        ScheduleLabResource resource = ScheduleTestFixtures.withRule(
                lab(1, 20, mask(0, 5), 0, lab(1).seats()), 1, 2);

        SchedulePlan plan = engine.schedule(
                new ScheduleProblem(List.of(demand), List.of(resource)), parameters);

        ScheduleAssignment assignment = assignment(plan, 1);
        assertThat(assignment.startSlot()).isEqualTo(2);
        assertThat(assignment.endSlot()).isEqualTo(4);
        assertThat(assignment.timeMask().value()).isEqualTo(mask(2, 4));
    }

    @Test
    void rejectsDurationsOutsideTheEffectiveRule() {
        ScheduleDemand demand = demand(1, 1, CREATED_AT, ReservationMode.SEAT, 3);
        ScheduleLabResource resource = ScheduleTestFixtures.withRule(lab(1), 1, 2);

        SchedulePlan plan = engine.schedule(
                new ScheduleProblem(List.of(demand), List.of(resource)), parameters);

        assertThat(plan.assignments()).isEmpty();
        assertThat(plan.unresolvedReasons().get(1L)).contains("NO_FEASIBLE_CANDIDATE");
    }

    @Test
    void enforcesCapacityAndHardSpecWithoutReturningPartialAssignments() {
        ScheduleDemand tooManyPeople = ScheduleTestFixtures.withCapacityNeeds(demand(1), 4, 1);
        ScheduleDemand gpu = ScheduleTestFixtures.withSpec(demand(2), "GPU");
        ScheduleLabResource resource = lab(1, 3, mask(0, 10), 0,
                List.of(seat(11, 1, "STANDARD", "A", true, 0)));

        SchedulePlan plan = engine.schedule(new ScheduleProblem(
                List.of(tooManyPeople, gpu), List.of(resource)), parameters);

        assertThat(plan.assignments()).isEmpty();
        assertThat(plan.unresolvedReasons()).containsOnlyKeys(1L, 2L);
    }

    @Test
    void allowedLabsAndActiveStatusRestrictTheCandidateSet() {
        ScheduleDemand allowed = ScheduleTestFixtures.withAllowedLabs(demand(1), Set.of(2L));
        ScheduleLabResource disabled = ScheduleTestFixtures.withStatus(lab(2), ResourceStatus.DISABLED);

        SchedulePlan plan = engine.schedule(new ScheduleProblem(
                List.of(allowed), List.of(lab(1), disabled)), parameters);

        assertThat(plan.assignments()).isEmpty();
        assertThat(plan.unresolvedReasons().get(1L)).contains("NO_FEASIBLE_CANDIDATE");
    }

    @Test
    void roomAndSeatDemandsBlockEachOtherInBothOrders() {
        ScheduleDemand seatFirst = demand(1, 1, CREATED_AT, ReservationMode.SEAT, 2);
        ScheduleDemand roomSecond = demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.ROOM, 2);
        ScheduleDemand roomFirst = demand(3, 3, CREATED_AT, ReservationMode.ROOM, 2);
        ScheduleDemand seatSecond = demand(4, 4, CREATED_AT.plusMinutes(1), ReservationMode.SEAT, 2);
        ScheduleLabResource oneSeat = lab(1, 20, mask(0, 10), 0,
                List.of(seat(11, 1, "STANDARD", "A", true, 0)));

        SchedulePlan seatThenRoom = engine.schedule(
                new ScheduleProblem(List.of(seatFirst, roomSecond), List.of(oneSeat)), parameters);
        SchedulePlan roomThenSeat = engine.schedule(
                new ScheduleProblem(List.of(roomFirst, seatSecond), List.of(oneSeat)), parameters);

        assertThat(assignment(seatThenRoom, 1).startSlot()).isEqualTo(0);
        assertThat(assignment(seatThenRoom, 2).startSlot()).isEqualTo(2);
        assertThat(assignment(roomThenSeat, 3).startSlot()).isEqualTo(0);
        assertThat(assignment(roomThenSeat, 4).startSlot()).isEqualTo(2);
    }

    @Test
    void sameUserCannotOverlapEvenAcrossDifferentLabs() {
        ScheduleDemand first = demand(1, 7, CREATED_AT, ReservationMode.SEAT, 2);
        ScheduleDemand overlapping = demand(2, 7, CREATED_AT.plusMinutes(1), ReservationMode.SEAT, 2);
        ScheduleDemand otherUser = demand(3, 8, CREATED_AT.plusMinutes(2), ReservationMode.SEAT, 2);

        SchedulePlan plan = engine.schedule(new ScheduleProblem(
                List.of(first, overlapping, otherUser), List.of(lab(1), lab(2))), parameters);

        assertThat(assignment(plan, 1).startSlot()).isEqualTo(0);
        assertThat(assignment(plan, 2).startSlot()).isEqualTo(2);
        assertThat(assignment(plan, 3).startSlot()).isEqualTo(0);
    }

    @Test
    void noCandidateReturnsAnUnresolvedReasonAndNoIllegalAssignment() {
        ScheduleDemand outsideOpenHours = ScheduleTestFixtures.withWindow(demand(1), 0, 60, 1);

        SchedulePlan plan = engine.schedule(new ScheduleProblem(
                List.of(outsideOpenHours), List.of(lab(1))), parameters);

        assertThat(plan.assignments()).isEmpty();
        assertThat(plan.unresolvedReasons().get(1L)).isNotEmpty();
    }

    @Test
    void resourcesForAnotherDateAreNotUsed() {
        ScheduleDemand demand = ScheduleTestFixtures.withDate(demand(1), ScheduleTestFixtures.DATE.plusDays(1));

        SchedulePlan plan = engine.schedule(new ScheduleProblem(
                List.of(demand), List.of(lab(1))), parameters);

        assertThat(plan.assignments()).isEmpty();
        assertThat(plan.unresolvedReasons()).containsOnlyKeys(1L);
    }

    @Test
    void onlyWholePeriodsInsideTheRuleClockAreCandidates() {
        ScheduleDemand demand = ScheduleTestFixtures.withWindow(demand(1), 480, 600, 1);
        ScheduleLabResource base = lab(1, 20, mask(0, 6), 0, lab(1).seats());
        ScheduleLabResource resource = new ScheduleLabResource(
                base.labId(), base.date(), base.status(), base.capacity(), base.openMask(), base.roomMask(),
                base.seats(), new com.campus.lab.resource.EffectiveTimeRule(
                        1, 1, base.date().getDayOfWeek().getValue(),
                        java.time.LocalTime.of(8, 15), java.time.LocalTime.of(10, 15),
                        1, 10, 1, 5, 0, 365),
                base.periodSchedule());

        SchedulePlan plan = engine.schedule(
                new ScheduleProblem(List.of(demand), List.of(resource)), parameters);

        assertThat(assignment(plan, 1).startSlot()).isEqualTo(0);
        assertThat(assignment(plan, 1).endSlot()).isEqualTo(1);
    }

    @Test
    void standardThenGpuIsFeasibleWithNestedSpecCapacities() {
        ScheduleDemand standard = ScheduleTestFixtures.withSpec(
                demand(1, 1, CREATED_AT, ReservationMode.SEAT, 2), "STANDARD");
        ScheduleDemand gpu = ScheduleTestFixtures.withSpec(
                demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.SEAT, 2), "GPU");
        ScheduleLabResource resource = lab(1, 20, mask(0, 2), 0, List.of(
                seat(1, 1, "GPU", "A", true, 0),
                seat(2, 2, "STANDARD", "A", true, 0)));

        SchedulePlan plan = engine.schedule(
                new ScheduleProblem(List.of(standard, gpu), List.of(resource)), parameters);

        assertThat(plan.assignments()).extracting(ScheduleAssignment::resvId).containsExactly(1L, 2L);
        assertThat(assignment(plan, 1).startSlot()).isEqualTo(0);
        assertThat(assignment(plan, 2).startSlot()).isEqualTo(0);
        assertThat(plan.unresolvedReasons()).isEmpty();
    }

    @Test
    void thirdMixedSpecDemandCannotOversellTwoSeats() {
        ScheduleDemand standard = ScheduleTestFixtures.withSpec(
                demand(1, 1, CREATED_AT, ReservationMode.SEAT, 2), "STANDARD");
        ScheduleDemand high = ScheduleTestFixtures.withSpec(
                demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.SEAT, 2), "HIGH");
        ScheduleDemand gpu = ScheduleTestFixtures.withSpec(
                demand(3, 3, CREATED_AT.plusMinutes(2), ReservationMode.SEAT, 2), "GPU");
        ScheduleLabResource resource = lab(1, 20, mask(0, 2), 0, List.of(
                seat(1, 1, "GPU", "A", true, 0),
                seat(2, 2, "STANDARD", "A", true, 0)));

        SchedulePlan plan = engine.schedule(
                new ScheduleProblem(List.of(standard, high, gpu), List.of(resource)), parameters);

        assertThat(plan.assignments()).extracting(ScheduleAssignment::resvId).containsExactly(1L, 2L);
        assertThat(plan.unresolvedReasons()).containsOnlyKeys(3L);
    }
}
