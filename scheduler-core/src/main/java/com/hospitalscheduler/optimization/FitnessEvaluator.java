package com.hospitalscheduler.optimization;

import com.hospitalscheduler.model.Schedule;

public interface FitnessEvaluator {

    FitnessBreakdown evaluate(
            OptimizationProblem problem,
            Schedule schedule
    );
}
