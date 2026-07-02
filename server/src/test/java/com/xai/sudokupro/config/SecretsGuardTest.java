package com.xai.sudokupro.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression tests for AUDIT P0-2: fail fast on missing or well-known credentials. */
class SecretsGuardTest {

    @Test
    void missingDbPasswordFailsStartup() {
        SecretsGuard guard = new SecretsGuard("", "a-real-secret-9x!");
        assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
    }

    @Test
    void missingAdminPasswordFailsStartup() {
        SecretsGuard guard = new SecretsGuard("a-real-secret-9x!", "");
        assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
    }

    @Test
    void wellKnownDefaultsFailStartup() {
        assertThrows(IllegalStateException.class,
            () -> new SecretsGuard("sudoku123", "a-real-secret-9x!").afterPropertiesSet());
        assertThrows(IllegalStateException.class,
            () -> new SecretsGuard("a-real-secret-9x!", "Secret").afterPropertiesSet());
        // The .env.example placeholder must also be rejected.
        assertThrows(IllegalStateException.class,
            () -> new SecretsGuard("CHANGE_ME", "a-real-secret-9x!").afterPropertiesSet());
    }

    @Test
    void realCredentialsPass() {
        SecretsGuard guard = new SecretsGuard("kJ8#mQ2vLp", "wR5$nT7xZc");
        assertDoesNotThrow(guard::afterPropertiesSet);
    }
}
