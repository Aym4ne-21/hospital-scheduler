package com.hospitalscheduler.model;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class TimeSlotTest {

    @Test
    void overlappingSlotsShouldReturnTrue() {
        TimeSlot first = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 9, 0),
                LocalDateTime.of(2026, 11, 23, 10, 0)
        );

        TimeSlot second = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 9, 30),
                LocalDateTime.of(2026, 11, 23, 10, 30)
        );

        assertTrue(first.overlaps(second));
    }

    @Test
    void consecutiveSlotsShouldNotOverlap() {
        TimeSlot first = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 9, 0),
                LocalDateTime.of(2026, 11, 23, 10, 0)
        );

        TimeSlot second = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 10, 0),
                LocalDateTime.of(2026, 11, 23, 11, 0)
        );

        assertFalse(first.overlaps(second));
    }


    @Test
    void containedSlotShouldReturnTrue() {
        TimeSlot workingHours = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 9, 0),
                LocalDateTime.of(2026, 11, 23, 17, 0)
        );

        TimeSlot appointment = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 10, 0),
                LocalDateTime.of(2026, 11, 23, 10, 30)
        );

        assertTrue(workingHours.contains(appointment));
    }

    @Test
    void PartiallyOutsideSlotShouldReturnFalse() {
        TimeSlot workingHours = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 9, 0),
                LocalDateTime.of(2026, 11, 23, 17, 0)
        );

        TimeSlot appointment = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 16, 30),
                LocalDateTime.of(2026, 11, 23, 17, 30)
        );

        assertFalse(workingHours.contains(appointment));
    }



}