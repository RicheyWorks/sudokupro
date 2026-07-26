package com.xai.sudokupro.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
/*
 * Implements SmartInitializingSingleton, and that is load-bearing rather than stylistic.
 *
 * SudokuProApplication.optimizeStartup calls app.setLazyInitialization(true), which makes
 * Boot's LazyInitializationBeanFactoryPostProcessor mark EVERY bean definition lazy — the
 * only built-in exemption is SmartInitializingSingleton. This class is injected by nothing
 * and fetched by nothing, so as a plain lazy @Component it was never instantiated and
 * afterPropertiesSet() never ran. The entire fail-fast credential check has been dead since
 * the day it was written: a prod deployment started happily on ADMIN_PASSWORD=secret and
 * DB_PASSWORD=sudoku123 — the exact defaults still shipped in application-dev.properties,
 * and the exact values this class exists to reject. Verified by booting the real
 * application under the prod profile with both defaults: no log line, no exception, and
 * /admin/constants answered 200 to admin:secret. Adding
 * --spring.main.lazy-initialization=false to the same command made it refuse to start.
 *
 * Neither unit nor integration tests could catch it: SecretsGuardTest constructs the class
 * directly, and @SpringBootTest never goes through SudokuProApplication.start(), so lazy
 * initialization is never applied in CI.
 */
@Component
@Profile("!dev & !test")
public class SecretsGuard implements SmartInitializingSingleton {

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

    /**
     * Boot exempts SmartInitializingSingleton from the lazy sweep, so this runs once the
     * singleton pre-instantiation phase completes — whether or not anything injects this
     * bean, and regardless of {@code spring.main.lazy-initialization}. That is the whole
     * point: as a plain lazy {@code @Component} with no injector, the check never ran.
     */
    @Override
    public void afterSingletonsInstantiated() {
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
