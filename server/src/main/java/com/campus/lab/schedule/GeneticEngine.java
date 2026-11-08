package com.campus.lab.schedule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public final class GeneticEngine implements ScheduleEngine {

    @Override
    public AlgorithmType algorithmType() {
        return AlgorithmType.GENETIC;
    }

    @Override
    public SchedulePlan schedule(ScheduleProblem problem, ScheduleParameters parameters) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(parameters, "parameters");
        CandidateCatalog catalog = CandidateCatalog.create(problem);
        Random random = new Random(parameters.seed());
        List<EvaluatedChromosome> population = initialPopulation(catalog, parameters, random);
        int eliteCount = Math.min(parameters.populationSize(),
                Math.max(1, (int) Math.ceil(parameters.populationSize() * parameters.eliteRatio())));
        for (int generation = 0; generation < parameters.generations(); generation++) {
            List<EvaluatedChromosome> next = new ArrayList<>(selectElites(population, eliteCount));
            while (next.size() < parameters.populationSize()) {
                EvaluatedChromosome firstParent = tournament(
                        population, parameters.tournamentSize(), random);
                EvaluatedChromosome secondParent = tournament(
                        population, parameters.tournamentSize(), random);
                int[][] children = {firstParent.genes().clone(), secondParent.genes().clone()};
                if (catalog.demandCount() > 1 && random.nextDouble() < parameters.crossoverRate()) {
                    int point = 1 + random.nextInt(catalog.demandCount() - 1);
                    children = singlePointCrossover(firstParent.genes(), secondParent.genes(), point);
                }
                for (int[] child : children) {
                    if (next.size() >= parameters.populationSize()) {
                        break;
                    }
                    int[] mutated = mutate(child, catalog, parameters.mutationRate(), random);
                    next.add(evaluate(mutated, catalog, parameters));
                }
            }
            population = List.copyOf(next);
        }
        return toPlan(selectElites(population, 1).getFirst(), catalog);
    }

    EvaluatedChromosome evaluate(
            int[] genes, CandidateCatalog catalog, ScheduleParameters parameters) {
        if (genes.length != catalog.demandCount()) {
            throw new IllegalArgumentException("染色体长度必须等于需求数量");
        }
        int[] repaired = new int[genes.length];
        Arrays.fill(repaired, -1);
        ScheduleResourceState state = catalog.freshResourceState();
        int assigned = 0;
        int violations = 0;
        for (int demandIndex : catalog.repairOrder()) {
            int gene = genes[demandIndex];
            if (gene == -1) {
                continue;
            }
            if (gene < 0 || gene >= catalog.candidateCount(demandIndex)) {
                violations++;
                continue;
            }
            ScheduleDemand demand = catalog.demand(demandIndex);
            ScheduleCandidate candidate = catalog.candidates(demandIndex).get(gene);
            if (!state.canOccupy(demand, candidate)) {
                violations++;
                continue;
            }
            state.occupy(demand, candidate);
            repaired[demandIndex] = gene;
            assigned++;
        }
        double success = genes.length == 0 ? 0 : (double) assigned / genes.length;
        double violationRate = genes.length == 0 ? 0 : (double) violations / genes.length;
        double fitness = parameters.successWeight() * success
                + parameters.utilizationWeight() * state.utilization()
                - parameters.fragmentationWeight() * state.estimatedFragmentation()
                - parameters.violationWeight() * violationRate;
        return new EvaluatedChromosome(genes, repaired, fitness, violations);
    }

    static int[][] singlePointCrossover(int[] first, int[] second, int point) {
        if (first.length != second.length || point < 0 || point > first.length) {
            throw new IllegalArgumentException("交叉染色体长度或交叉点无效");
        }
        int[] firstChild = first.clone();
        int[] secondChild = second.clone();
        for (int index = point; index < first.length; index++) {
            firstChild[index] = second[index];
            secondChild[index] = first[index];
        }
        return new int[][] {firstChild, secondChild};
    }

    static int[] mutate(
            int[] genes, CandidateCatalog catalog, double mutationRate, Random random) {
        int[] mutated = genes.clone();
        for (int demandIndex = 0; demandIndex < genes.length; demandIndex++) {
            if (random.nextDouble() >= mutationRate) {
                continue;
            }
            int optionCount = catalog.candidateCount(demandIndex) + 1;
            if (optionCount <= 1) {
                continue;
            }
            int currentOption = genes[demandIndex] + 1;
            int selected = random.nextInt(optionCount - 1);
            if (selected >= currentOption) {
                selected++;
            }
            mutated[demandIndex] = selected - 1;
        }
        return mutated;
    }

    static EvaluatedChromosome tournament(
            List<EvaluatedChromosome> population, int tournamentSize, Random random) {
        EvaluatedChromosome best = null;
        int samples = Math.min(tournamentSize, population.size());
        for (int sample = 0; sample < samples; sample++) {
            EvaluatedChromosome candidate = population.get(random.nextInt(population.size()));
            if (best == null || compare(candidate, best) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    static List<EvaluatedChromosome> selectElites(
            List<EvaluatedChromosome> population, int eliteCount) {
        return population.stream().sorted(GeneticEngine::compare).limit(eliteCount).toList();
    }

    private static List<EvaluatedChromosome> initialPopulation(
            CandidateCatalog catalog, ScheduleParameters parameters, Random random) {
        List<int[]> chromosomes = new ArrayList<>();
        chromosomes.add(catalog.genesFor(DeterministicScheduler.fcfs(catalog)));
        if (parameters.populationSize() > 1) {
            chromosomes.add(catalog.genesFor(DeterministicScheduler.greedy(catalog, parameters)));
        }
        while (chromosomes.size() < parameters.populationSize()) {
            int[] genes = new int[catalog.demandCount()];
            for (int demandIndex = 0; demandIndex < genes.length; demandIndex++) {
                genes[demandIndex] = random.nextInt(catalog.candidateCount(demandIndex) + 1) - 1;
            }
            chromosomes.add(genes);
        }
        return chromosomes.stream()
                .map(genes -> new GeneticEngine().evaluate(genes, catalog, parameters))
                .toList();
    }

    private static SchedulePlan toPlan(EvaluatedChromosome best, CandidateCatalog catalog) {
        List<ScheduleAssignment> assignments = new ArrayList<>();
        Map<Long, List<String>> unresolved = new HashMap<>();
        for (int demandIndex = 0; demandIndex < catalog.demandCount(); demandIndex++) {
            ScheduleDemand demand = catalog.demand(demandIndex);
            int repairedGene = best.repairedGenes()[demandIndex];
            if (repairedGene >= 0) {
                assignments.add(catalog.candidates(demandIndex).get(repairedGene).assignment(demand.resvId()));
                continue;
            }
            String reason;
            if (catalog.candidateCount(demandIndex) == 0) {
                reason = "NO_FEASIBLE_CANDIDATE";
            } else if (best.genes()[demandIndex] >= 0) {
                reason = "RESOURCE_OR_USER_CONFLICT";
            } else {
                reason = "NOT_SELECTED_BY_GENETIC_SEARCH";
            }
            unresolved.put(demand.resvId(), List.of(reason));
        }
        return new SchedulePlan(AlgorithmType.GENETIC, assignments, unresolved);
    }

    private static int compare(EvaluatedChromosome first, EvaluatedChromosome second) {
        int fitness = Double.compare(second.fitness(), first.fitness());
        if (fitness != 0) {
            return fitness;
        }
        int violations = Integer.compare(first.violations(), second.violations());
        if (violations != 0) {
            return violations;
        }
        int repaired = Arrays.compare(first.repairedGenes(), second.repairedGenes());
        return repaired != 0 ? repaired : Arrays.compare(first.genes(), second.genes());
    }

    record EvaluatedChromosome(int[] genes, int[] repairedGenes, double fitness, int violations) {
        EvaluatedChromosome {
            genes = genes.clone();
            repairedGenes = repairedGenes.clone();
        }
    }
}
