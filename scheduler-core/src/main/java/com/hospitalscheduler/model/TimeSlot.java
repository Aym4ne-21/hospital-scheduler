package com.hospitalscheduler.model;

import java.time.LocalDateTime;

public class TimeSlot {

    private final LocalDateTime start;
    private final LocalDateTime end;

    public TimeSlot(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null ||
                !start.isBefore(end)) {
            throw new IllegalArgumentException(
                    "Start must be before end"
            );
        }

        this.start = start;
        this.end = end;
    }

    public LocalDateTime getStart() {
        return start;
    }

    public LocalDateTime getEnd() {
        return end;
    }


    public boolean overlaps(TimeSlot other) {
        if (other == null) {
            throw new IllegalArgumentException("Other time slot cannot be null");
        }

        return start.isBefore(other.end) && other.start.isBefore(end);
    }


    public boolean contains(TimeSlot other) {
        if (other == null) {
            throw new IllegalArgumentException(
                    "Other time slot cannot be null"
            );
        }

        return !other.start.isBefore(this.start)
                && !other.end.isAfter(this.end);
    }
}