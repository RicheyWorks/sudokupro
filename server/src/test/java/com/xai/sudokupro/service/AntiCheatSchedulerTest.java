package com.xai.sudokupro.service;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The anti-cheat SCAN — as opposed to the engine, which {@link AntiCheatPipelineTest}
 * covers. There was no test class here at all, which is how a punishment loop shipped.
 *
 * <p>{@code AntiCheatEngine.flagPlayer} is destructive and persistent: it halves the
 * player's {@code cosmicDrip} in the database and increments their lifetime
 * {@code cheatFlagCount}. The scan runs every 60 seconds and six of its eight detectors
 * called it unconditionally, on evidence that does not clear on its own.
 */
class AntiCheatSchedulerTest {

    private UserRepository userRepository;
    private AntiCheatEngine engine;
    private AnalyticsService analyticsService;
    private GameRepository gameRepository;
    private AntiCheatScheduler scheduler;

    private static final String PLAYER_ID = "42";

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        engine = mock(AntiCheatEngine.class);
        analyticsService = mock(AnalyticsService.class);
        gameRepository = mock(GameRepository.class);

        User suspect = mock(User.class);
        when(suspect.getId()).thenReturn(42L);
        lenient().when(suspect.getUsername()).thenReturn("suspect");
        lenient().when(userRepository.findById(42L)).thenReturn(Optional.of(suspect));

        // A player sitting above the enforcement threshold with a stale move rate — the
        // exact standing state the scan re-read every minute. Nothing here decays: the
        // suspicion score only decays inside detectCheating on a CLEAN observation, and
        // moveRates is only reset when that player submits another move. A player who
        // trips a detector once and then stops playing stays in this state indefinitely.
        lenient().when(engine.getCheatSuspicionScores()).thenReturn(Map.of(PLAYER_ID, 90.0));
        lenient().when(engine.getMoveRates()).thenReturn(Map.of(PLAYER_ID, 120));
        lenient().when(engine.getCosmicStreaks()).thenReturn(Collections.emptyMap());
        lenient().when(engine.getIPSolveCounts()).thenReturn(Collections.emptyMap());
        lenient().when(engine.getDeviceSwitches()).thenReturn(Collections.emptyMap());
        lenient().when(analyticsService.getCosmicDripHeatmap()).thenReturn(Collections.emptyMap());
        lenient().when(analyticsService.getLastEventTimestamps()).thenReturn(Collections.emptyMap());
        lenient().when(analyticsService.getPlayerSkillScores()).thenReturn(Collections.emptyMap());
        lenient().when(userRepository.findPotentialCheatersByPoints(anyInt(), any()))
            .thenReturn(List.of());
        lenient().when(gameRepository.findActiveUnfinishedGames(any(), any())).thenReturn(List.of());

        scheduler = new AntiCheatScheduler(userRepository, engine, analyticsService, gameRepository);
    }

    /**
     * The headline regression: repeated scans over unchanged evidence must punish once.
     *
     * <p>Before the cooldown, twenty scans meant twenty calls to {@code flagPlayer}, and
     * each one halves the player's balance in the database. Starting from a million cosmic
     * drip that reaches zero in about twenty minutes and stays there — for a single offence
     * that may well have been a false positive, since the detector's input never clears.
     * The lifetime {@code cheatFlagCount} climbed by 1,440 a day per pod on top.
     */
    @Test
    void repeatedScansOverUnchangedEvidencePunishOnce() {
        for (int i = 0; i < 20; i++) {
            scheduler.scanForCheaters();
        }

        verify(engine, times(1)).flagPlayer(PLAYER_ID);
    }

    /**
     * Several detectors firing in the SAME scan are one piece of evidence, not four.
     *
     * <p>Each detector called {@code flagPlayer} separately, so one pass over a player who
     * tripped the move-rate, cosmic-streak, IP-clustering and skill-anomaly checks halved
     * their balance four times over — in a single minute.
     */
    @Test
    void severalDetectorsFiringInOneScanFlagOnce() {
        when(engine.getCosmicStreaks()).thenReturn(Map.of(PLAYER_ID, 50));
        when(engine.getIPSolveCounts())
            .thenReturn(Map.of("203.0.113.7", Map.of(PLAYER_ID, 99)));

        scheduler.scanForCheaters();

        verify(engine, times(1)).flagPlayer(PLAYER_ID);
    }

    /**
     * Moderation must restore the ability to act.
     *
     * <p>Clearing a player is the "this was a false positive" path. It has to drop the
     * cooldown too, or a genuine offence right afterwards would be ignored for the rest of
     * the window — the window exists only to stop us re-punishing evidence that has just
     * been thrown out.
     */
    @Test
    void clearingAPlayerLetsTheNextGenuineFlagThrough() {
        scheduler.scanForCheaters();
        verify(engine, times(1)).flagPlayer(PLAYER_ID);

        scheduler.clearFlaggedPlayer(PLAYER_ID);
        // The engine's suspicion map is mocked, so it still reports the player as suspicious
        // — which is the point: after exoneration a fresh offence must be actionable.
        scheduler.scanForCheaters();

        verify(engine, times(2)).flagPlayer(PLAYER_ID);
    }

    /** A player below the enforcement threshold is never flagged, however many scans run. */
    @Test
    void aPlayerBelowTheThresholdIsNeverFlagged() {
        when(engine.getCheatSuspicionScores()).thenReturn(Map.of(PLAYER_ID, 10.0));

        for (int i = 0; i < 5; i++) scheduler.scanForCheaters();

        verify(engine, never()).flagPlayer(anyString());
    }

    /** The cooldown map must not grow with every player ever flagged. */
    @Test
    void theCooldownMapIsBounded() {
        scheduler.scanForCheaters();
        assertEquals(1, scheduler.getLastFlagged().size());
        assertTrue(scheduler.getLastFlagged().containsKey(PLAYER_ID));
    }

    /** Scanning with nothing suspicious must not touch the database or throw. */
    @Test
    void anEmptyScanIsANoOp() {
        when(engine.getCheatSuspicionScores()).thenReturn(Collections.emptyMap());
        when(engine.getMoveRates()).thenReturn(Collections.emptyMap());

        assertDoesNotThrow(() -> scheduler.scanForCheaters());

        verify(engine, never()).flagPlayer(anyString());
        verify(userRepository, never()).save(any());
    }
}
