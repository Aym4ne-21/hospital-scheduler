package com.hospitalscheduler.persistence;

import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Shared isolated-database lifecycle; native JDBC is opt-in for disposable test servers. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgresIntegrationTest {
    private PostgreSQLContainer postgres;
    private PGSimpleDataSource admin;
    protected PGSimpleDataSource dataSource;
    private String database;

    @BeforeAll
    void startPostgres() {
        String url = System.getProperty("scheduler.test.jdbc.url");
        admin = new PGSimpleDataSource();
        if (url == null) {
            postgres = new PostgreSQLContainer(System.getProperty("scheduler.postgres.image", "postgres:18.7-bookworm"));
            postgres.start();
            admin.setURL(postgres.getJdbcUrl());
            admin.setUser(postgres.getUsername());
            admin.setPassword(postgres.getPassword());
        } else {
            admin.setURL(url);
            admin.setUser(System.getProperty("scheduler.test.jdbc.user", "postgres"));
            admin.setPassword(System.getProperty("scheduler.test.jdbc.password", ""));
        }
    }

    @AfterAll
    void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void prepareDatabase() throws Exception {
        database = "persistence_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = admin.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE " + database);
        }
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(admin.getURL());
        dataSource.setDatabaseName(database);
        dataSource.setUser(admin.getUser());
        dataSource.setPassword(admin.getPassword());
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            for (String migration : migrations()) {
                executeMigration(c, migration);
            }
            c.commit();
        }
    }

    @AfterEach
    void removeDatabase() throws Exception {
        if (database != null) {
            try (var c = admin.getConnection(); var s = c.createStatement()) {
                s.executeUpdate("DROP DATABASE " + database);
            }
        }
    }


    protected List<String> migrations() {
        return List.of("V001__hospital_and_availability.sql", "V002__schedules.sql",
                "V003__confirmed_appointment_exclusions.sql");
    }

    protected void executeMigration(Connection connection, String filename) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/" + filename);
             var statement = connection.createStatement()) {
            assertNotNull(stream);
            statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
