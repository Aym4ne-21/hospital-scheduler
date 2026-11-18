package com.hospitalscheduler.optimization;

public interface Optimizer {
    String getName();

    OptimizationResult optimize(
            OptimizationRequest request,
            ProgressListener listener
    );
}
