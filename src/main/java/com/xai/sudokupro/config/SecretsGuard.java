package com.xai.sudokupro.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Fail-fast credential check (AUDIT P0-2).
 *
 * Outside the dev/test profiles, the application must not start with missing or
 * well-known credentials. Previously {@code application.properties} shipped fallback
 * defaults ({@code DB_PASSWORD:sudoku123}, {@code ADMIN_PASSWORD:secret}), so a
 * production deployment that forgot the env vars ran happily with guessable
 * credentials guarding {@code /admin/**}. The fallbacks are now empty and this
 * guard turns an empty or well-known value into a startup failure instead of a
 * silently insecure deployment.
 */
@Component
@Profile("!dev & !test")
public class SecretsGuard implements InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(SecretsGuard.class);

    /** Values that must never guard a non-dev deployment. */
    private static final Set<String> WELL_KNOWN = Set.of(
        "sudoku123", "secret", "admin", "password", "changeme", "change_me", "postgres");

    private final String dbPassword;
    private final String adminPassword;

    public SecretsGuard(
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${spring.security.user.password:}") String adminPassword) {
        this.dbPassword = dbPassword;
        this.adminPassword = adminPassword;
    }

    @Override
    public void afterPropertiesSet() {
        require("DB_PASSWORD (spring.datasource.password)", dbPassword);
        require("ADMIN_PASSWORD (spring.security.user.password)", adminPassword);
        logger.info("SecretsGuard: credential checks passed");
    }

    private static void require(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Refusing to start: " + name + " is not set. Provide it via the environment, "
                + "or run with the 'dev' profile for local development.");
        }
        if (WELL_KNOWN.contains(value.toLowerCase())) {
            throw new IllegalStateException(
                "Refusing to start: " + name + " is set to a well-known default value. "
                + "Choose a real secret, or run with the 'dev' profile for local development.");
        }
    }
}
