package com.hospitalscheduler.optimization;

import java.util.Objects;

public record OptimizationProgress(
        int iteration,
        FitnessBreakdown bestFitness
) {
    public OptimizationProgress {
        if (iteration < 0) {
            throw new IllegalArgumentException(
                    "Iteration cannot be negative"
            );
        }

        Objects.requireNonNull(bestFitness);
    }
}
