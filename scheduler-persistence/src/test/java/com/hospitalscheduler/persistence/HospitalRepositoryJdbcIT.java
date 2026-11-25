package com.hospitalscheduler.persistence;

import com.hospitalscheduler.repository.HospitalRepositoryException;
import com.hospitalscheduler.repository.HospitalWeek;
import com.hospitalscheduler.repository.HospitalWeek.*;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Runs with Testcontainers by default; an explicit test JDBC URL can use a disposable native server. */
class HospitalRepositoryJdbcIT extends PostgresIntegrationTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);
    private static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    private HospitalRepositoryJdbc repository;

    @BeforeEach
    void prepareRepository() {
        repository = new HospitalRepositoryJdbc(dataSource);
    }

    @Test
    void roundTripsAllEditableDataAndKeepsStableIds() {
        HospitalWeek input = fixture("test.hospital", MONDAY, ZONE);
        repository.saveWeek(input);
        HospitalWeek loaded = repository.loadWeek("test.hospital", MONDAY).orElseThrow();
        assertTrue(loaded.hospital().id() > 0);
        assertEquals(2, loaded.doctors().get(0).specialtyCodes().size());
        assertEquals(input.doctors().get(0).availability(), loaded.doctors().get(0).availability());
        assertEquals(input.doctors().get(0).absences(), loaded.doctors().get(0).absences());
        assertEquals(input.doctors().get(0).breaks(), loaded.doctors().get(0).breaks());
        assertEquals(input.rooms().get(0).equipment(), loaded.rooms().get(0).equipment());
        assertEquals(input.patients().get(0).preferences(), loaded.patients().get(0).preferences());
        assertEquals(input.requests().get(0).availability(), loaded.requests().get(0).availability());
        assertEquals(input.requests().get(0).equipment(), loaded.requests().get(0).equipment());
        assertEquals(input.requests().get(0).requestedAt(), loaded.requests().get(0).requestedAt());
        assertEquals(Urgency.PRIORITY, loaded.requests().get(0).urgency());
        assertEquals("test.roomtype", loaded.requests().get(0).requiredRoomTypeCode());
        assertEquals("Dr O'Neil", loaded.doctors().get(0).displayName());
        repository.saveWeek(loaded);
        assertEquals(loaded, repository.loadWeek("test.hospital", MONDAY).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> loaded.requests().clear());
    }

    @Test
    void updatesEntitiesAndReplacesOnlySelectedWeekWindows() {
        repository.saveWeek(fixture("test.hospital", MONDAY, ZONE));
        repository.saveWeek(fixture("test.hospital", MONDAY.plusWeeks(1), ZONE));
        HospitalWeek loaded = repository.loadWeek("test.hospital", MONDAY).orElseThrow();
        Doctor doctor = loaded.doctors().get(0);
        Window editedWindow = localWindow(MONDAY, ZONE, 10, 12);
        Doctor edited = new Doctor(doctor.id(), doctor.code(), doctor.departmentCode(), "Edited", null,
                List.of("test.specialty"), List.of(editedWindow), List.of(), List.of());
        Room room = loaded.rooms().get(0);
        Room editedRoom = new Room(room.id(), room.code(), room.departmentCode(), room.roomTypeCode(),
                "Edited room", List.of(new EquipmentQuantity("test.equipment", 7)));
        Request request = loaded.requests().get(0);
        Request editedRequest = new Request(request.id(), request.code(), request.patientCode(), request.specialtyCode(),
                null, 45, Urgency.URGENT, request.requestedAt(), RequestStatus.CANCELLED,
                request.availability(), List.of(new EquipmentQuantity("test.equipment", 3)));
        repository.saveWeek(copy(loaded, List.of(edited), List.of(editedRoom), loaded.patients(), List.of(editedRequest)));
        HospitalWeek updated = repository.loadWeek("test.hospital", MONDAY).orElseThrow();
        assertEquals("Edited", updated.doctors().get(0).displayName());
        assertEquals(List.of(editedWindow), updated.doctors().get(0).availability());
        assertTrue(updated.doctors().get(0).absences().isEmpty());
        assertTrue(updated.doctors().get(0).breaks().isEmpty());
        assertEquals(7, updated.rooms().get(0).equipment().get(0).quantity());
        assertEquals(RequestStatus.CANCELLED, updated.requests().get(0).status());
        assertEquals(45, updated.requests().get(0).durationMinutes());
        assertNull(updated.requests().get(0).requiredRoomTypeCode());
        assertEquals(3, updated.requests().get(0).equipment().get(0).quantity());
        assertEquals(1, repository.loadWeek("test.hospital", MONDAY.plusWeeks(1)).orElseThrow()
                .doctors().get(0).availability().size());
    }

    @Test
    void rollsBackAllChangesAfterALateDatabaseFailure() {
        repository.saveWeek(fixture("test.hospital", MONDAY, ZONE));
        HospitalWeek before = repository.loadWeek("test.hospital", MONDAY).orElseThrow();
        Doctor d = before.doctors().get(0);
        Doctor edited = new Doctor(d.id(), d.code(), d.departmentCode(), "Must roll back", null,
                List.of(), List.of(), List.of(), List.of());
        Request r = before.requests().get(0);
        Request duplicateEquipment = new Request(r.id(), r.code(), r.patientCode(), r.specialtyCode(),
                r.requiredRoomTypeCode(), r.durationMinutes(), r.urgency(), r.requestedAt(), r.status(),
                r.availability(), List.of(new EquipmentQuantity("test.equipment", 1), new EquipmentQuantity("test.equipment", 2)));
        assertThrows(HospitalRepositoryException.class, () -> repository.saveWeek(
                copy(before, List.of(edited), before.rooms(), before.patients(), List.of(duplicateEquipment))));
        assertEquals(before, repository.loadWeek("test.hospital", MONDAY).orElseThrow());
    }

    @Test
    void changesPatientWindowsWithoutDeletingReferencedOrOtherWeekData() {
        repository.saveWeek(fixture("test.hospital", MONDAY, ZONE));
        repository.saveWeek(fixture("test.hospital", MONDAY.plusWeeks(1), ZONE));
        HospitalWeek before = repository.loadWeek("test.hospital", MONDAY).orElseThrow();
        Patient p = before.patients().get(0);
        Window replacement = localWindow(MONDAY, ZONE, 12, 15);
        Patient edited = new Patient(p.id(), p.code(), "Edited patient", p.email(), List.of(replacement), p.preferences());
        Request r = before.requests().get(0);
        Request editedRequest = new Request(r.id(), r.code(), r.patientCode(), r.specialtyCode(), r.requiredRoomTypeCode(),
                r.durationMinutes(), r.urgency(), r.requestedAt(), r.status(), List.of(replacement), r.equipment());
        repository.saveWeek(copy(before, before.doctors(), before.rooms(), List.of(edited), List.of(editedRequest)));
        HospitalWeek after = repository.loadWeek("test.hospital", MONDAY).orElseThrow();
        assertEquals(List.of(replacement), after.requests().get(0).availability());
        assertEquals(List.of(replacement), after.patients().get(0).availability());
        assertEquals(1, repository.loadWeek("test.hospital", MONDAY.plusWeeks(1)).orElseThrow().patients().get(0).availability().size());
        // Removing a still-linked patient window must fail and leave all previous edits intact.
        Patient noWindows = new Patient(p.id(), p.code(), "Must roll back", p.email(), List.of(), p.preferences());
        assertThrows(HospitalRepositoryException.class, () -> repository.saveWeek(
                copy(after, after.doctors(), after.rooms(), List.of(noWindows), List.of())));
        assertEquals(after, repository.loadWeek("test.hospital", MONDAY).orElseThrow());
    }

    @Test
    void isolatesHospitalsAndUsesHalfOpenWeekBoundaries() throws Exception {
        repository.saveWeek(fixture("test.hospital", MONDAY, ZONE));
        try (Connection c = dataSource.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO hospital.hospital(code,name,time_zone) VALUES ('other','Other','UTC')");
            s.executeUpdate("INSERT INTO hospital.department(code,hospital_id,name) SELECT 'other.dept',id,'Other' FROM hospital.hospital WHERE code='other'");
        }
        HospitalWeek other = repository.loadWeek("other", MONDAY).orElseThrow();
        assertTrue(other.doctors().isEmpty());
        assertTrue(other.rooms().isEmpty());
        assertTrue(other.patients().isEmpty());
        assertTrue(other.requests().isEmpty());
        Doctor stolen = new Doctor(0, "test.doctor", "other.dept", "Must roll back", null,
                List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> repository.saveWeek(
                copy(other, List.of(stolen), other.rooms(), other.patients(), other.requests())));
        assertEquals("Dr O'Neil", repository.loadWeek("test.hospital", MONDAY).orElseThrow().doctors().get(0).displayName());
        assertTrue(repository.loadWeek("test.hospital", MONDAY.minusWeeks(1)).orElseThrow().requests().isEmpty());
        assertTrue(repository.loadWeek("missing", MONDAY).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> repository.loadWeek("test.hospital", MONDAY.plusDays(1)));
    }

    @Test
    void preservesCrossingWindowsAndExcludesAdjacentWeekEndpoints() throws Exception {
        repository.saveWeek(fixture("test.hospital", MONDAY, ZONE));
        HospitalWeek week = repository.loadWeek("test.hospital", MONDAY).orElseThrow();
        Window crossing = new Window(week.start().minusSeconds(3600), week.start().plusSeconds(3600));
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                INSERT INTO hospital.doctor_availability(doctor_id,starts_at,ends_at) VALUES (?,?,?)
                """)) {
            for (Window w : List.of(crossing,
                    new Window(week.start().minusSeconds(7200), week.start()),
                    new Window(week.end(), week.end().plusSeconds(3600)))) {
                s.setLong(1, week.doctors().get(0).id());
                s.setObject(2, w.startsAt().atOffset(ZoneOffset.UTC));
                s.setObject(3, w.endsAt().atOffset(ZoneOffset.UTC));
                s.executeUpdate();
            }
        }
        HospitalWeek loaded = repository.loadWeek("test.hospital", MONDAY).orElseThrow();
        assertEquals(2, loaded.doctors().get(0).availability().size());
        assertTrue(loaded.doctors().get(0).availability().contains(crossing));
        repository.saveWeek(loaded);
        assertEquals(loaded, repository.loadWeek("test.hospital", MONDAY).orElseThrow());
        assertEquals(1, repository.loadWeek("test.hospital", MONDAY.plusWeeks(1)).orElseThrow()
                .doctors().get(0).availability().size());
        assertThrows(IllegalArgumentException.class,
                () -> new Window(week.start().plusNanos(1), week.end()));
    }

    @Test
    void handlesWeekCrossingDaylightSavingWithoutRoundingInstants() {
        LocalDate dstWeek = LocalDate.of(2026, 10, 19);
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        HospitalWeek input = fixture("test.hospital", dstWeek, berlin);
        assertEquals(169, Duration.between(input.start(), input.end()).toHours());
        repository.saveWeek(input);
        assertEquals(input.doctors().get(0).availability(),
                repository.loadWeek("test.hospital", dstWeek).orElseThrow().doctors().get(0).availability());
    }

    private static HospitalWeek fixture(String code, LocalDate monday, ZoneId zone) {
        Window work = localWindow(monday, zone, 8, 17);
        Window absence = localWindow(monday, zone, 10, 11);
        Window pause = localWindow(monday, zone, 12, 13);
        return new HospitalWeek(new Hospital(0, code, "Test hospital", zone), monday,
                List.of(new Department(0, "test.department", "Department")),
                List.of(new Specialty(0, "test.specialty", "test.department", "Specialty"),
                        new Specialty(0, "test.specialty2", "test.department", "Other specialty")),
                List.of(new Doctor(0, "test.doctor", "test.department", "Dr O'Neil", null,
                        List.of("test.specialty", "test.specialty2"), List.of(work),
                        List.of(new Absence(absence, "Appointment")), List.of(new DoctorBreak(pause, "Lunch")))),
                List.of(new RoomType(0, "test.roomtype", "Room type")),
                List.of(new Equipment(0, "test.equipment", "Equipment")),
                List.of(new Room(0, "test.room", "test.department", "test.roomtype", "Room",
                        List.of(new EquipmentQuantity("test.equipment", 4)))),
                List.of(new Patient(0, "test.patient", "Patient", null, List.of(work),
                        List.of(new Preference(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(11, 0), 2)))),
                List.of(new Request(0, "test.request." + monday, "test.patient", "test.specialty", "test.roomtype",
                        30, Urgency.PRIORITY, monday.minusDays(5).atStartOfDay(zone).toInstant(), RequestStatus.OPEN,
                        List.of(work), List.of(new EquipmentQuantity("test.equipment", 2)))));
    }

    private static Window localWindow(LocalDate date, ZoneId zone, int startHour, int endHour) {
        return new Window(date.atTime(startHour, 0).atZone(zone).toInstant(), date.atTime(endHour, 0).atZone(zone).toInstant());
    }

    private static HospitalWeek copy(HospitalWeek source, List<Doctor> doctors, List<Room> rooms,
                                     List<Patient> patients, List<Request> requests) {
        return new HospitalWeek(source.hospital(), source.startsOn(), source.departments(), source.specialties(), doctors,
                source.roomTypes(), source.equipment(), rooms, patients, requests);
    }
}
