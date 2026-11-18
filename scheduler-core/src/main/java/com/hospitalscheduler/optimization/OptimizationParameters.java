package com.hospitalscheduler.optimization;

public record OptimizationParameters(
        long seed,
        int maxIterations
) {
    public OptimizationParameters {
        if (maxIterations < 0) {
            throw new IllegalArgumentException(
                    "Maximum iterations cannot be negative"
            );
        }
    }
}
