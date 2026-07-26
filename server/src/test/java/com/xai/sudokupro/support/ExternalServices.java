package com.xai.sudokupro.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Connection details for the real PostgreSQL and Redis that the integration-grade
 * tests need, resolved from configuration so the SAME test runs on a developer
 * machine, in this container, and in CI.
 *
 * <p><b>Why this class exists.</b> {@code FlywayMigrationTest} and
 * {@code CrossReplicaBroadcastTest} were previously gated on
 * {@code @Testcontainers(disabledWithoutDocker = true)}. Wherever Docker was absent —
 * which is everywhere except a developer laptop — both classes were <em>silently</em>
 * disabled: four tests still counted toward the headline test number while the Flyway
 * chain and the cross-replica relay were never executed at all. Both subsystems shipped
 * unverified.
 *
 * <p><b>Resolution order</b> for every setting: JVM system property, then environment
 * variable, then the default. The environment-variable names are deliberately the same
 * ones {@code application.properties} reads ({@code DB_USERNAME}, {@code DB_PASSWORD},
 * {@code REDIS_HOST}, ...), so pointing a run at different services is one export.
 *
 * <p><b>A missing dependency is never silent.</b> {@link #requirePostgres()} and
 * {@link #requireRedis()} FAIL the test by default, with a message naming the exact
 * host/port that was tried and the properties that change it. Skipping is possible only
 * by explicitly opting in ({@code -Dsudokupro.it.optional=true} /
 * {@code SUDOKUPRO_IT_OPTIONAL=true}), and even then a banner is printed to stderr and
 * the abort message says the subsystem went unverified. CI never sets that flag: the
 * services are provisioned as job services, so an outage is a red build.
 */
public final class ExternalServices {

    private ExternalServices() {
    }

    // ---- configuration ----

    public static String dbHost()     { return get("sudokupro.it.db.host", "DB_HOST", "localhost"); }
    public static int    dbPort()     { return Integer.parseInt(get("sudokupro.it.db.port", "DB_PORT", "5432")); }
    public static String dbUser()     { return get("sudokupro.it.db.username", "DB_USERNAME", "postgres"); }
    public static String dbPassword() { return get("sudokupro.it.db.password", "DB_PASSWORD", ""); }

    /** Database used only to CREATE/DROP the throwaway databases the tests migrate. */
    public static String dbAdminDatabase() {
        return get("sudokupro.it.db.admin-database", "DB_ADMIN_DATABASE", "postgres");
    }

    public static String redisHost() { return get("sudokupro.it.redis.host", "REDIS_HOST", "localhost"); }
    public static int    redisPort() { return Integer.parseInt(get("sudokupro.it.redis.port", "REDIS_PORT", "6379")); }

    public static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + dbHost() + ":" + dbPort() + "/" + database;
    }

    /** A connection to {@code database} using the configured credentials. */
    public static Connection connect(String database) throws Exception {
        return DriverManager.getConnection(jdbcUrl(database), dbUser(), dbPassword());
    }

    // ---- availability, loudly ----

    /**
     * Verifies PostgreSQL is reachable and usable with the configured credentials.
     * Fails the test (default) or aborts loudly (opt-in) — never silently passes.
     */
    public static void requirePostgres() {
        try (Connection c = connect(dbAdminDatabase()); Statement st = c.createStatement()) {
            st.execute("select 1");
        } catch (Exception e) {
            unavailable("PostgreSQL", dbHost() + ":" + dbPort() + "/" + dbAdminDatabase()
                + " as user '" + dbUser() + "'",
                "-Dsudokupro.it.db.host / -Dsudokupro.it.db.port / -Dsudokupro.it.db.username"
                + " / -Dsudokupro.it.db.password (or DB_HOST/DB_PORT/DB_USERNAME/DB_PASSWORD)",
                "the Flyway V1-V9 migration chain would go completely unverified",
                e);
        }
    }

    /** Verifies Redis is reachable. Fails the test (default) or aborts loudly (opt-in). */
    public static void requireRedis() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(redisHost(), redisPort()), 3000);
            s.getOutputStream().write("PING\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            byte[] buf = new byte[7];
            int n = s.getInputStream().read(buf);
            String reply = n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.US_ASCII) : "";
            if (!reply.startsWith("+PONG")) {
                throw new IOException("expected +PONG from Redis, got: " + reply.trim());
            }
        } catch (Exception e) {
            unavailable("Redis", redisHost() + ":" + redisPort(),
                "-Dsudokupro.it.redis.host / -Dsudokupro.it.redis.port (or REDIS_HOST/REDIS_PORT)",
                "the cross-replica WebSocket broadcast relay would go completely unverified",
                e);
        }
    }

    private static void unavailable(String service, String endpoint, String howToPoint,
                                    String consequence, Exception cause) {
        String message = service + " is NOT reachable at " + endpoint + " — " + consequence + "."
            + "\n  cause: " + cause
            + "\n  Point the test at a running instance with: " + howToPoint + "."
            + "\n  This test deliberately FAILS rather than skipping: it replaced a"
            + " Testcontainers gate that silently disabled itself everywhere Docker was absent,"
            + " leaving the subsystem untested while still counting as a passing test.";

        if (optional()) {
            System.err.println("\n=========================================================================");
            System.err.println("WARNING: SKIPPING " + service + " INTEGRATION TEST — SUBSYSTEM UNVERIFIED");
            System.err.println(message);
            System.err.println("=========================================================================\n");
            org.junit.jupiter.api.Assumptions.abort(
                "UNVERIFIED SUBSYSTEM: " + service + " unavailable and sudokupro.it.optional=true. " + message);
        }
        fail(message);
    }

    /** True only when a run has explicitly opted out of requiring the real services. */
    public static boolean optional() {
        return Boolean.parseBoolean(get("sudokupro.it.optional", "SUDOKUPRO_IT_OPTIONAL", "false"));
    }

    private static String get(String systemProperty, String envVar, String defaultValue) {
        String v = System.getProperty(systemProperty);
        if (v == null || v.isBlank()) v = System.getenv(envVar);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }
}
