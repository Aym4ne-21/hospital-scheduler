package com.hospitalscheduler.optimization;

public record FitnessBreakdown(
        int hardViolations,
        double softCost
) {
    public FitnessBreakdown {
        if (hardViolations < 0) {
            throw new IllegalArgumentException(
                    "Hard violations cannot be negative"
            );
        }

        if (!Double.isFinite(softCost) || softCost < 0) {
            throw new IllegalArgumentException(
                    "Soft cost must be finite and non-negative"
            );
        }
    }


    public boolean isBetterThan(FitnessBreakdown other) {
        if (other == null) {
            throw new IllegalArgumentException(
                    "Other fitness cannot be null"
            );
        }

        if (this.hardViolations != other.hardViolations) {
            return this.hardViolations < other.hardViolations;
        }

        return this.softCost < other.softCost;
    }

}
