package com.xai.sudokupro.engine;

import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.util.SecureRandomGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Regression: ChaosEngine's five per-player maps were an unbounded heap leak.
 *
 * <p>{@code playerLuck}, {@code playerProfiles}, {@code playerKarma},
 * {@code playerBrainprints} and {@code universeSignatures} are all keyed by player id and
 * were only ever cleared by {@code resetPlayerState}, which runs on the "RESET" event
 * alone. Meanwhile {@code GameService.applyMove} fires
 * {@code chaosEngine.onGameEvent("MOVE", playerId)} on <em>every single move</em>, and
 * that path writes {@code playerLuck} and creates a {@code LuckProfile}. So the server
 * accumulated permanent entries for every player who had ever made one move and released
 * them only on restart — memory growing with lifetime unique players, holding transient
 * flavour state that nothing needs past a session.
 */
class ChaosEngineEvictionTest {

    private static ChaosEngine newEngine() {
        return new ChaosEngine(
            new SecureRandomGenerator(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            mock(GameService.class));
    }

    @Test
    void trackedPlayersAreCappedRatherThanGrowingForever() {
        ChaosEngine chaos = newEngine();
        int overflow = ChaosEngine.MAX_TRACKED_PLAYERS + 500;

        for (int i = 0; i < overflow; i++) {
            chaos.onGameEvent("MOVE", "player-" + i);
        }

        assertTrue(chaos.trackedPlayerCount() <= ChaosEngine.MAX_TRACKED_PLAYERS,
            "expected at most " + ChaosEngine.MAX_TRACKED_PLAYERS
                + " tracked players, got " + chaos.trackedPlayerCount());
    }

    @Test
    void theColdestPlayersAreEvictedAndTheHottestSurvive() {
        ChaosEngine chaos = newEngine();

        // Fill to capacity.
        for (int i = 0; i < ChaosEngine.MAX_TRACKED_PLAYERS; i++) {
            chaos.onGameEvent("MOVE", "player-" + i);
        }
        // Keep one early player warm, then push the cap over with newcomers.
        chaos.onGameEvent("MOVE", "player-0");
        for (int i = 0; i < 100; i++) {
            chaos.onGameEvent("MOVE", "newcomer-" + i);
        }

        assertNotEquals(0.0, chaos.getPlayerLuck("player-0"),
            "a recently active player must not be evicted ahead of colder ones");
        assertNotEquals(0.0, chaos.getPlayerLuck("newcomer-99"),
            "the most recent player must still be tracked");
    }

    @Test
    void evictionClearsEveryMapForThatPlayerNotJustOne() {
        ChaosEngine chaos = newEngine();
        chaos.setKarma("victim", 5.0);
        chaos.updateBrainprint("victim", "pattern");
        chaos.adjustRealityParameters("victim", "signature-x");
        assertEquals(5.0, chaos.getKarma("victim"), 0.001);

        chaos.resetPlayerState("victim");

        assertEquals(0.0, chaos.getKarma("victim"), 0.001);
        assertEquals("", chaos.getLastKnownBrainprint("victim"));
        assertEquals("default", chaos.getUniverseSignature("victim"));
        assertEquals(0.0, chaos.getPlayerLuck("victim"), 0.001);
        assertEquals(0, chaos.trackedPlayerCount(),
            "a stale lastTouch entry would keep the player counted after eviction");
    }

    @Test
    void ordinaryPlayIsUnaffectedByTheCap() {
        ChaosEngine chaos = newEngine();
        chaos.updateLuck("regular", 0.25);
        assertEquals(0.25, chaos.getPlayerLuck("regular"), 0.001);
        assertEquals(1, chaos.trackedPlayerCount());
    }
}
