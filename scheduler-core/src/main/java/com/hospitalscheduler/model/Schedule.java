package com.hospitalscheduler.model;

import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class Schedule {

    private final List<Appointment> appointments;

    public Schedule(List<Appointment> appointments) {
        this.appointments = List.copyOf(appointments);

        Set<Long> requestIds = new HashSet<>();

        for (Appointment appointment : this.appointments) {

            long id = appointment.getRequest().getId();

            if (!requestIds.add(id)) {
                throw new IllegalArgumentException(
                        "Duplicate request ID: " + id
                );
            }
        }
    }

    public List<Appointment> getAppointments() {
        return appointments;
    }
}