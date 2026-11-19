package com.hospitalscheduler.model;

import java.util.Objects;

public class Appointment {

    private final AppointmentRequest request;
    private final Doctor doctor;
    private final Room room;
    private final TimeSlot timeSlot;

    public Appointment(
            AppointmentRequest request,
            Doctor doctor,
            Room room,
            TimeSlot timeSlot) {

        this.request = Objects.requireNonNull(request);
        this.doctor = Objects.requireNonNull(doctor);
        this.room = Objects.requireNonNull(room);
        this.timeSlot = Objects.requireNonNull(timeSlot);
    }

    public AppointmentRequest getRequest() {
        return request;
    }

    public Doctor getDoctor() {
        return doctor;
    }

    public Room getRoom() {
        return room;
    }

    public TimeSlot getTimeSlot() {
        return timeSlot;
    }
}