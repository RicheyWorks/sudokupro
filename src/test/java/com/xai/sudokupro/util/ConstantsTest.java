package com.xai.sudokupro.util;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Constants class to ensure config values load successfully.
 */
@SpringBootTest
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
