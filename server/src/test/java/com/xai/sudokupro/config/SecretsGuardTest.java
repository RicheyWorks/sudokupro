package com.xai.sudokupro.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression tests for AUDIT P0-2: fail fast on missing or well-known credentials. */
class SecretsGuardTest {

    /*
     * These call the lifecycle hook directly, which is exactly why they never caught the
     * real defect: the guard was a plain lazy @Component that nothing injected, so Spring
     * never instantiated it and the hook never fired in a running application. Constructing
     * the object by hand proves the RULES are right; it says nothing about whether they are
     * ever applied. The wiring itself is covered end to end by booting the prod profile with
     * default credentials and asserting the process refuses to start.
     */


    @Test
    void missingDbPasswordFailsStartup() {
        SecretsGuard guard = new SecretsGuard("", "a-real-secret-9x!");
        assertThrows(IllegalStateException.class, guard::afterSingletonsInstantiated);
    }

    @Test
    void missingAdminPasswordFailsStartup() {
        SecretsGuard guard = new SecretsGuard("a-real-secret-9x!", "");
        assertThrows(IllegalStateException.class, guard::afterSingletonsInstantiated);
    }

    @Test
    void wellKnownDefaultsFailStartup() {
        assertThrows(IllegalStateException.class,
            () -> new SecretsGuard("sudoku123", "a-real-secret-9x!").afterSingletonsInstantiated());
        assertThrows(IllegalStateException.class,
            () -> new SecretsGuard("a-real-secret-9x!", "Secret").afterSingletonsInstantiated());
        // The .env.example placeholder must also be rejected.
        assertThrows(IllegalStateException.class,
            () -> new SecretsGuard("CHANGE_ME", "a-real-secret-9x!").afterSingletonsInstantiated());
    }

    @Test
    void realCredentialsPass() {
        SecretsGuard guard = new SecretsGuard("kJ8#mQ2vLp", "wR5$nT7xZc");
        assertDoesNotThrow(guard::afterSingletonsInstantiated);
    }
}
