package com.xai.sudokupro.client.net;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the defect class "a retry loop that retries the wrong things, or forever".
 *
 * <p>The client had no reconnect logic at all, so these rules are new. Two of them
 * are the difference between a reconnect loop and a denial-of-service against your
 * own server: backoff that actually grows, and a stop condition for refusals the
 * server has already settled (a 1008 close for an unknown or un-spectatable game,
 * a 401/403 on the handshake).
 */
class ReconnectPolicyTest {

    private final ReconnectPolicy policy =
        new ReconnectPolicy(4, Duration.ofSeconds(1), Duration.ofSeconds(8), 2.0);

    @Test
    void delayGrowsGeometricallyFromTheInitialDelay() {
        assertEquals(Duration.ofSeconds(1), policy.delayFor(1));
        assertEquals(Duration.ofSeconds(2), policy.delayFor(2));
        assertEquals(Duration.ofSeconds(4), policy.delayFor(3));
    }

    @Test
    void delayIsCappedAtTheMaximum() {
        assertEquals(Duration.ofSeconds(8), policy.delayFor(4));
        assertEquals(Duration.ofSeconds(8), policy.delayFor(9),
            "A long outage must not produce an hour-long wait");

        // A ceiling the doubling does not land on exactly. With 1s/5s/x2 the
        // sequence is 1, 2, 4, 8 — so an uncapped implementation returns 8s here
        // and a capped one returns 5s. The powers-of-two case above cannot tell
        // those two apart, which is exactly the kind of test that proves nothing.
        ReconnectPolicy uneven =
            new ReconnectPolicy(9, Duration.ofSeconds(1), Duration.ofSeconds(5), 2.0);
        assertEquals(Duration.ofSeconds(5), uneven.delayFor(4));
        assertEquals(Duration.ofSeconds(5), uneven.delayFor(8));
    }

    @Test
    void delayForRejectsAttemptNumbersBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> policy.delayFor(0));
    }

    @Test
    void stopsAtTheAttemptBudget() {
        assertTrue(policy.shouldRetryAfterClose(4, 1006));
        assertFalse(policy.shouldRetryAfterClose(5, 1006),
            "Retrying past the budget is a spin loop against a host that is not coming back");
    }

    /**
     * 1008 is the server's "no, and asking again will not change it": an unknown
     * game id, or a competitive board that may not be spectated.
     */
    @Test
    void policyViolationCloseIsNeverRetried() {
        assertFalse(policy.shouldRetryAfterClose(1, ReconnectPolicy.POLICY_VIOLATION));
    }

    @Test
    void ordinaryCloseCodesAreRetried() {
        assertTrue(policy.shouldRetryAfterClose(1, 1006), "abnormal closure is exactly what retrying is for");
        assertTrue(policy.shouldRetryAfterClose(1, 1001), "server going away comes back");
        assertTrue(policy.shouldRetryAfterClose(1, CloseListener.TRANSPORT_ERROR));
    }

    @Test
    void authFailuresArePermanentWhateverTheAttemptNumber() {
        assertFalse(policy.shouldRetryAfterFailure(1, new ApiException(401, "bad credentials")));
        assertFalse(policy.shouldRetryAfterFailure(1, new ApiException(403, "not your board")));
        assertTrue(ReconnectPolicy.isPermanent(new ApiException(401, "bad credentials")));
    }

    @Test
    void transportFailuresAreRetriedUntilTheBudget() {
        ApiException down = new ApiException("cannot reach server", new java.io.IOException("refused"));
        assertTrue(policy.shouldRetryAfterFailure(1, down));
        assertFalse(policy.shouldRetryAfterFailure(5, down));
    }

    /** Futures wrap their causes; the rule must still see the 403 inside. */
    @Test
    void aWrappedAuthFailureIsStillPermanent() {
        assertTrue(ReconnectPolicy.isPermanent(
            new CompletionException(new ApiException(403, "not your board"))));
    }

    @Test
    void constructorRejectsNonsenseConfiguration() {
        assertThrows(IllegalArgumentException.class,
            () -> new ReconnectPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(1), 2.0));
        assertThrows(IllegalArgumentException.class,
            () -> new ReconnectPolicy(3, Duration.ZERO, Duration.ofSeconds(1), 2.0));
        assertThrows(IllegalArgumentException.class,
            () -> new ReconnectPolicy(3, Duration.ofSeconds(5), Duration.ofSeconds(1), 2.0));
        assertThrows(IllegalArgumentException.class,
            () -> new ReconnectPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(5), 0.5));
    }

    /** The shipped default must survive a server restart without hammering it. */
    @Test
    void theDefaultPolicyBacksOffAndGivesUp() {
        assertEquals(Duration.ofSeconds(1), ReconnectPolicy.DEFAULT.delayFor(1));
        assertEquals(Duration.ofSeconds(30), ReconnectPolicy.DEFAULT.delayFor(6),
            "1,2,4,8,16,32 capped at 30");
        assertFalse(ReconnectPolicy.DEFAULT.shouldRetryAfterClose(
            ReconnectPolicy.DEFAULT.maxAttempts() + 1, 1006));
    }
}
