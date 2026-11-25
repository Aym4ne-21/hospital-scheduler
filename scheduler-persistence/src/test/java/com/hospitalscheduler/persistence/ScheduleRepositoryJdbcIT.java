package com.hospitalscheduler.persistence;

import com.hospitalscheduler.repository.ScheduleRepositoryException;
import com.hospitalscheduler.repository.ScheduleSnapshot;
import com.hospitalscheduler.repository.ScheduleSnapshot.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hospitalscheduler.optimization.FitnessBreakdown;
import java.sql.SQLException;
import java.time.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleRepositoryJdbcIT extends PostgresIntegrationTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);
    private static final Algorithm ALGORITHM = new Algorithm("test.v1", "Test algorithm", "1");
    private static final Instant START = OffsetDateTime.parse("2026-11-23T09:00:00.123456+01:00").toInstant();
    private ScheduleRepositoryJdbc repository;

    @BeforeEach
    void seedHospitalData() throws Exception {
        repository = new ScheduleRepositoryJdbc(dataSource);
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("""
                    INSERT INTO hospital.hospital(code,name,time_zone)
                    VALUES ('hospital1','One','Africa/Casablanca'),('hospital2','Two','UTC');
                    INSERT INTO hospital.department(code,hospital_id,name) VALUES ('dept1',1,'One'),('dept2',2,'Two');
                    INSERT INTO hospital.specialty(code,department_id,name) VALUES ('specialty1',1,'One'),('specialty2',2,'Two');
                    INSERT INTO hospital.doctor(code,department_id,display_name) VALUES ('doctor1',1,'One'),('doctor2',2,'Two');
                    INSERT INTO hospital.room_type(code,name) VALUES ('type','Test');
                    INSERT INTO hospital.room(code,department_id,room_type_id,name) VALUES ('room1',1,1,'One'),('room2',2,1,'Two');
                    INSERT INTO hospital.patient(code,display_name) VALUES ('patient','Test');
                    INSERT INTO hospital.appointment_request(code,patient_id,specialty_id,duration_minutes,urgency,requested_at)
                    SELECT 'request' || n,1,1,30,'ROUTINE',current_timestamp FROM generate_series(1,150) n;
                    INSERT INTO hospital.appointment_request(code,patient_id,specialty_id,duration_minutes,urgency,requested_at)
                    VALUES ('other.request',1,2,30,'URGENT',current_timestamp);
                    """);
        }
    }

    @Test
    void roundTrips150ConflictingAssignmentsAndAllMetadata() throws Exception {
        List<Assignment> assignments = new ArrayList<>();
        for (long i = 1; i <= 150; i++) assignments.add(assignment(i, 1, 1, i % 2 == 0));
        Collections.reverse(assignments);
        ScheduleSnapshot input = candidate("roundtrip", "hospital1", MONDAY, ALGORITHM,
                "{\"label\":\"O'Neil — تجربة\",\"iterations\":150,\"weights\":{\"hard\":2}}", assignments);
        assignments.clear();
        Stored saved = repository.saveCandidate(input);
        assertTrue(saved.id() > 0);
        assertEquals(Status.CANDIDATE, saved.status());
        assertNotNull(saved.createdAt());
        assertEquals(ALGORITHM, saved.snapshot().algorithm());
        assertEquals(Long.MIN_VALUE, saved.snapshot().randomSeed());
        assertEquals(input.finalFitness(), saved.snapshot().finalFitness());
        assertTrue(saved.snapshot().parametersJson().contains("O'Neil — تجربة"));
        assertEquals(150, saved.snapshot().assignments().size());
        assertEquals(assignment(1, 1, 1, false), saved.snapshot().assignments().get(0));
        assertEquals(assignment(150, 1, 1, true), saved.snapshot().assignments().get(149));
        assertEquals(saved, repository.load("roundtrip").orElseThrow());
        assertEquals(List.of(saved), repository.loadWeek("hospital1", MONDAY));
        assertEquals(0, count("hospital.confirmed_appointment"));
        assertThrows(UnsupportedOperationException.class, () -> saved.snapshot().assignments().clear());
    }

    @Test
    void retainsSeparateVersionsAndFiltersWeekAndHospital() throws Exception {
        Stored first = repository.saveCandidate(candidate("first", List.of(assignment(1, 1, 1, false))));
        Stored second = repository.saveCandidate(candidate("second", List.of(assignment(1, 1, 1, true))));
        repository.saveCandidate(candidate("next.week", "hospital1", MONDAY.plusWeeks(1), ALGORITHM, "{}", List.of()));
        repository.saveCandidate(candidate("other.hospital", "hospital2", MONDAY, ALGORITHM, "{}", List.of()));
        assertNotEquals(first.id(), second.id());
        assertEquals(List.of(first, second), repository.loadWeek("hospital1", MONDAY));
        assertEquals(1, repository.loadWeek("hospital2", MONDAY).size());
        assertTrue(repository.loadWeek("missing", MONDAY).isEmpty());
        assertTrue(repository.load("missing").isEmpty());
        assertEquals(3, count("hospital.schedule_week"));
        assertEquals(1, count("hospital.algorithm"));
        assertThrows(IllegalArgumentException.class, () -> repository.loadWeek("hospital1", MONDAY.plusDays(1)));
    }

    @Test
    void rollsBackMetadataWhenJsonIsMalformedOrIsNotAnObject() throws Exception {
        for (String json : List.of("{\"broken\":}", "[]", "null")) {
            assertThrows(ScheduleRepositoryException.class, () -> repository.saveCandidate(
                    candidate("invalid", "hospital1", MONDAY, ALGORITHM, json, List.of())));
            assertEquals(0, count("hospital.algorithm"));
            assertEquals(0, count("hospital.schedule_week"));
            assertEquals(0, count("hospital.schedule"));
        }
    }

    @Test
    void lateInvalidBatchRowRollsBackEarlierRowsAndAllMetadata() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> repository.saveCandidate(candidate("invalid.batch",
                List.of(assignment(1, 1, 1, true), assignment(2, 999999, 1, false)))));
        assertEquals(0, count("hospital.candidate_assignment"));
        assertEquals(0, count("hospital.schedule"));
        assertEquals(0, count("hospital.algorithm"));
        assertEquals(0, count("hospital.schedule_week"));
    }

    @Test
    void duplicateCodeCannotOverwriteAnExistingVersion() throws Exception {
        Stored original = repository.saveCandidate(candidate("immutable", List.of(assignment(1, 1, 1, true))));
        ScheduleSnapshot duplicate = candidate("immutable", "hospital1", MONDAY.plusWeeks(1),
                new Algorithm("new.v1", "New algorithm", "1"), "{}", List.of());
        ScheduleRepositoryException error = assertThrows(ScheduleRepositoryException.class,
                () -> repository.saveCandidate(duplicate));
        assertEquals("23505", ((SQLException) error.getCause()).getSQLState());
        assertEquals(original, repository.load("immutable").orElseThrow());
        assertEquals(1, count("hospital.schedule_week"));
        assertEquals(1, count("hospital.algorithm"));
    }

    @Test
    void existingAlgorithmMetadataCannotChangePastSchedules() throws Exception {
        Stored original = repository.saveCandidate(candidate("original", List.of()));
        assertThrows(IllegalArgumentException.class, () -> repository.saveCandidate(candidate("new.version",
                "hospital1", MONDAY.plusWeeks(1), new Algorithm(ALGORITHM.code(), ALGORITHM.name(), "2"), "{}", List.of())));
        assertEquals(original, repository.load("original").orElseThrow());
        assertEquals(1, count("hospital.schedule"));
        assertEquals(1, count("hospital.schedule_week"));
    }

    @Test
    void rejectsForeignHospitalRequestsDoctorsAndRooms() throws Exception {
        for (Assignment foreign : List.of(assignment(151, 1, 1, false), assignment(1, 2, 1, false), assignment(1, 1, 2, false))) {
            assertThrows(IllegalArgumentException.class,
                    () -> repository.saveCandidate(candidate("foreign", List.of(foreign))));
            assertEquals(0, count("hospital.schedule"));
            assertEquals(0, count("hospital.candidate_assignment"));
        }
        assertThrows(IllegalArgumentException.class, () -> repository.saveCandidate(
                candidate("unknown", "missing", MONDAY, ALGORITHM, "{}", List.of())));
    }

    @Test
    void readsDraftsWithoutFitnessAndRejectsIncompleteCandidateInput() throws Exception {
        Stored original = repository.saveCandidate(candidate("draft", List.of()));
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE hospital.schedule SET status='DRAFT', final_fitness=NULL, hard_violations=NULL, soft_cost=NULL WHERE code='draft'");
        }
        Stored draft = repository.load("draft").orElseThrow();
        assertEquals(original.id(), draft.id());
        assertEquals(Status.DRAFT, draft.status());
        assertNull(draft.snapshot().finalFitness());
        assertThrows(IllegalArgumentException.class, () -> repository.saveCandidate(draft.snapshot()));
        assertEquals(draft, repository.load("draft").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> candidate("duplicate.requests",
                List.of(assignment(1, 1, 1, false), assignment(1, 1, 1, true))));
    }

    private static ScheduleSnapshot candidate(String code, List<Assignment> assignments) {
        return candidate(code, "hospital1", MONDAY, ALGORITHM, "{}", assignments);
    }

    private static ScheduleSnapshot candidate(String code, String hospital, LocalDate monday,
                                              Algorithm algorithm, String json, List<Assignment> assignments) {
        return new ScheduleSnapshot(code, hospital, monday, algorithm, json, Long.MIN_VALUE,
                new FitnessBreakdown(7, 123456789.123456789), assignments);
    }

    private static Assignment assignment(long request, long doctor, long room, boolean locked) {
        return new Assignment(request, doctor, room, START, START.plusSeconds(1800), locked);
    }

    private long count(String table) throws Exception {
        try (var c = dataSource.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }
}
