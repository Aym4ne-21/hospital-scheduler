package com.hospitalscheduler.optimization;

import com.hospitalscheduler.model.Schedule;
import java.util.List;
import java.util.Objects;

public record OptimizationResult(
        Schedule bestSchedule,
        FitnessBreakdown bestFitness,
        List<FitnessBreakdown> history,
        String status
) {
    public OptimizationResult {
        Objects.requireNonNull(bestSchedule);
        Objects.requireNonNull(bestFitness);
        Objects.requireNonNull(history);
        Objects.requireNonNull(status);

        history = List.copyOf(history);
    }
}
