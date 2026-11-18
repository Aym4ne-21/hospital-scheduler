package com.hospitalscheduler.model;
import java.util.List;

public class Doctor {

    private final long id;
    private final String name;
    private final String specialty;
    private final List<TimeSlot> workingHours;
    private final List<TimeSlot> unavailableSlots;

    public Doctor(long id, String name, String specialty,List<TimeSlot> workingHours,List<TimeSlot> unavailableSlots) {
        this.id = id;
        this.name = name;
        this.specialty = specialty;
        this.workingHours = List.copyOf(workingHours);
        this.unavailableSlots = List.copyOf(unavailableSlots);
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSpecialty() {
        return specialty;
    }

    public List<TimeSlot> getWorkingHours() {
        return workingHours;
    }

    public List<TimeSlot> getUnavailableSlots() {
        return unavailableSlots;
    }

    public boolean isAvailable(TimeSlot slot) {
        if (slot == null) {
            throw new IllegalArgumentException(
                    "Time slot cannot be null"
            );
        }

        boolean withinWorkingHours = workingHours.stream()
                .anyMatch(workingSlot -> workingSlot.contains(slot));

        boolean overlapsUnavailability = unavailableSlots.stream()
                .anyMatch(unavailableSlot -> unavailableSlot.overlaps(slot));

        return withinWorkingHours && !overlapsUnavailability;
    }
}