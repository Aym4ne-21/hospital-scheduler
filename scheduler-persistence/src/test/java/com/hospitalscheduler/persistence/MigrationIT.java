package com.hospitalscheduler.persistence;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies migrations, upgrades and structural constraints on real PostgreSQL. */
@Testcontainers
class MigrationIT {
    private static final Path ROOT = Path.of(System.getProperty("scheduler.persistence.root", ".")).toAbsolutePath();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("scheduler.postgres.image", "postgres:18.7-bookworm"))
            .withDatabaseName("migration_admin")
            .withUsername("migration_test")
            .withPassword("testcontainers_only")
            .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("scripts")), "/workspace/scripts")
            .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("src/main/resources/db/migration")),
                    "/workspace/src/main/resources/db/migration");

    private String database;

    @BeforeAll
    static void prepareScripts() throws Exception {
        succeeds(POSTGRES.execInContainer("bash", "-c", "chmod +x /workspace/scripts/*.sh"));
    }

    @BeforeEach
    void createIsolatedDatabase() throws SQLException {
        database = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + database);
        }
    }

    @Test
    void migratesEmptyDatabaseAndReplaysWithoutChangingTheJournal() throws Exception {
        migrate();
        try (var connection = connect()) {
            assertEquals(24, scalar(connection, "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema='hospital' AND table_type='BASE TABLE'"));
            assertEquals(3, scalar(connection, "SELECT count(*) FROM public.database_schema_migration"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM pg_extension WHERE extname='btree_gist'"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM hospital.patient"));
            String before = journal(connection);
            migrate();
            assertEquals(before, journal(connection), "Replay must preserve the migration record");
        }
    }

    @Test
    void upgradesDayOneDatabaseWithoutLosingHospitalData() throws Exception {
        succeeds(POSTGRES.execInContainer("bash", "-c", """
                mkdir -p /day1/src/main/resources/db/migration
                cp -R /workspace/scripts /day1/scripts
                cp /workspace/src/main/resources/db/migration/V001__hospital_and_availability.sql \
                    /day1/src/main/resources/db/migration/
                """));
        migrate("/day1");
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO hospital.hospital (code,name,time_zone) "
                    + "VALUES ('upgrade.hospital','Preserved hospital','Africa/Casablanca')");
            String before = journal(connection);
            migrate();
            assertEquals(1, scalar(connection, "SELECT count(*) FROM hospital.hospital "
                    + "WHERE code='upgrade.hospital' AND name='Preserved hospital'"));
            assertEquals(3, scalar(connection, "SELECT count(*) FROM public.database_schema_migration"));
            assertTrue(journal(connection).startsWith(before + ","), "V001 journal must be preserved");
        }
    }

    @Test
    void savesOverlappingCandidatesAndConfirmsOnlyTheExactSavedAssignment() throws Exception {
        migrate();
        try (var connection = connect(); var statement = connection.createStatement()) {
            seedSchedule(connection);
            statement.executeUpdate("""
                    INSERT INTO hospital.candidate_assignment
                        (schedule_id,request_id,doctor_id,room_id,starts_at,ends_at,locked)
                    VALUES (1,1,1,1,'2026-11-23 09:00+01','2026-11-23 09:30+01',true),
                           (1,2,1,1,'2026-11-23 09:00+01','2026-11-23 09:30+01',false),
                           (2,1,1,1,'2026-11-23 09:00+01','2026-11-23 09:30+01',false)
                    """);
            assertEquals(3, scalar(connection, "SELECT count(*) FROM hospital.candidate_assignment"));
            statement.executeUpdate("""
                    INSERT INTO hospital.confirmed_appointment
                        (candidate_assignment_id,request_id,doctor_id,room_id,starts_at,ends_at)
                    SELECT id,request_id,doctor_id,room_id,starts_at,ends_at
                    FROM hospital.candidate_assignment WHERE id=1
                    """);
            assertEquals(1, scalar(connection, "SELECT count(*) FROM hospital.confirmed_appointment"));
            assertEquals(3, scalar(connection, "SELECT count(*) FROM hospital.candidate_assignment"));
            PSQLException mismatch = assertThrows(PSQLException.class, () -> statement.executeUpdate("""
                    UPDATE hospital.confirmed_appointment SET ends_at='2026-11-23 09:45+01'
                    WHERE candidate_assignment_id=1
                    """));
            assertEquals("23503", mismatch.getSQLState());
            PSQLException duplicate = assertThrows(PSQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO hospital.candidate_assignment
                        (schedule_id,request_id,doctor_id,room_id,starts_at,ends_at)
                    VALUES (1,1,1,1,'2026-11-23 10:00+01','2026-11-23 10:30+01')
                    """));
            assertEquals("23505", duplicate.getSQLState());
            assertEquals(1, scalar(connection, "SELECT count(*) FROM hospital.candidate_assignment "
                    + "WHERE locked AND lower_inc(occupied_at) AND NOT upper_inc(occupied_at) "
                    + "AND starts_at='2026-11-23 08:00Z'::timestamptz"));
        }
    }

    @Test
    void rejectsMalformedScheduleMetadataAndAssignmentBounds() throws Exception {
        migrate();
        try (var connection = connect(); var statement = connection.createStatement()) {
            seedSchedule(connection);
            for (String edit : new String[]{"parameters='[]'::jsonb", "final_fitness='NaN'::numeric",
                    "final_fitness='Infinity'::numeric", "final_fitness=-1", "status='UNKNOWN'",
                    "status='CANDIDATE', final_fitness=NULL"}) {
                PSQLException error = assertThrows(PSQLException.class,
                        () -> statement.executeUpdate("UPDATE hospital.schedule SET " + edit + " WHERE id=1"));
                assertEquals("23514", error.getSQLState(), edit);
            }
            PSQLException bounds = assertThrows(PSQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO hospital.candidate_assignment
                        (schedule_id,request_id,doctor_id,room_id,starts_at,ends_at)
                    VALUES (1,1,1,1,'2026-11-23 09:00Z','2026-11-23 09:00Z')
                    """));
            assertEquals("23514", bounds.getSQLState());
        }
    }

    private static void seedSchedule(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO hospital.hospital(code,name,time_zone) VALUES ('it.h','Test','Africa/Casablanca');
                    INSERT INTO hospital.department(code,hospital_id,name) VALUES ('it.d',1,'Test');
                    INSERT INTO hospital.specialty(code,department_id,name) VALUES ('it.s',1,'Test');
                    INSERT INTO hospital.doctor(code,department_id,display_name) VALUES ('it.dr',1,'Test');
                    INSERT INTO hospital.room_type(code,name) VALUES ('it.rt','Test');
                    INSERT INTO hospital.room(code,department_id,room_type_id,name) VALUES ('it.r',1,1,'Test');
                    INSERT INTO hospital.patient(code,display_name) VALUES ('it.p','Test');
                    INSERT INTO hospital.appointment_request
                        (code,patient_id,specialty_id,duration_minutes,urgency,requested_at)
                    VALUES ('it.req1',1,1,30,'ROUTINE',current_timestamp),
                           ('it.req2',1,1,30,'ROUTINE',current_timestamp);
                    INSERT INTO hospital.algorithm(code,name,version) VALUES ('it.fake','Test','1');
                    INSERT INTO hospital.schedule_week(code,hospital_id,starts_on) VALUES ('it.week',1,'2026-11-23');
                    INSERT INTO hospital.schedule(code,schedule_week_id,algorithm_id,random_seed,final_fitness,status)
                    VALUES ('it.schedule1',1,1,42,0,'CANDIDATE'), ('it.schedule2',1,1,42,1,'CANDIDATE');
                    """);
        }
    }

    @Test
    void rejectsNegativeAppointmentDuration() throws Exception {
        migrate();
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO hospital.hospital (code,name,time_zone) "
                        + "VALUES ('it.hospital','Integration test','Africa/Casablanca')");
                statement.executeUpdate("INSERT INTO hospital.department (code,hospital_id,name) "
                        + "VALUES ('it.department',1,'Integration test')");
                statement.executeUpdate("INSERT INTO hospital.specialty (code,department_id,name) "
                        + "VALUES ('it.specialty',1,'Integration test')");
                statement.executeUpdate("INSERT INTO hospital.patient (code,display_name) "
                        + "VALUES ('it.patient','Integration test')");
            }
            Savepoint savepoint = connection.setSavepoint();
            try {
                PSQLException error = assertThrows(PSQLException.class, () -> {
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate("INSERT INTO hospital.appointment_request "
                                + "(code,patient_id,specialty_id,duration_minutes,urgency,requested_at) "
                                + "VALUES ('it.invalid-duration',1,1,-15,'ROUTINE',current_timestamp)");
                    }
                });
                assertEquals("23514", error.getSQLState());
                assertEquals("appointment_request_duration_minutes_check",
                        error.getServerErrorMessage().getConstraint());
            } finally {
                connection.rollback(savepoint);
                connection.rollback();
            }
        }
    }

    @Test
    void rejectsInvalidPatientForeignKey() throws Exception {
        migrate();
        try (var connection = connect()) {
            PSQLException error = assertThrows(PSQLException.class, () -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO hospital.appointment_request "
                            + "(code,patient_id,specialty_id,duration_minutes,urgency,requested_at) "
                            + "VALUES ('it.invalid-patient',-999,1,30,'ROUTINE',current_timestamp)");
                }
            });
            assertEquals("23503", error.getSQLState());
            assertEquals("appointment_request_patient_id_fkey", error.getServerErrorMessage().getConstraint());
        }
    }

    private Connection connect() throws SQLException {
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void migrate() throws Exception {
        migrate("/workspace");
    }

    private void migrate(String root) throws Exception {
        ExecResult result = POSTGRES.execInContainer("env", "PGHOST=127.0.0.1", "PGPORT=5432",
                "PGDATABASE=" + database, "PGUSER=" + POSTGRES.getUsername(),
                "PGPASSWORD=" + POSTGRES.getPassword(), "bash", root + "/scripts/migrate-database.sh");
        succeeds(result);
    }

    private static void succeeds(ExecResult result) {
        assertEquals(0, result.getExitCode(), () -> result.getStdout() + "\n" + result.getStderr());
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String journal(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT string_agg(version || ':' || filename || ':' || sha256 || ':' || applied_at, ',' ORDER BY version) "
                        + "FROM public.database_schema_migration")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
