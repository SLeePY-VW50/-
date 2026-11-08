package com.campus.lab.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleEngineTest {

    @Test
    void defaultsMatchTheGeneticAlgorithmSpecificationAndCanBeOverridden() {
        ScheduleParameters defaults = ScheduleParameters.defaults();

        assertThat(defaults.populationSize()).isEqualTo(80);
        assertThat(defaults.generations()).isEqualTo(200);
        assertThat(defaults.tournamentSize()).isEqualTo(3);
        assertThat(defaults.crossoverRate()).isEqualTo(0.8);
        assertThat(defaults.mutationRate()).isEqualTo(0.05);
        assertThat(defaults.eliteRatio()).isEqualTo(0.05);

        ScheduleParameters small = ScheduleParameters.builder()
                .populationSize(6)
                .generations(4)
                .tournamentSize(2)
                .seed(1234)
                .build();
        assertThat(small.populationSize()).isEqualTo(6);
        assertThat(small.generations()).isEqualTo(4);
        assertThat(small.tournamentSize()).isEqualTo(2);
        assertThat(small.seed()).isEqualTo(1234);
    }

    @Test
    void problemAndResourcesDefensivelyCopyCallerCollections() {
        var demands = new ArrayList<>(List.of(ScheduleTestFixtures.demand(1)));
        var seats = new ArrayList<>(ScheduleTestFixtures.lab(1).seats());
        var resources = new ArrayList<>(List.of(ScheduleTestFixtures.lab(
                1, 20, ScheduleTestFixtures.mask(16, 40), 0, seats)));
        ScheduleProblem problem = new ScheduleProblem(demands, resources);

        demands.clear();
        seats.clear();
        resources.clear();

        assertThat(problem.demands()).hasSize(1);
        assertThat(problem.resources()).hasSize(1);
        assertThat(problem.resources().getFirst().seats()).hasSize(2);
        assertThatThrownBy(() -> problem.demands().add(ScheduleTestFixtures.demand(2)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidWindowsDurationsAndDuplicateDemandIds() {
        assertThatThrownBy(() -> ScheduleTestFixtures.withWindow(
                ScheduleTestFixtures.demand(1), 480, 735, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleTestFixtures.withWindow(
                ScheduleTestFixtures.demand(1), 480, 720, 45))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleTestFixtures.withWindow(
                ScheduleTestFixtures.demand(1), 480, 720, 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleProblem(
                List.of(ScheduleTestFixtures.demand(1), ScheduleTestFixtures.demand(1)),
                List.of(ScheduleTestFixtures.lab(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidGeneticParameters() {
        assertThatThrownBy(() -> ScheduleParameters.builder().populationSize(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleParameters.builder().generations(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleParameters.builder().crossoverRate(1.1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleParameters.builder().eliteRatio(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fcfsEngineIsIndependentOfSpringAndExposesItsAlgorithmType() {
        ScheduleEngine engine = new FcfsEngine();
        assertThat(engine.algorithmType()).isEqualTo(AlgorithmType.FCFS);
    }

    @Test
    void threeEnginesShareOneSnapshotWithoutMutatingItOrEachOthersState() {
        ScheduleLabResource resource = ScheduleTestFixtures.lab(
                1, 20, ScheduleTestFixtures.mask(16, 40), ScheduleTestFixtures.mask(30, 32),
                List.of(
                        ScheduleTestFixtures.seat(11, 1, "STANDARD", "A", true,
                                ScheduleTestFixtures.mask(16, 17)),
                        ScheduleTestFixtures.seat(12, 2, "STANDARD", "A", true, 0)));
        ScheduleProblem problem = new ScheduleProblem(
                List.of(ScheduleTestFixtures.demand(1), ScheduleTestFixtures.demand(2)), List.of(resource));
        ScheduleParameters parameters = ScheduleParameters.builder()
                .populationSize(4).generations(3).tournamentSize(2).seed(99).build();
        long originalRoomMask = resource.roomMask();
        var originalSeatMasks = resource.seats().stream().map(seat -> seat.mask()).toList();

        SchedulePlan fcfs = new FcfsEngine().schedule(problem, parameters);
        SchedulePlan greedy = new GreedyEngine().schedule(problem, parameters);
        SchedulePlan genetic = new GeneticEngine().schedule(problem, parameters);
        SchedulePlan repeatedFcfs = new FcfsEngine().schedule(problem, parameters);

        assertThat(fcfs.algorithmType()).isEqualTo(AlgorithmType.FCFS);
        assertThat(greedy.algorithmType()).isEqualTo(AlgorithmType.GREEDY);
        assertThat(genetic.algorithmType()).isEqualTo(AlgorithmType.GENETIC);
        assertThat(repeatedFcfs).isEqualTo(fcfs);
        assertThat(resource.roomMask()).isEqualTo(originalRoomMask);
        assertThat(resource.seats().stream().map(seat -> seat.mask()).toList())
                .isEqualTo(originalSeatMasks);
    }
}
