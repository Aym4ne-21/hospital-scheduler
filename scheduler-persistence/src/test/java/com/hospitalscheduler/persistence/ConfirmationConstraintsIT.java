package com.hospitalscheduler.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmationConstraintsIT extends PostgresIntegrationTest {
    private static final String MIGRATION = "V003__confirmed_appointment_exclusions.sql";

    @Override
    protected List<String> migrations() {
        return List.of("V001__hospital_and_availability.sql", "V002__schedules.sql");
    }

    @BeforeEach
    void seedSchedules() throws Exception {
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("""
                    INSERT INTO hospital.hospital(code,name,time_zone) VALUES ('h','Test','UTC');
                    INSERT INTO hospital.department(code,hospital_id,name) VALUES ('d',1,'Test');
                    INSERT INTO hospital.specialty(code,department_id,name) VALUES ('s',1,'Test');
                    INSERT INTO hospital.doctor(code,department_id,display_name) VALUES ('dr1',1,'One'),('dr2',1,'Two');
                    INSERT INTO hospital.room_type(code,name) VALUES ('type','Test');
                    INSERT INTO hospital.room(code,department_id,room_type_id,name) VALUES ('room1',1,1,'One'),('room2',1,1,'Two');
                    INSERT INTO hospital.patient(code,display_name) VALUES ('p','Test');
                    INSERT INTO hospital.appointment_request(code,patient_id,specialty_id,duration_minutes,urgency,requested_at)
                    SELECT 'req' || n,1,1,30,'ROUTINE',current_timestamp FROM generate_series(1,6) n;
                    INSERT INTO hospital.algorithm(code,name,version) VALUES ('test','Test','1');
                    INSERT INTO hospital.schedule_week(code,hospital_id,starts_on) VALUES ('week',1,'2026-11-23');
                    INSERT INTO hospital.schedule(code,schedule_week_id,algorithm_id,random_seed,final_fitness,status)
                    VALUES ('schedule1',1,1,42,0,'CANDIDATE'),('schedule2',1,1,42,0,'CANDIDATE'),
                           ('schedule3',1,1,42,0,'CANDIDATE');
                    """);
        }
    }

    @Test
    void rejectsDoctorOverlapAcrossSchedulesButKeepsConflictingCandidates() throws Exception {
        long first = candidate(1, 1, 1, 1, "09:00", "09:30");
        long conflict = candidate(2, 2, 1, 2, "09:15", "09:45");
        upgrade();
        try (var c = dataSource.getConnection()) {
            confirm(c, first);
            exclusion(assertThrows(PSQLException.class, () -> confirm(c, conflict)), "confirmed_doctor_no_overlap");
            assertEquals(2, count(c, "hospital.candidate_assignment"));
            assertEquals(1, count(c, "hospital.confirmed_appointment"));
        }
    }

    @Test
    void rejectsRoomOverlapWithoutDoctorCollision() throws Exception {
        long first = candidate(1, 1, 1, 1, "09:00", "09:30");
        long conflict = candidate(2, 2, 2, 1, "09:15", "09:45");
        upgrade();
        try (var c = dataSource.getConnection()) {
            confirm(c, first);
            exclusion(assertThrows(PSQLException.class, () -> confirm(c, conflict)), "confirmed_room_no_overlap");
        }
    }

    @Test
    void preservesExistingReservationsAndAllowsAdjacencyAndIndependentResources() throws Exception {
        long first = candidate(1, 1, 1, 1, "09:00", "09:30");
        long adjacent = candidate(2, 2, 1, 1, "09:30", "10:00");
        long independent = candidate(2, 3, 2, 2, "09:15", "09:45");
        try (var c = dataSource.getConnection()) {
            confirm(c, first); // Existing V002 data is validated and retained on upgrade.
            upgrade();
            confirm(c, adjacent);
            confirm(c, independent);
            assertEquals(3, count(c, "hospital.confirmed_appointment"));
            assertEquals(3, count(c, "hospital.candidate_assignment"));
        }
    }

    @Test
    void rejectsAnUpdateThatWouldCreateAnOverlap() throws Exception {
        long first = candidate(1, 1, 1, 1, "09:00", "09:30");
        long later = candidate(2, 2, 1, 2, "10:00", "10:30");
        long conflict = candidate(3, 2, 1, 2, "09:15", "09:45");
        upgrade();
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                UPDATE hospital.confirmed_appointment a SET
                    candidate_assignment_id=b.id,request_id=b.request_id,doctor_id=b.doctor_id,
                    room_id=b.room_id,starts_at=b.starts_at,ends_at=b.ends_at
                FROM hospital.candidate_assignment b WHERE a.candidate_assignment_id=? AND b.id=?
                """)) {
            confirm(c, first);
            confirm(c, later);
            s.setLong(1, later);
            s.setLong(2, conflict);
            exclusion(assertThrows(PSQLException.class, s::executeUpdate), "confirmed_doctor_no_overlap");
            assertEquals(2, count(c, "hospital.confirmed_appointment"));
        }
    }

    @Test
    void rejectsInvalidLegacyDataAndRollsBackBothConstraints() throws Exception {
        // Room-only conflict makes the second exclusion fail after the first has been built.
        long first = candidate(1, 1, 1, 1, "09:00", "09:30");
        long conflict = candidate(2, 2, 2, 1, "09:15", "09:45");
        try (var c = dataSource.getConnection()) {
            confirm(c, first);
            confirm(c, conflict);
            PSQLException error = assertThrows(PSQLException.class, this::upgrade);
            assertEquals("23P01", error.getSQLState());
            assertEquals(2, count(c, "hospital.confirmed_appointment"));
            try (var s = c.createStatement(); var r = s.executeQuery("""
                    SELECT count(*) FROM pg_constraint
                    WHERE conrelid='hospital.confirmed_appointment'::regclass AND contype='x'
                    """)) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
        }
    }

    @Test
    void concurrentConfirmationFailsAfterTheConflictingReservationCommits() throws Exception {
        concurrentConfirmation(true);
    }

    @Test
    void concurrentConfirmationSucceedsAfterTheConflictingReservationRollsBack() throws Exception {
        concurrentConfirmation(false);
    }

    private void concurrentConfirmation(boolean commit) throws Exception {
        long first = candidate(1, 1, 1, 1, "09:00", "09:30");
        long conflict = candidate(2, 2, 1, 2, "09:15", "09:45");
        upgrade();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (var blocker = dataSource.getConnection(); var observer = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            confirm(blocker, first);
            BlockingQueue<Integer> backend = new ArrayBlockingQueue<>(1);
            Future<PSQLException> result = executor.submit(() -> {
                try (var other = dataSource.getConnection(); var s = other.createStatement()) {
                    s.execute("SET statement_timeout='5s'");
                    try (var r = s.executeQuery("SELECT pg_backend_pid()")) {
                        r.next();
                        backend.add(r.getInt(1));
                    }
                    try {
                        confirm(other, conflict);
                        return null;
                    } catch (PSQLException error) {
                        return error;
                    }
                }
            });
            try {
                Integer pid = backend.poll(5, TimeUnit.SECONDS);
                assertNotNull(pid);
                boolean waiting = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                try (var s = observer.prepareStatement("SELECT wait_event_type FROM pg_stat_activity WHERE pid=?")) {
                    s.setInt(1, pid);
                    while (System.nanoTime() < deadline && !result.isDone()) {
                        try (var r = s.executeQuery()) {
                            waiting = r.next() && "Lock".equals(r.getString(1));
                        }
                        if (waiting) break;
                        Thread.sleep(10);
                    }
                }
                assertTrue(waiting, "The second confirmation must wait for the first transaction");
                if (commit) blocker.commit(); else blocker.rollback();
                PSQLException error = result.get(5, TimeUnit.SECONDS);
                if (commit) exclusion(error, "confirmed_doctor_no_overlap"); else assertNull(error);
                assertEquals(1, count(observer, "hospital.confirmed_appointment"));
            } finally {
                blocker.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void upgrade() throws Exception {
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                executeMigration(c, MIGRATION);
                c.commit();
            } catch (Exception error) {
                c.rollback();
                throw error;
            }
        }
    }

    private long candidate(long schedule, long request, long doctor, long room, String start, String end) throws SQLException {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                INSERT INTO hospital.candidate_assignment(schedule_id,request_id,doctor_id,room_id,starts_at,ends_at)
                VALUES (?,?,?,?,?::timestamptz,?::timestamptz) RETURNING id
                """)) {
            s.setLong(1, schedule);
            s.setLong(2, request);
            s.setLong(3, doctor);
            s.setLong(4, room);
            s.setString(5, "2026-11-23 " + start + "+00");
            s.setString(6, "2026-11-23 " + end + "+00");
            try (var r = s.executeQuery()) { assertTrue(r.next()); return r.getLong(1); }
        }
    }

    private static void confirm(Connection c, long candidateId) throws SQLException {
        try (var s = c.prepareStatement("""
                INSERT INTO hospital.confirmed_appointment(candidate_assignment_id,request_id,doctor_id,room_id,starts_at,ends_at)
                SELECT id,request_id,doctor_id,room_id,starts_at,ends_at FROM hospital.candidate_assignment WHERE id=?
                """)) {
            s.setLong(1, candidateId);
            assertEquals(1, s.executeUpdate());
        }
    }

    private static void exclusion(PSQLException error, String constraint) {
        assertNotNull(error);
        assertEquals("23P01", error.getSQLState());
        assertNotNull(error.getServerErrorMessage());
        assertEquals(constraint, error.getServerErrorMessage().getConstraint());
    }

    private static long count(Connection c, String table) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }
}
