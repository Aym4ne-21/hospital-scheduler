package com.hospitalscheduler.persistence;

import com.hospitalscheduler.model.*;
import com.hospitalscheduler.optimization.OptimizationProblem;
import com.hospitalscheduler.repository.HospitalWeek;
import com.hospitalscheduler.repository.HospitalWeek.Urgency;
import com.hospitalscheduler.repository.ScheduleSnapshot;

import java.time.*;
import java.util.*;
import java.util.function.ToLongFunction;

/** Converts editing/storage data into the existing shared core without discarding unsupported requirements. */
public final class CoreModelMapper {
    private final Map<Urgency, Integer> urgencyValues;
    private final Map<String, String> selectedSpecialties;

    /** Integration convention: ROUTINE=1, PRIORITY=2, URGENT=3. */
    public CoreModelMapper() {
        this(Map.of(Urgency.ROUTINE, 1, Urgency.PRIORITY, 2, Urgency.URGENT, 3), Map.of());
    }

    /** Specialty selections are doctor code -> specialty code; each must be a stored qualification. */
    public CoreModelMapper(Map<Urgency, Integer> urgencyValues, Map<String, String> selectedSpecialties) {
        this.urgencyValues = Map.copyOf(urgencyValues);
        this.selectedSpecialties = Map.copyOf(selectedSpecialties);
        if (!this.urgencyValues.keySet().containsAll(EnumSet.allOf(Urgency.class))
                || new HashSet<>(this.urgencyValues.values()).size() != Urgency.values().length) {
            throw new IllegalArgumentException("Provide a distinct integer for each urgency level");
        }
    }

    public OptimizationProblem toProblem(HospitalWeek week) {
        Objects.requireNonNull(week);
        List<Doctor> doctors = new ArrayList<>();
        for (HospitalWeek.Doctor d : week.doctors()) {
            String specialty = selectedSpecialties.get(d.code());
            if (specialty == null) {
                if (d.specialtyCodes().size() != 1) {
                    throw new IllegalArgumentException("Doctor " + d.code()
                            + " needs exactly one specialty for the shared core, or an explicit specialty selection");
                }
                specialty = d.specialtyCodes().get(0);
            }
            if (!d.specialtyCodes().contains(specialty)) {
                throw new IllegalArgumentException("Selected specialty is not a qualification of " + d.code());
            }
            List<HospitalWeek.Window> unavailable = new ArrayList<>();
            d.absences().forEach(a -> unavailable.add(a.window()));
            d.breaks().forEach(b -> unavailable.add(b.window()));
            doctors.add(new Doctor(d.id(), d.displayName(), specialty,
                    slots(d.availability(), week), slots(unavailable, week)));
        }
        List<Room> rooms = week.rooms().stream().map(r -> new Room(r.id(), r.name(), equipment(r.equipment()))).toList();
        Map<String, HospitalWeek.Patient> patientData = new HashMap<>();
        Map<String, Patient> patients = new HashMap<>();
        for (HospitalWeek.Patient p : week.patients()) {
            patientData.put(p.code(), p);
            patients.put(p.code(), new Patient(p.id(), p.displayName()));
        }
        List<AppointmentRequest> requests = new ArrayList<>();
        for (HospitalWeek.Request r : week.requests()) {
            if (r.status() != HospitalWeek.RequestStatus.OPEN) continue;
            HospitalWeek.Patient patient = patientData.get(r.patientCode());
            if (patient == null) throw new IllegalArgumentException("Missing patient for request " + r.code());
            if (!patient.preferences().isEmpty()) {
                throw new IllegalArgumentException("Patient preferences for " + r.patientCode()
                        + " cannot be represented by the current shared core");
            }
            if (r.equipment().stream().anyMatch(e -> e.quantity() != 1)) {
                throw new IllegalArgumentException("Equipment quantities above one on " + r.code()
                        + " cannot be represented by the shared core's equipment set");
            }
            if (r.requiredRoomTypeCode() != null && week.rooms().stream()
                    .anyMatch(room -> !r.requiredRoomTypeCode().equals(room.roomTypeCode()))) {
                throw new IllegalArgumentException("Per-request room type on " + r.code()
                        + " cannot be represented when the shared core contains other room types");
            }
            List<TimeSlot> availability = slots(r.availability(), week);
            if (availability.isEmpty()) throw new IllegalArgumentException("Request " + r.code() + " has no availability in this week");
            requests.add(new AppointmentRequest(r.id(), patients.get(r.patientCode()), r.specialtyCode(),
                    r.durationMinutes(), urgencyValues.get(r.urgency()), availability, equipment(r.equipment())));
        }
        return new OptimizationProblem(doctors, rooms, requests);
    }

    public List<ScheduleSnapshot.Assignment> toAssignments(Schedule schedule, ZoneId zone, Set<Long> locks) {
        Objects.requireNonNull(schedule);
        locks = Set.copyOf(locks);
        Set<Long> included = new HashSet<>();
        List<ScheduleSnapshot.Assignment> result = new ArrayList<>();
        for (Appointment a : schedule.getAppointments()) {
            long request = a.getRequest().getId();
            included.add(request);
            result.add(new ScheduleSnapshot.Assignment(request, a.getDoctor().getId(), a.getRoom().getId(),
                    toInstant(a.getTimeSlot().getStart(), zone), toInstant(a.getTimeSlot().getEnd(), zone), locks.contains(request)));
        }
        if (!included.containsAll(locks)) throw new IllegalArgumentException("A lock refers to a request outside the schedule");
        return List.copyOf(result);
    }

    public Schedule toSchedule(ScheduleSnapshot snapshot, OptimizationProblem problem, ZoneId zone) {
        Map<Long, Doctor> doctors = index(problem.getDoctors(), Doctor::getId);
        Map<Long, Room> rooms = index(problem.getRooms(), Room::getId);
        Map<Long, AppointmentRequest> requests = index(problem.getRequests(), AppointmentRequest::getId);
        List<Appointment> appointments = new ArrayList<>();
        for (ScheduleSnapshot.Assignment a : snapshot.assignments()) {
            appointments.add(new Appointment(reference(requests, a.requestId(), "request"),
                    reference(doctors, a.doctorId(), "doctor"), reference(rooms, a.roomId(), "room"),
                    new TimeSlot(toLocal(a.startsAt(), zone), toLocal(a.endsAt(), zone))));
        }
        return new Schedule(appointments);
    }

    public static Instant toInstant(LocalDateTime local, ZoneId zone) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(Objects.requireNonNull(local));
        if (offsets.size() != 1) {
            throw new IllegalArgumentException("Ambiguous or nonexistent local time " + local + " in " + zone);
        }
        return local.toInstant(offsets.get(0));
    }

    public static LocalDateTime toLocal(Instant instant, ZoneId zone) {
        LocalDateTime local = LocalDateTime.ofInstant(instant, zone);
        // The shared TimeSlot cannot retain which offset was intended during a repeated hour.
        if (zone.getRules().getValidOffsets(local).size() != 1) {
            throw new IllegalArgumentException("Instant maps to an ambiguous shared-core local time: " + local + " in " + zone);
        }
        return local;
    }

    private static List<TimeSlot> slots(List<HospitalWeek.Window> windows, HospitalWeek week) {
        List<TimeSlot> result = new ArrayList<>();
        for (HospitalWeek.Window w : windows) {
            Instant start = w.startsAt().isBefore(week.start()) ? week.start() : w.startsAt();
            Instant end = w.endsAt().isAfter(week.end()) ? week.end() : w.endsAt();
            if (start.isBefore(end)) result.add(new TimeSlot(toLocal(start, week.hospital().timeZone()), toLocal(end, week.hospital().timeZone())));
        }
        result.sort(Comparator.comparing(TimeSlot::getStart).thenComparing(TimeSlot::getEnd));
        return List.copyOf(result);
    }

    private static Set<String> equipment(List<HospitalWeek.EquipmentQuantity> equipment) {
        Set<String> result = new HashSet<>();
        equipment.forEach(e -> result.add(e.equipmentCode()));
        return Set.copyOf(result);
    }

    private static <T> Map<Long, T> index(List<T> objects, ToLongFunction<T> id) {
        Map<Long, T> result = new HashMap<>();
        for (T object : objects) {
            if (result.put(id.applyAsLong(object), object) != null) throw new IllegalArgumentException("Duplicate shared-core ID");
        }
        return result;
    }

    private static <T> T reference(Map<Long, T> objects, long id, String type) {
        T object = objects.get(id);
        if (object == null) throw new IllegalArgumentException("The problem does not contain " + type + " " + id);
        return object;
    }
}
