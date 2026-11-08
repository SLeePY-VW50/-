package com.campus.lab.schedule;

import static com.campus.lab.schedule.ScheduleTestFixtures.CREATED_AT;
import static com.campus.lab.schedule.ScheduleTestFixtures.demand;
import static com.campus.lab.schedule.ScheduleTestFixtures.lab;
import static com.campus.lab.schedule.ScheduleTestFixtures.mask;
import static com.campus.lab.schedule.ScheduleTestFixtures.seat;
import static org.assertj.core.api.Assertions.assertThat;

import com.campus.lab.reservation.ReservationMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GeneticEngineTest {

    private final GeneticEngine engine = new GeneticEngine();

    @Test
    void sameSeedProblemAndParametersProduceExactlyTheSamePlan() {
        ScheduleProblem problem = new ScheduleProblem(
                List.of(demand(1), demand(2), demand(3), demand(4)), List.of(lab(2), lab(1)));
        ScheduleParameters parameters = smallParameters().seed(987654321).build();

        SchedulePlan first = engine.schedule(problem, parameters);
        SchedulePlan second = new GeneticEngine().schedule(problem, parameters);

        assertThat(first).isEqualTo(second);
        assertThat(first.algorithmType()).isEqualTo(AlgorithmType.GENETIC);
        assertThat(engine.algorithmType()).isEqualTo(AlgorithmType.GENETIC);
    }

    @Test
    void injectedGreedySolutionCanWinWithoutRandomGenerations() {
        ScheduleProblem problem = injectionProblem();
        ScheduleParameters parameters = injectionParameters().populationSize(2).generations(0).build();

        SchedulePlan plan = engine.schedule(problem, parameters);

        assertThat(plan.assignments()).hasSize(2);
        assertThat(plan.unresolvedReasons()).containsOnlyKeys(1L);
    }

    @Test
    void elitismRetainsTheBestInjectedSolutionDespiteFullMutationAndCrossover() {
        ScheduleProblem problem = injectionProblem();
        ScheduleParameters parameters = injectionParameters()
                .populationSize(4)
                .generations(8)
                .crossoverRate(1)
                .mutationRate(1)
                .seed(19)
                .build();

        SchedulePlan plan = engine.schedule(problem, parameters);

        assertThat(plan.assignments()).hasSize(2);
        assertThat(plan.unresolvedReasons()).containsOnlyKeys(1L);
    }

    @Test
    void singlePointCrossoverExchangesSuffixesWithoutMutatingParents() {
        int[] first = {0, 1, 2};
        int[] second = {9, 8, 7};

        int[][] children = GeneticEngine.singlePointCrossover(first, second, 2);

        assertThat(children[0]).containsExactly(0, 1, 7);
        assertThat(children[1]).containsExactly(9, 8, 2);
        assertThat(first).containsExactly(0, 1, 2);
        assertThat(second).containsExactly(9, 8, 7);
    }

    @Test
    void mutationUsesOnlyTheSuppliedRandomAndIncludesTheUnassignedGene() {
        ScheduleProblem problem = new ScheduleProblem(List.of(demand(1), demand(2)), List.of(lab(1)));
        CandidateCatalog catalog = CandidateCatalog.create(problem);
        SequenceRandom random = new SequenceRandom(0, 0);

        int[] mutated = GeneticEngine.mutate(new int[] {-1, 0}, catalog, 1, random);

        assertThat(mutated).containsExactly(0, -1);
    }

    @Test
    void tournamentReturnsTheBestSampleAndElitesRetainTheBestChromosomes() {
        GeneticEngine.EvaluatedChromosome weak = scored(0, 0.1);
        GeneticEngine.EvaluatedChromosome best = scored(1, 0.9);
        GeneticEngine.EvaluatedChromosome middle = scored(2, 0.5);
        List<GeneticEngine.EvaluatedChromosome> population = new ArrayList<>(List.of(weak, best, middle));

        GeneticEngine.EvaluatedChromosome selected = GeneticEngine.tournament(
                population, 3, new SequenceRandom(0, 1, 2));
        List<GeneticEngine.EvaluatedChromosome> elites = GeneticEngine.selectElites(population, 2);

        assertThat(selected).isSameAs(best);
        assertThat(elites).containsExactly(best, middle);
        assertThat(population).containsExactly(weak, best, middle);
    }

    @Test
    void conflictingGenesAreRepairedAndReceiveAnExplicitViolationPenalty() {
        ScheduleDemand first = ScheduleTestFixtures.withWindow(
                demand(1, 1, CREATED_AT, ReservationMode.ROOM, 60), 0, 120, 60);
        ScheduleDemand second = ScheduleTestFixtures.withWindow(
                demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.ROOM, 60), 0, 120, 60);
        ScheduleProblem problem = new ScheduleProblem(
                List.of(first, second), List.of(lab(1, 20, mask(0, 2), 0, lab(1).seats())));
        CandidateCatalog catalog = CandidateCatalog.create(problem);
        ScheduleParameters parameters = ScheduleParameters.builder()
                .successWeight(1)
                .utilizationWeight(0)
                .fragmentationWeight(0)
                .violationWeight(2)
                .build();

        GeneticEngine.EvaluatedChromosome conflicting = engine.evaluate(new int[] {0, 0}, catalog, parameters);
        GeneticEngine.EvaluatedChromosome repaired = engine.evaluate(new int[] {0, -1}, catalog, parameters);

        assertThat(conflicting.violations()).isEqualTo(1);
        assertThat(conflicting.repairedGenes()).containsExactly(0, -1);
        assertThat(conflicting.fitness()).isLessThan(repaired.fitness());
        assertThat(repaired.violations()).isZero();
    }

    @Test
    void geneticRepairAllowsStandardAndGpuOnMatchingTwoSeatResource() {
        ScheduleDemand standard = ScheduleTestFixtures.withSpec(
                demand(1, 1, CREATED_AT, ReservationMode.SEAT, 60), "STANDARD");
        ScheduleDemand gpu = ScheduleTestFixtures.withSpec(
                demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.SEAT, 60), "GPU");
        ScheduleLabResource resource = lab(1, 20, mask(16, 18), 0, List.of(
                seat(1, 1, "GPU", "A", true, 0),
                seat(2, 2, "STANDARD", "A", true, 0)));
        CandidateCatalog catalog = CandidateCatalog.create(
                new ScheduleProblem(List.of(standard, gpu), List.of(resource)));
        ScheduleParameters parameters = ScheduleParameters.builder()
                .utilizationWeight(0).fragmentationWeight(0).build();

        GeneticEngine.EvaluatedChromosome evaluated = engine.evaluate(new int[] {0, 0}, catalog, parameters);

        assertThat(evaluated.repairedGenes()).containsExactly(0, 0);
        assertThat(evaluated.violations()).isZero();
    }

    @Test
    void geneticRepairRejectsThirdMixedSpecDemandOnTwoSeatResource() {
        ScheduleDemand standard = ScheduleTestFixtures.withSpec(
                demand(1, 1, CREATED_AT, ReservationMode.SEAT, 60), "STANDARD");
        ScheduleDemand high = ScheduleTestFixtures.withSpec(
                demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.SEAT, 60), "HIGH");
        ScheduleDemand gpu = ScheduleTestFixtures.withSpec(
                demand(3, 3, CREATED_AT.plusMinutes(2), ReservationMode.SEAT, 60), "GPU");
        ScheduleLabResource resource = lab(1, 20, mask(16, 18), 0, List.of(
                seat(1, 1, "GPU", "A", true, 0),
                seat(2, 2, "STANDARD", "A", true, 0)));
        CandidateCatalog catalog = CandidateCatalog.create(
                new ScheduleProblem(List.of(standard, high, gpu), List.of(resource)));
        ScheduleParameters parameters = ScheduleParameters.builder()
                .utilizationWeight(0).fragmentationWeight(0).build();

        GeneticEngine.EvaluatedChromosome evaluated = engine.evaluate(new int[] {0, 0, 0}, catalog, parameters);

        assertThat(evaluated.repairedGenes()).containsExactly(0, 0, -1);
        assertThat(evaluated.violations()).isEqualTo(1);
    }

    @Test
    void noCandidateProducesOnlyTheUnassignedGeneAndAnUnresolvedReason() {
        ScheduleDemand outside = ScheduleTestFixtures.withWindow(demand(1), 0, 60, 60);
        ScheduleProblem problem = new ScheduleProblem(List.of(outside), List.of(lab(1)));

        SchedulePlan plan = engine.schedule(problem, smallParameters().build());

        assertThat(plan.assignments()).isEmpty();
        assertThat(plan.unresolvedReasons().get(1L)).contains("NO_FEASIBLE_CANDIDATE");
    }

    private static ScheduleParameters.Builder smallParameters() {
        return ScheduleParameters.builder()
                .populationSize(6)
                .generations(5)
                .tournamentSize(2)
                .seed(42);
    }

    private static ScheduleProblem injectionProblem() {
        ScheduleDemand longLowCredit = ScheduleTestFixtures.withCredit(
                ScheduleTestFixtures.withWindow(
                        demand(1, 1, CREATED_AT, ReservationMode.ROOM, 60), 0, 120, 60), 10);
        ScheduleDemand shortHighCredit1 = ScheduleTestFixtures.withCredit(
                ScheduleTestFixtures.withWindow(
                        demand(2, 2, CREATED_AT.plusMinutes(1), ReservationMode.ROOM, 30), 0, 120, 30), 100);
        ScheduleDemand shortHighCredit2 = ScheduleTestFixtures.withCredit(
                ScheduleTestFixtures.withWindow(
                        demand(3, 3, CREATED_AT.plusMinutes(2), ReservationMode.ROOM, 30), 0, 120, 30), 100);
        return new ScheduleProblem(
                List.of(longLowCredit, shortHighCredit1, shortHighCredit2),
                List.of(lab(1, 20, mask(0, 2), 0, lab(1).seats())));
    }

    private static ScheduleParameters.Builder injectionParameters() {
        return ScheduleParameters.builder()
                .urgencyWeight(0)
                .durationFitWeight(0)
                .creditWeight(1)
                .greedyFragmentationWeight(0)
                .successWeight(1)
                .utilizationWeight(0)
                .fragmentationWeight(0)
                .violationWeight(1)
                .tournamentSize(2)
                .seed(7);
    }

    private static GeneticEngine.EvaluatedChromosome scored(int gene, double fitness) {
        return new GeneticEngine.EvaluatedChromosome(
                new int[] {gene}, new int[] {gene}, fitness, 0);
    }

    private static final class SequenceRandom extends Random {
        private final int[] values;
        private int index;

        private SequenceRandom(int... values) {
            this.values = values;
        }

        @Override
        public int nextInt(int bound) {
            int value = Math.floorMod(values[index++], bound);
            return value;
        }

        @Override
        public double nextDouble() {
            return 0;
        }
    }
}
