package com.hospitalscheduler.model;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleTest {

    @Test
    void shouldRejectDuplicateRequests() {

        Patient patient = new Patient(1, "Ahmed");

        TimeSlot slot = new TimeSlot(
                LocalDateTime.of(2026, 11, 23, 9, 0),
                LocalDateTime.of(2026, 11, 23, 9, 30)
        );

        AppointmentRequest request = new AppointmentRequest(1, patient, "Cardiology", 30, 2, List.of(slot), Set.of()
        );

        Doctor doctor = new Doctor(
                1, "Dr Ali", "Cardiology",
                List.of(slot), List.of()
        );

        Room room = new Room(1, "Room A", Set.of());

        Appointment appointment1 =
                new Appointment(request, doctor, room, slot);

        Appointment appointment2 =
                new Appointment(request, doctor, room, slot);

        assertThrows(
                IllegalArgumentException.class,
                () -> new Schedule(
                        List.of(appointment1, appointment2)
                )
        );
    }
}
