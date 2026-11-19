package com.hospitalscheduler.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FitnessBreakdownTest {

    @Test
    void storesFitnessValues() {
        FitnessBreakdown fitness =
                new FitnessBreakdown(2, 15.5);

        assertEquals(2, fitness.hardViolations());
        assertEquals(15.5, fitness.softCost());
    }

    @Test
    void rejectsNegativeHardViolations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FitnessBreakdown(-1, 10.0)
        );
    }

    @Test
    void rejectsNegativeSoftCost() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FitnessBreakdown(0, -5.0)
        );
    }

    @Test
    void rejectsInvalidSoftCost() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FitnessBreakdown(
                        0, Double.NaN
                )
        );
    }


    @Test
    void fewerHardViolationsIsBetter() {
        FitnessBreakdown a = new FitnessBreakdown(0, 50);
        FitnessBreakdown b = new FitnessBreakdown(2, 10);

        assertTrue(a.isBetterThan(b));
        assertFalse(b.isBetterThan(a));
    }

    @Test
    void lowerSoftCostWinsWhenHardViolationsEqual() {
        FitnessBreakdown a = new FitnessBreakdown(0, 10);
        FitnessBreakdown b = new FitnessBreakdown(0, 20);

        assertTrue(a.isBetterThan(b));
    }

    @Test
    void equalFitnessIsNotBetter() {
        FitnessBreakdown a = new FitnessBreakdown(0, 10);
        FitnessBreakdown b = new FitnessBreakdown(0, 10);

        assertFalse(a.isBetterThan(b));
    }



}
