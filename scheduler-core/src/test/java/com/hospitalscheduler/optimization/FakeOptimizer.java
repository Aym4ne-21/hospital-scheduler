package com.hospitalscheduler.optimization;

import com.hospitalscheduler.model.Schedule;

import java.util.List;
import java.util.Objects;

public class FakeOptimizer implements Optimizer {

    @Override
    public String getName() {
        return "Fake Optimizer";
    }

    @Override
    public OptimizationResult optimize(
            OptimizationRequest request,
            ProgressListener listener
    ) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(listener);

        // Create an empty schedule
        Schedule schedule = new Schedule(List.of());

        // Artificial fitness for testing
        FitnessBreakdown fitness =
                new FitnessBreakdown(0, 0.0);

        // Send one progress update
        listener.onProgress(
                new OptimizationProgress(0, fitness)
        );

        // Return the final result
        return new OptimizationResult(
                schedule,
                fitness,
                List.of(fitness),
                "COMPLETED"
        );
    }
}
