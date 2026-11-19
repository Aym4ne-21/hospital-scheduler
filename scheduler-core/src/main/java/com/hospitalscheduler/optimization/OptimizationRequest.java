package com.hospitalscheduler.optimization;

import java.util.Objects;

public record OptimizationRequest(
        OptimizationProblem problem,
        OptimizationParameters parameters
) {
    public OptimizationRequest {
        Objects.requireNonNull(problem);
        Objects.requireNonNull(parameters);
    }
}
