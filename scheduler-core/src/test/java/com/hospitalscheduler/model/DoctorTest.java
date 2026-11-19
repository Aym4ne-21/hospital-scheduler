package com.hospitalscheduler.model;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DoctorTest {

    @Test
    void doctorAvailabilityRespectsWorkingHoursAndBreaks() {

        TimeSlot workingHours = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 9, 0),
                LocalDateTime.of(2026, 11, 23, 17, 0)
        );

        TimeSlot lunchBreak = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 12, 0),
                LocalDateTime.of(2026, 11, 23, 13, 0)
        );

        Doctor doctor = new Doctor(
                1,
                "Dr. Ahmed",
                "Cardiology",
                List.of(workingHours),
                List.of(lunchBreak)
        );

        TimeSlot validAppointment = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 10, 0),
                LocalDateTime.of(2026, 11, 23, 10, 30)
        );

        TimeSlot duringBreak = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 12, 15),
                LocalDateTime.of(2026, 11, 23, 12, 45)
        );

        TimeSlot outsideWorkingHours = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 16, 30),
                LocalDateTime.of(2026, 11, 23, 17, 30)
        );

        assertTrue(doctor.isAvailable(validAppointment));
        assertFalse(doctor.isAvailable(duringBreak));
        assertFalse(doctor.isAvailable(outsideWorkingHours));
    }
}