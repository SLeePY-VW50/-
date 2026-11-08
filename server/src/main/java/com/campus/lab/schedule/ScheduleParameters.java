package com.campus.lab.schedule;

public record ScheduleParameters(
        double urgencyWeight,
        double durationFitWeight,
        double creditWeight,
        double greedyFragmentationWeight,
        double successWeight,
        double utilizationWeight,
        double fragmentationWeight,
        double violationWeight,
        int populationSize,
        int generations,
        int tournamentSize,
        double crossoverRate,
        double mutationRate,
        double eliteRatio,
        long seed) {

    public ScheduleParameters {
        requireWeight(urgencyWeight);
        requireWeight(durationFitWeight);
        requireWeight(creditWeight);
        requireWeight(greedyFragmentationWeight);
        requireWeight(successWeight);
        requireWeight(utilizationWeight);
        requireWeight(fragmentationWeight);
        requireWeight(violationWeight);
        if (populationSize <= 0 || generations < 0 || tournamentSize <= 0) {
            throw new IllegalArgumentException("遗传算法种群、代数或锦标赛参数无效");
        }
        if (!inUnitInterval(crossoverRate) || !inUnitInterval(mutationRate)
                || !Double.isFinite(eliteRatio) || eliteRatio <= 0 || eliteRatio > 1) {
            throw new IllegalArgumentException("交叉、变异或精英比例无效");
        }
    }

    public static ScheduleParameters defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    private static void requireWeight(double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("权重必须为有限非负数");
        }
    }

    private static boolean inUnitInterval(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    public static final class Builder {
        private double urgencyWeight = 1;
        private double durationFitWeight = 1;
        private double creditWeight = 1;
        private double greedyFragmentationWeight = 1;
        private double successWeight = 1;
        private double utilizationWeight = 1;
        private double fragmentationWeight = 1;
        private double violationWeight = 1;
        private int populationSize = 80;
        private int generations = 200;
        private int tournamentSize = 3;
        private double crossoverRate = 0.8;
        private double mutationRate = 0.05;
        private double eliteRatio = 0.05;
        private long seed;

        private Builder() {
        }

        public Builder urgencyWeight(double value) {
            urgencyWeight = value;
            return this;
        }

        public Builder durationFitWeight(double value) {
            durationFitWeight = value;
            return this;
        }

        public Builder creditWeight(double value) {
            creditWeight = value;
            return this;
        }

        public Builder greedyFragmentationWeight(double value) {
            greedyFragmentationWeight = value;
            return this;
        }

        public Builder successWeight(double value) {
            successWeight = value;
            return this;
        }

        public Builder utilizationWeight(double value) {
            utilizationWeight = value;
            return this;
        }

        public Builder fragmentationWeight(double value) {
            fragmentationWeight = value;
            return this;
        }

        public Builder violationWeight(double value) {
            violationWeight = value;
            return this;
        }

        public Builder populationSize(int value) {
            populationSize = value;
            return this;
        }

        public Builder generations(int value) {
            generations = value;
            return this;
        }

        public Builder tournamentSize(int value) {
            tournamentSize = value;
            return this;
        }

        public Builder crossoverRate(double value) {
            crossoverRate = value;
            return this;
        }

        public Builder mutationRate(double value) {
            mutationRate = value;
            return this;
        }

        public Builder eliteRatio(double value) {
            eliteRatio = value;
            return this;
        }

        public Builder seed(long value) {
            seed = value;
            return this;
        }

        public ScheduleParameters build() {
            return new ScheduleParameters(
                    urgencyWeight, durationFitWeight, creditWeight, greedyFragmentationWeight,
                    successWeight, utilizationWeight, fragmentationWeight, violationWeight,
                    populationSize, generations, tournamentSize, crossoverRate, mutationRate,
                    eliteRatio, seed);
        }
    }
}
