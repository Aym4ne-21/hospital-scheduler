package com.hospitalscheduler.optimization;

@FunctionalInterface
public interface ProgressListener {
    void onProgress(OptimizationProgress progress);
}