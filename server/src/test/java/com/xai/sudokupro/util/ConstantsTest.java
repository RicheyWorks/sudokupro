package com.xai.sudokupro.util;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Constants class to ensure config values load successfully.
 * Runs against in-memory H2 via the test profile — no local Postgres needed. (AUDIT P1-1)
 */
@SpringBootTest
@ActiveProfiles("test")
class ConstantsTest {

    @Autowired
    private Constants constants;

    @Test
    void constantsConfigLoadsSuccessfully() {
        assertTrue(constants.getXpPerSolveEasy() > 0, "XP per easy solve should be positive");
        assertTrue(constants.getPointsPerSolveEasy() > 0, "Points per easy solve should be positive");
        assertTrue(constants.getXpPerLevel() > 0, "XP per level should be positive");
    }
}
