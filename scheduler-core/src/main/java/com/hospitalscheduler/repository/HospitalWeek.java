package com.hospitalscheduler.repository;

import java.time.*;
import java.util.List;
import java.util.Objects;

/** Lossless editing data; codes identify references and id=0 denotes a new entity. */
public record HospitalWeek(Hospital hospital, LocalDate startsOn,
                           List<Department> departments, List<Specialty> specialties,
                           List<Doctor> doctors, List<RoomType> roomTypes, List<Equipment> equipment,
                           List<Room> rooms, List<Patient> patients, List<Request> requests) {
    public HospitalWeek {
        Objects.requireNonNull(hospital);
        requireMonday(startsOn);
        departments = List.copyOf(departments);
        specialties = List.copyOf(specialties);
        doctors = List.copyOf(doctors);
        roomTypes = List.copyOf(roomTypes);
        equipment = List.copyOf(equipment);
        rooms = List.copyOf(rooms);
        patients = List.copyOf(patients);
        requests = List.copyOf(requests);
    }

    public static void requireMonday(LocalDate date) {
        if (Objects.requireNonNull(date).getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("The week must start on Monday");
        }
    }

    public Instant start() { return startsOn.atStartOfDay(hospital.timeZone()).toInstant(); }
    public Instant end() { return startsOn.plusWeeks(1).atStartOfDay(hospital.timeZone()).toInstant(); }

    public record Hospital(long id, String code, String name, ZoneId timeZone) {
        public Hospital { Objects.requireNonNull(timeZone); }
    }
    public record Department(long id, String code, String name) { }
    public record Specialty(long id, String code, String departmentCode, String name) { }
    public record RoomType(long id, String code, String name) { }
    public record Equipment(long id, String code, String name) { }
    public record EquipmentQuantity(String equipmentCode, int quantity) {
        public EquipmentQuantity {
            if (quantity <= 0) throw new IllegalArgumentException("Equipment quantity must be positive");
        }
    }
    public record Window(Instant startsAt, Instant endsAt) {
        public Window {
            if (!Objects.requireNonNull(startsAt).isBefore(Objects.requireNonNull(endsAt))) {
                throw new IllegalArgumentException("Window start must precede end");
            }
            requireMicroseconds(startsAt.getNano());
            requireMicroseconds(endsAt.getNano());
        }
    }
    public record Absence(Window window, String reason) { }
    public record DoctorBreak(Window window, String label) { }
    public record Doctor(long id, String code, String departmentCode, String displayName, String email,
                         List<String> specialtyCodes, List<Window> availability,
                         List<Absence> absences, List<DoctorBreak> breaks) {
        public Doctor {
            specialtyCodes = List.copyOf(specialtyCodes);
            availability = List.copyOf(availability);
            absences = List.copyOf(absences);
            breaks = List.copyOf(breaks);
        }
    }
    public record Room(long id, String code, String departmentCode, String roomTypeCode,
                       String name, List<EquipmentQuantity> equipment) {
        public Room { equipment = List.copyOf(equipment); }
    }
    public record Preference(DayOfWeek weekday, LocalTime startsAt, LocalTime endsAt, int weight) {
        public Preference {
            Objects.requireNonNull(weekday);
            if (!Objects.requireNonNull(startsAt).isBefore(Objects.requireNonNull(endsAt)) || weight <= 0) {
                throw new IllegalArgumentException("Preference must have positive duration and weight");
            }
            requireMicroseconds(startsAt.getNano());
            requireMicroseconds(endsAt.getNano());
        }
    }
    public record Patient(long id, String code, String displayName, String email,
                          List<Window> availability, List<Preference> preferences) {
        public Patient {
            availability = List.copyOf(availability);
            preferences = List.copyOf(preferences);
        }
    }
    public enum Urgency { URGENT, PRIORITY, ROUTINE }
    public enum RequestStatus { OPEN, CANCELLED, CLOSED }
    public record Request(long id, String code, String patientCode, String specialtyCode,
                          String requiredRoomTypeCode, int durationMinutes, Urgency urgency,
                          Instant requestedAt, RequestStatus status,
                          List<Window> availability, List<EquipmentQuantity> equipment) {
        public Request {
            Objects.requireNonNull(urgency);
            Objects.requireNonNull(requestedAt);
            requireMicroseconds(requestedAt.getNano());
            Objects.requireNonNull(status);
            if (durationMinutes < 1 || durationMinutes > 1440) {
                throw new IllegalArgumentException("Request duration must be between 1 and 1440 minutes");
            }
            availability = List.copyOf(availability);
            equipment = List.copyOf(equipment);
        }
    }

    private static void requireMicroseconds(int nanos) {
        if (nanos % 1000 != 0) throw new IllegalArgumentException("Times must use PostgreSQL microsecond precision");
    }
}
