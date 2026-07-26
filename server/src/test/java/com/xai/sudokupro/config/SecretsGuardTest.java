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
     * ever applied.
     *
     * <p>The wiring — that Spring actually instantiates this bean under lazy
     * initialization — is covered by {@code secretsGuardImplementsTheHookThatSurvivesLazyInit}
     * below, which is a structural assertion rather than a boot. A previous version of this
     * comment claimed the wiring was "covered end to end by booting the prod profile", and
     * no such test existed anywhere in the repository. A false coverage claim is worse than
     * an acknowledged gap, because it stops anyone looking.
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

    /**
     * The guard must implement the lifecycle hook that Boot exempts from the lazy sweep.
     *
     * <p>{@code SudokuProApplication.optimizeStartup} calls
     * {@code setLazyInitialization(true)}, and Boot's lazy post-processor marks every bean
     * lazy except {@code SmartInitializingSingleton}. Nothing injects or fetches this bean,
     * so as a plain lazy component it was never constructed and the entire credential check
     * was dead in every deployment for its whole life — a prod server started happily on
     * ADMIN_PASSWORD=secret.
     *
     * <p>Asserting the interface is what keeps that fix from being quietly undone: an
     * innocent-looking change to "just implement InitializingBean like everything else"
     * would restore the original defect, and no behavioural test in this suite would notice,
     * because @SpringBootTest never goes through the application's own startup path.
     */
    @org.junit.jupiter.api.Test
    void secretsGuardImplementsTheHookThatSurvivesLazyInit() {
        org.junit.jupiter.api.Assertions.assertTrue(
            org.springframework.beans.factory.SmartInitializingSingleton.class
                .isAssignableFrom(SecretsGuard.class),
            "SecretsGuard must implement SmartInitializingSingleton — it is the only "
                + "lifecycle hook Boot exempts from lazy initialization, and nothing "
                + "injects this bean, so any other hook means the check never runs");
    }
}
