package com.hospitalscheduler.optimization;

import com.hospitalscheduler.model.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleCompletenessValidatorTest {

    private final Patient patient = new Patient(1, "Ahmed");

    private final TimeSlot slot = new TimeSlot(
            LocalDateTime.of(2026, 11, 23, 9, 0),
            LocalDateTime.of(2026, 11, 23, 9, 30)
    );

    private final Doctor doctor = new Doctor(
            1, "Dr Ali", "Cardiology",
            List.of(slot), List.of()
    );

    private final Room room = new Room(
            1, "Room A", Set.of()
    );

    private AppointmentRequest request(long id) {
        return new AppointmentRequest(
                id, patient, "Cardiology", 30, 2,
                List.of(slot), Set.of()
        );
    }

    @Test
    void completeScheduleReturnsTrue() {
        AppointmentRequest r1 = request(1);
        AppointmentRequest r2 = request(2);

        OptimizationProblem problem =
                new OptimizationProblem(
                        List.of(doctor),
                        List.of(room),
                        List.of(r1, r2)
                );

        Schedule schedule = new Schedule(List.of(
                new Appointment(r1, doctor, room, slot),
                new Appointment(r2, doctor, room, slot)
        ));

        assertTrue(
                ScheduleCompletenessValidator.isComplete(
                        problem, schedule
                )
        );
    }

    @Test
    void missingRequestReturnsFalse() {
        AppointmentRequest r1 = request(1);
        AppointmentRequest r2 = request(2);

        OptimizationProblem problem =
                new OptimizationProblem(
                        List.of(doctor),
                        List.of(room),
                        List.of(r1, r2)
                );

        Schedule schedule = new Schedule(List.of(
                new Appointment(r1, doctor, room, slot)
        ));

        assertFalse(
                ScheduleCompletenessValidator.isComplete(
                        problem, schedule
                )
        );
    }

    @Test
    void unexpectedRequestReturnsFalse() {
        AppointmentRequest r1 = request(1);
        AppointmentRequest r2 = request(2);

        OptimizationProblem problem =
                new OptimizationProblem(
                        List.of(doctor),
                        List.of(room),
                        List.of(r1)
                );

        Schedule schedule = new Schedule(List.of(
                new Appointment(r1, doctor, room, slot),
                new Appointment(r2, doctor, room, slot)
        ));

        assertFalse(
                ScheduleCompletenessValidator.isComplete(
                        problem, schedule
                )
        );
    }
}
