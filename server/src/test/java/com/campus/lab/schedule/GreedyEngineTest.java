package com.campus.lab.schedule;

import static com.campus.lab.schedule.ScheduleTestFixtures.CREATED_AT;
import static com.campus.lab.schedule.ScheduleTestFixtures.assignment;
import static com.campus.lab.schedule.ScheduleTestFixtures.demand;
import static com.campus.lab.schedule.ScheduleTestFixtures.lab;
import static com.campus.lab.schedule.ScheduleTestFixtures.mask;
import static org.assertj.core.api.Assertions.assertThat;

import com.campus.lab.reservation.ReservationMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class GreedyEngineTest {

    private final GreedyEngine engine = new GreedyEngine();

    @Test
    void creditWeightCanPrioritizeTwoShortDemandsOverAnEarlierLongDemand() {
        ScheduleDemand longLowCredit = ScheduleTestFixtures.withCredit(
                ScheduleTestFixtures.withWindow(
                        demand(1, 1, CREATED_AT, ReservationMode.ROOM, 60), 0, 120, 60), 10);
        ScheduleDemand shortHighCredit1 = ScheduleTestFixtures.withCredit(
                ScheduleTestFixtures.withWindow(
                        demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.ROOM, 30), 0, 120, 30), 100);
        ScheduleDemand shortHighCredit2 = ScheduleTestFixtures.withCredit(
                ScheduleTestFixtures.withWindow(
                        demand(3, 3, CREATED_AT.plusMinutes(2), ReservationMode.ROOM, 30), 0, 120, 30), 100);
        ScheduleProblem problem = new ScheduleProblem(
                List.of(longLowCredit, shortHighCredit1, shortHighCredit2),
                List.of(lab(1, 20, mask(0, 2), 0, lab(1).seats())));
        ScheduleParameters parameters = ScheduleParameters.builder()
                .urgencyWeight(0)
                .durationFitWeight(0)
                .creditWeight(1)
                .greedyFragmentationWeight(0)
                .build();

        SchedulePlan plan = engine.schedule(problem, parameters);

        assertThat(plan.assignments()).hasSize(2);
        assertThat(assignment(plan, 2).startSlot()).isEqualTo(0);
        assertThat(assignment(plan, 3).startSlot()).isEqualTo(1);
        assertThat(plan.unresolvedReasons()).containsOnlyKeys(1L);
        assertThat(new FcfsEngine().schedule(problem, parameters).assignments()).hasSize(1);
    }

    @Test
    void urgencyUsesTheProportionOfTheWindowConsumedByTheDemand() {
        ScheduleDemand flexible = ScheduleTestFixtures.withCredit(
                demand(1, 1, CREATED_AT, ReservationMode.ROOM, 60), 0);
        ScheduleDemand tight = ScheduleTestFixtures.withCredit(ScheduleTestFixtures.withWindow(
                demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.ROOM, 60), 480, 540, 60), 0);
        ScheduleParameters parameters = ScheduleParameters.builder()
                .urgencyWeight(1)
                .durationFitWeight(0)
                .creditWeight(0)
                .greedyFragmentationWeight(0)
                .build();

        SchedulePlan plan = engine.schedule(
                new ScheduleProblem(List.of(flexible, tight), List.of(lab(1))), parameters);

        assertThat(assignment(plan, 2).startSlot()).isEqualTo(16);
        assertThat(assignment(plan, 1).startSlot()).isEqualTo(18);
    }

    @Test
    void durationFitPrefersTheCandidateThatFillsMoreOfItsOpenRun() {
        ScheduleLabResource longOpenRun = lab(1, 20, mask(16, 24), 0, lab(1).seats());
        ScheduleLabResource exactOpenRun = lab(2, 20, mask(16, 18), 0, lab(2).seats());
        ScheduleParameters parameters = ScheduleParameters.builder()
                .urgencyWeight(0)
                .durationFitWeight(1)
                .creditWeight(0)
                .greedyFragmentationWeight(0)
                .build();

        SchedulePlan plan = engine.schedule(new ScheduleProblem(
                List.of(demand(1)), List.of(longOpenRun, exactOpenRun)), parameters);

        assertThat(assignment(plan, 1).labId()).isEqualTo(2);
        assertThat(assignment(plan, 1).startSlot()).isEqualTo(16);
    }

    @Test
    void fragmentationPenaltyAvoidsCreatingASlotTooShortForTheEffectiveMinimum() {
        ScheduleDemand demand = ScheduleTestFixtures.withWindow(
                demand(1, 1, CREATED_AT, ReservationMode.ROOM, 60), 0, 180, 60);
        ScheduleLabResource resource = ScheduleTestFixtures.withRule(
                lab(1, 20, mask(0, 6), mask(3, 4), lab(1).seats()), 60, 240);
        ScheduleParameters parameters = ScheduleParameters.builder()
                .urgencyWeight(0)
                .durationFitWeight(0)
                .creditWeight(0)
                .greedyFragmentationWeight(1)
                .build();

        SchedulePlan plan = engine.schedule(
                new ScheduleProblem(List.of(demand), List.of(resource)), parameters);

        assertThat(assignment(plan, 1).startSlot()).isEqualTo(4);
    }

    @Test
    void equalScoresUseDemandAndCandidateTieBreaksAndAreRepeatable() {
        ScheduleDemand later = demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.SEAT, 60);
        ScheduleDemand earlier = demand(1, 1, CREATED_AT, ReservationMode.SEAT, 60);
        ScheduleProblem problem = new ScheduleProblem(List.of(later, earlier), List.of(lab(2), lab(1)));
        ScheduleParameters parameters = ScheduleParameters.builder()
                .urgencyWeight(0)
                .durationFitWeight(0)
                .creditWeight(0)
                .greedyFragmentationWeight(0)
                .build();

        SchedulePlan first = engine.schedule(problem, parameters);
        SchedulePlan second = engine.schedule(problem, parameters);

        assertThat(first).isEqualTo(second);
        assertThat(engine.algorithmType()).isEqualTo(AlgorithmType.GREEDY);
        assertThat(assignment(first, 1).labId()).isEqualTo(1);
        assertThat(assignment(first, 1).startSlot()).isEqualTo(16);
    }

    @Test
    void noCandidateIsUnresolvedRatherThanPartiallyAssigned() {
        ScheduleDemand demand = ScheduleTestFixtures.withWindow(demand(1), 0, 60, 60);

        SchedulePlan plan = engine.schedule(
                new ScheduleProblem(List.of(demand), List.of(lab(1))), ScheduleParameters.defaults());

        assertThat(plan.assignments()).isEmpty();
        assertThat(plan.unresolvedReasons().get(1L)).contains("NO_FEASIBLE_CANDIDATE");
    }
}
