package com.hospitalscheduler.model;

import java.util.List;
import java.util.Set;

public class AppointmentRequest {

    private final long id;
    private final Patient patient;
    private final String specialty;
    private final int durationMinutes;
    private final int urgency;
    private final List<TimeSlot> availableSlots;
    private final Set<String> requiredEquipment;

    public AppointmentRequest(
            long id,
            Patient patient,
            String specialty,
            int durationMinutes,
            int urgency,
            List<TimeSlot> availableSlots,
            Set<String> requiredEquipment) {

        this.id = id;
        this.patient = patient;
        this.specialty = specialty;
        this.durationMinutes = durationMinutes;
        this.urgency = urgency;
        this.availableSlots = List.copyOf(availableSlots);
        this.requiredEquipment = Set.copyOf(requiredEquipment);
    }

    public long getId() {
        return id;
    }

    public Patient getPatient() {
        return patient;
    }

    public String getSpecialty() {
        return specialty;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public int getUrgency() {
        return urgency;
    }

    public List<TimeSlot> getAvailableSlots() {
        return availableSlots;
    }

    public Set<String> getRequiredEquipment() {
        return requiredEquipment;
    }
}