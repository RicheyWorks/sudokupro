package com.xai.sudokupro.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real Flyway chain against real PostgreSQL (Testcontainers).
 * Skipped automatically where Docker is unavailable; CI runs it.
 *
 * Covers both deployment scenarios:
 *  1. Fresh database — V1 (baseline schema) + V2 + V3 apply cleanly.
 *  2. Legacy pre-Flyway database — baseline-on-migrate skips V1; V2
 *     converts the old BIGINT start_time column to TIMESTAMP and V3
 *     adds the cells_json grid-snapshot column.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayMigrationTest {

    private static final String LOCATIONS_COMMON = "classpath:db/migration/common";
    private static final String LOCATIONS_PG = "classpath:db/migration/postgresql";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    @Order(1)
    void freshDatabaseGetsFullSchema() throws Exception {
        Flyway flyway = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations(LOCATIONS_COMMON, LOCATIONS_PG)
            .baselineOnMigrate(true)
            .baselineVersion("1")
            .load();

        var result = flyway.migrate();
        assertTrue(result.success, "Migration chain must apply cleanly on a fresh database");

        try (Connection c = connect(postgres.getJdbcUrl())) {
            // All baseline tables exist
            for (String table : new String[]{"sudoku_boards", "users", "user_achievements",
                                             "user_friends", "user_match_history", "user_power_ups"}) {
                assertTrue(tableExists(c, table), "V1 must create table " + table);
            }
            // start_time is TIMESTAMP from day one on fresh installs
            assertEquals("timestamp without time zone", columnType(c, "sudoku_boards", "start_time"));
            // cells_json (save/load grid snapshot) exists on fresh installs
            assertEquals("text", columnType(c, "sudoku_boards", "cells_json"));
            // All migrations recorded
            assertTrue(migrationApplied(c, "1"), "V1 must be recorded");
            assertTrue(migrationApplied(c, "2"), "V2 must be recorded (idempotent no-op here)");
            assertTrue(migrationApplied(c, "3"), "V3 (cells_json) must be recorded");
        }
    }

    @Test
    @Order(2)
    void legacyDatabaseIsBaselinedAndStartTimeConverted() throws Exception {
        // Second database in the same container simulates a pre-Flyway install.
        String legacyUrl;
        try (Connection admin = connect(postgres.getJdbcUrl());
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE legacy");
        }
        legacyUrl = postgres.getJdbcUrl().replaceAll("/[^/?]+(\\?|$)", "/legacy$1");

        // Minimal legacy shape: the ddl-auto=update era left start_time as BIGINT (epoch millis).
        try (Connection c = connect(legacyUrl); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE sudoku_boards (id bigserial primary key, game_id varchar(255), " +
                       "start_time bigint)");
            st.execute("INSERT INTO sudoku_boards (game_id, start_time) VALUES ('g1', 1719900000000)");
        }

        Flyway flyway = Flyway.configure()
            .dataSource(legacyUrl, postgres.getUsername(), postgres.getPassword())
            .locations(LOCATIONS_COMMON, LOCATIONS_PG)
            .baselineOnMigrate(true)   // same settings as application.properties
            .baselineVersion("1")
            .load();
        var result = flyway.migrate();
        assertTrue(result.success);

        try (Connection c = connect(legacyUrl)) {
            // Baselined past V1 (schema pre-exists), V2 applied
            assertFalse(migrationApplied(c, "1"), "V1 must be skipped on a baselined legacy database");
            assertTrue(migrationApplied(c, "2"), "V2 must run on a legacy database");
            assertTrue(migrationApplied(c, "3"), "V3 must run on a legacy database");
            assertEquals("text", columnType(c, "sudoku_boards", "cells_json"),
                "V3 must add cells_json to upgraded legacy databases");
            // The BIGINT column is now a real timestamp, data converted not lost
            assertEquals("timestamp without time zone", columnType(c, "sudoku_boards", "start_time"));
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT extract(year FROM start_time) FROM sudoku_boards WHERE game_id='g1'")) {
                assertTrue(rs.next());
                assertEquals(2024, (int) rs.getDouble(1), "Epoch-millis value must convert to the same instant");
            }
        }
    }

    // ---- helpers ----

    private static Connection connect(String url) throws Exception {
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    private static boolean tableExists(Connection c, String table) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT 1 FROM information_schema.tables WHERE table_name = '" + table + "'")) {
            return rs.next();
        }
    }

    private static String columnType(Connection c, String table, String column) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT data_type FROM information_schema.columns WHERE table_name = '"
                 + table + "' AND column_name = '" + column + "'")) {
            assertTrue(rs.next(), "column " + table + "." + column + " must exist");
            return rs.getString(1);
        }
    }

    private static boolean migrationApplied(Connection c, String version) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT 1 FROM flyway_schema_history WHERE version = '" + version
                 + "' AND type NOT IN ('BASELINE') AND success")) {
            return rs.next();
        }
    }
}
