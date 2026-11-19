package com.hospitalscheduler.optimization;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FakeOptimizerTest {

    @Test
    void fakeOptimizerReturnsValidResultAndReportsProgress() {

        // 1. Create the optimizer
        Optimizer optimizer = new FakeOptimizer();

        // 2. Create an empty hospital problem
        OptimizationProblem problem =
                new OptimizationProblem(
                        List.of(),
                        List.of(),
                        List.of()
                );

        // 3. Define execution parameters
        OptimizationParameters parameters =
                new OptimizationParameters(42L, 1000);

        // 4. Create the optimization request
        OptimizationRequest request =
                new OptimizationRequest(problem, parameters);

        // 5. Prepare a list to receive progress updates
        List<OptimizationProgress> updates =
                new ArrayList<>();

        // 6. Run the fake optimizer
        OptimizationResult result =
                optimizer.optimize(request, updates::add);

        // 7. Verify the final result
        assertEquals("Fake Optimizer", optimizer.getName());
        assertNotNull(result);
        assertNotNull(result.bestSchedule());
        assertNotNull(result.bestFitness());

        assertEquals(0, result.bestFitness().hardViolations());
        assertEquals(0.0, result.bestFitness().softCost());

        assertEquals(1, result.history().size());
        assertEquals("COMPLETED", result.status());

        // 8. Verify the progress listener
        assertEquals(1, updates.size());
        assertEquals(0, updates.get(0).iteration());

        assertEquals(
                result.bestFitness(),
                updates.get(0).bestFitness()
        );
    }
}
