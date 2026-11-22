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
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the shipped Bash/psql runner, rather than a second migration implementation. */
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
            .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("db")), "/workspace/db")
            .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("src/main/resources/db/migration")),
                    "/workspace/src/main/resources/db/migration");

    private String database;

    @BeforeAll
    static void prepareScripts() throws Exception {
        // Also works when the source checkout came from a Windows ZIP download.
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
        migrate("/workspace");
        try (var connection = connect()) {
            assertEquals(27, scalar(connection, "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema='hospital' AND table_type='BASE TABLE'"));
            assertEquals(3, scalar(connection, "SELECT count(*) FROM public.database_schema_migration"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM pg_extension WHERE extname='btree_gist'"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM pg_constraint "
                    + "WHERE conrelid='hospital.appointment'::regclass AND contype='x' "
                    + "AND conname IN ('confirmed_doctor_no_overlap','confirmed_room_no_overlap')"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM hospital.patient"));
            String before = journal(connection);
            migrate("/workspace");
            assertEquals(before, journal(connection), "Replay must preserve checksums and application timestamps");
        }
    }

    @Test
    void upgradesV001AndRejectsAnEditedAppliedMigration() throws Exception {
        String copy = "/tmp/upgrade_" + database;
        succeeds(POSTGRES.execInContainer("cp", "-a", "/workspace", copy));
        String migrations = copy + "/src/main/resources/db/migration/";
        succeeds(POSTGRES.execInContainer("rm", migrations + "V002__schedules_and_reservations.sql",
                migrations + "V003__synthetic_experiments.sql"));
        migrate(copy);
        try (var connection = connect()) {
            assertEquals(1, scalar(connection, "SELECT count(*) FROM public.database_schema_migration"));
            assertEquals(19, scalar(connection, "SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema='hospital' AND table_type='BASE TABLE'"));
        }
        succeeds(POSTGRES.execInContainer("cp",
                "/workspace/src/main/resources/db/migration/V002__schedules_and_reservations.sql",
                "/workspace/src/main/resources/db/migration/V003__synthetic_experiments.sql", migrations));
        migrate(copy);
        try (var connection = connect()) {
            assertEquals(3, scalar(connection, "SELECT count(*) FROM public.database_schema_migration"));
            String before = journal(connection);
            succeeds(POSTGRES.execInContainer("bash", "-c",
                    "printf '\\n-- checksum regression fixture\\n' >> " + migrations + "V001__hospital_and_availability.sql"));
            var result = script(copy + "/scripts/migrate-database.sh");
            assertNotEquals(0, result.getExitCode());
            assertTrue(result.getStderr().contains("changed after application"), result.getStderr());
            assertEquals(before, journal(connection), "Rejected migration must not change its journal");
        }
    }

    @Test
    void verifiesConstraintsSeedReplayFeasibilityAndReplanning() throws Exception {
        migrate("/workspace");
        sql("/workspace/db/verify/constraints.sql");
        succeeds(script("/workspace/scripts/seed-database.sh"));
        sql("/workspace/db/verify/replay.sql");
        sql("/workspace/db/verify/demo.sql");
        sql("/workspace/db/queries/experiments.sql");
        sql("/workspace/db/verify/replanning.sql");
        try (var connection = connect()) {
            assertEquals(150, scalar(connection, "SELECT count(*) FROM hospital.appointment WHERE status='CONFIRMED'"));
            assertEquals(300, scalar(connection, "SELECT count(*) FROM hospital.appointment WHERE status='CANDIDATE'"));
        }
    }

    @Test
    void jdbcRejectsInvalidRequestsAndPreservesTheTimestampInstant() throws Exception {
        migrate("/workspace");
        succeeds(script("/workspace/scripts/seed-database.sh"));
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            for (int duration : new int[]{-15, 0, 1441}) {
                rejects(connection, "UPDATE hospital.appointment_request SET duration_minutes=" + duration
                        + " WHERE code='demo15.req.001'", "23514", "appointment_request_duration_minutes_check");
            }
            // Insert a new request so an existing request_availability back-reference
            // cannot mask the patient FK that this assertion is meant to exercise.
            rejects(connection, "INSERT INTO hospital.appointment_request "
                    + "(code,patient_id,specialty_id,duration_minutes,urgency,requested_at) "
                    + "SELECT 'jdbc.invalid-patient',-999,specialty_id,30,'ROUTINE',current_timestamp "
                    + "FROM hospital.appointment_request WHERE code='demo15.req.001'",
                    "23503", "appointment_request_patient_id_fkey");
            OffsetDateTime input = OffsetDateTime.parse("2026-11-16T09:00:00+01:00");
            try (var statement = connection.prepareStatement("SELECT ?::timestamptz")) {
                statement.setObject(1, input);
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(input.toInstant(), result.getObject(1, OffsetDateTime.class).toInstant());
                }
            }
            assertEquals(150, scalar(connection, "SELECT count(*) FROM hospital.appointment_request"));
            connection.rollback();
        }
    }

    private Connection connect() throws SQLException {
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private ExecResult script(String path) throws Exception {
        return command("bash", path);
    }

    private ExecResult command(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 6];
        String[] environment = {"env", "PGHOST=127.0.0.1", "PGPORT=5432", "PGDATABASE=" + database,
                "PGUSER=" + POSTGRES.getUsername(), "PGPASSWORD=" + POSTGRES.getPassword()};
        System.arraycopy(environment, 0, command, 0, environment.length);
        System.arraycopy(arguments, 0, command, environment.length, arguments.length);
        return POSTGRES.execInContainer(command);
    }

    private void migrate(String root) throws Exception {
        succeeds(script(root + "/scripts/migrate-database.sh"));
    }

    private void sql(String path) throws Exception {
        succeeds(command("psql", "-X", "--set=ON_ERROR_STOP=1", "--file=" + path));
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

    private static void rejects(Connection connection, String sql, String state, String constraint) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            PSQLException error = assertThrows(PSQLException.class, () -> {
                try (var statement = connection.createStatement()) {
                    int changed = statement.executeUpdate(sql);
                    fail("Invalid edit unexpectedly changed " + changed + " rows");
                }
            });
            assertEquals(state, error.getSQLState());
            assertNotNull(error.getServerErrorMessage());
            assertEquals(constraint, error.getServerErrorMessage().getConstraint());
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }
}
