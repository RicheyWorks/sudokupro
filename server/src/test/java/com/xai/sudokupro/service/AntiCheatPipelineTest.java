package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The anti-cheat scoring pipeline.
 *
 * <p>These tests exist because the subsystem was structurally inert rather than merely
 * buggy: {@link AntiCheatEngine#detectCheating(SudokuBoard, User)} is the only writer of
 * the suspicion score that {@link AntiCheatScheduler} enforces on, and its sole caller
 * was {@code EventEngine.submitEventScore} — a method with no callers anywhere in the
 * codebase. The map was therefore always empty, every one of the scheduler's eight
 * detectors compared {@code 0.0 >= 75} once a minute, and nobody could ever be flagged
 * for anything. Nothing failed; the system simply had no input.
 */
class AntiCheatPipelineTest {

    private UserRepository userRepository;
    private GameRepository gameRepository;
    private AnalyticsService analyticsService;
    private AntiCheatEngine engine;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        gameRepository = mock(GameRepository.class);
        analyticsService = mock(AnalyticsService.class);
        when(analyticsService.getPlayerSkillScores()).thenReturn(Collections.emptyMap());
        when(analyticsService.getDuelWins()).thenReturn(Collections.emptyMap());
        engine = new AntiCheatEngine(analyticsService, userRepository, gameRepository);
    }

    /**
     * A mock user carrying the identity production actually uses: the USERNAME. The engine
     * used to key everything by the numeric id, and this helper used to hand out ids —
     * which is exactly how the tests kept passing while every real player was skipped.
     */
    private static User userWith(String username, String ip, String platform) {
        User u = mock(User.class);
        lenient().when(u.getUsername()).thenReturn(username);
        when(u.getLastLoginIp()).thenReturn(ip);
        when(u.getPlatform()).thenReturn(platform);
        when(u.getDuelWins()).thenReturn(0);
        return u;
    }

    /** A board that reports a plausible elapsed time, as a real finished game does. */
    private static SudokuBoard boardSolvedIn(Duration elapsed) {
        SudokuBoard board = spy(new SudokuBoard(1, false, false, 0, "ac-test"));
        doReturn(elapsed).when(board).getSolveTime();
        return board;
    }

    /**
     * The headline regression: scoring a finished game must actually move the number the
     * scheduler reads. Before {@code scoreCompletedGame} existed there was no path from
     * gameplay into {@code suspicionScoreMap} at all.
     */
    @Test
    void scoringACompletedGameFeedsTheSuspicionScoreTheSchedulerReads() {
        User cheater = userWith("speedster", "10.0.0.1", "ios");
        when(userRepository.findByUsername("speedster")).thenReturn(Optional.of(cheater));

        assertTrue(engine.getCheatSuspicionScores().isEmpty(),
            "precondition: no suspicion recorded yet");

        // 900ms for a difficulty-1 board is far under the 10s-per-difficulty floor.
        engine.scoreCompletedGame(boardSolvedIn(Duration.ofMillis(900)), "speedster");

        Map<String, Double> scores = engine.getCheatSuspicionScores();
        assertTrue(scores.containsKey("speedster"),
            "a suspiciously fast solve must register against the player");
        assertTrue(scores.get("speedster") > 0.0,
            "the score the scheduler compares against its threshold must be non-zero");
    }

    /**
     * The duel-win detector must fire on a BURST, and must not fire on a veteran.
     *
     * <p>It used to require the in-memory analytics count to be exactly equal to the
     * player's persisted lifetime count, with both above the threshold. Those measure
     * different things — analytics counts what this process has seen and trims its maps,
     * while {@code User.duelWins} is the lifetime total across every replica — so they
     * coincide only inside one pod's uptime for a player who started from zero. After any
     * restart, and on every pod of a multi-replica deployment, the detector could not fire
     * at all. Pass 15 made the analytics side reachable; this pins that the comparison
     * now means something.
     */
    @Test
    void aBurstOfDuelWinsIsScoredButALifetimeVeteranIsNot() {
        User burster = userWith("burster", "10.0.0.9", "web");
        when(userRepository.findByUsername("burster")).thenReturn(Optional.of(burster));
        // 21 wins seen recently, in this process — over the threshold of 20. The analytics
        // map is keyed by username, which is why the engine's numeric-id lookup could
        // never see this number before the identity fix.
        when(analyticsService.getDuelWins()).thenReturn(Map.of("burster", 21));

        engine.scoreCompletedGame(boardSolvedIn(Duration.ofMinutes(5)), "burster");

        assertTrue(engine.getCheatSuspicionScores().getOrDefault("burster", 0.0) > 0.0,
            "twenty-one duel wins inside one window is the signal this detector exists for");

        // A veteran: thousands of wins on the account, but almost none recently.
        User veteran = mock(User.class);
        lenient().when(veteran.getUsername()).thenReturn("veteran");
        when(veteran.getLastLoginIp()).thenReturn("10.0.0.10");
        when(veteran.getPlatform()).thenReturn("web");
        lenient().when(veteran.getDuelWins()).thenReturn(5_000);
        when(userRepository.findByUsername("veteran")).thenReturn(Optional.of(veteran));
        when(analyticsService.getDuelWins()).thenReturn(Map.of("veteran", 2));

        engine.scoreCompletedGame(boardSolvedIn(Duration.ofMinutes(5)), "veteran");

        assertEquals(0.0, engine.getCheatSuspicionScores().getOrDefault("veteran", 0.0),
            "a long-standing player with a big lifetime record and no recent burst must "
                + "not be flagged — the rate is the signal, not the total");
    }

    /**
     * The threshold AntiCheatScheduler enforces on is 75. If repeated offences could not
     * accumulate past it, wiring the pipeline up would still leave enforcement dead.
     */
    @Test
    void repeatedOffencesAccumulatePastTheSchedulerEnforcementThreshold() {
        User cheater = userWith("persistent-cheat", "10.0.0.2", "web");
        when(userRepository.findByUsername("persistent-cheat")).thenReturn(Optional.of(cheater));

        for (int i = 0; i < 20; i++) {
            engine.scoreCompletedGame(boardSolvedIn(Duration.ofMillis(500)), "persistent-cheat");
        }

        double score = engine.getCheatSuspicionScores().getOrDefault("persistent-cheat", 0.0);
        assertTrue(score >= 75.0,
            "sustained impossible solve times must reach the enforcement threshold, got " + score);
    }

    /**
     * Regression: {@code detectCheating(board, user)} counted {@code solveTime == 0} as
     * "solved impossibly fast" — twice over, since the per-move-time signal divides by the
     * same zero total. Zero is what an unsolved board reports, and it was also what every
     * board rehydrated from the database or Redis reported. With the pipeline now live,
     * that would have manufactured suspicion from a serialization artefact rather than
     * from behaviour. The single-argument overload always guarded this; this one did not.
     */
    @Test
    void aBoardReportingNoElapsedTimeIsNotTreatedAsAnInstantSolve() {
        User innocent = userWith("innocent", "10.0.0.3", "android");
        when(userRepository.findByUsername("innocent")).thenReturn(Optional.of(innocent));

        engine.scoreCompletedGame(boardSolvedIn(Duration.ZERO), "innocent");

        assertEquals(0.0, engine.getCheatSuspicionScores().getOrDefault("innocent", 0.0),
            "an unsolved or rehydrated board must not generate suspicion signals");
    }

    /**
     * Regression: AntiCheatScheduler's IP-clustering and device-switch detectors read
     * {@code ipSolveCounts} and {@code deviceSwitches}, but nothing ever wrote them —
     * {@code detectCheating} computed the ip and platform locally and then discarded both.
     * Two of the eight detectors were reading permanently empty maps.
     */
    @Test
    void observingAGameRecordsTheIpAndDeviceTheSchedulerDetectorsRead() {
        User player = userWith("mobile-ada", "203.0.113.7", "ios");
        when(userRepository.findByUsername("mobile-ada")).thenReturn(Optional.of(player));

        engine.scoreCompletedGame(boardSolvedIn(Duration.ofMinutes(4)), "mobile-ada");

        assertEquals(Integer.valueOf(1),
            engine.getIPSolveCounts().getOrDefault("203.0.113.7", Map.of()).get("mobile-ada"),
            "the IP-clustering detector needs a solve recorded against the IP");
        assertTrue(engine.getDeviceSwitches().getOrDefault("mobile-ada", Map.of()).containsKey("ios"),
            "the device-switch detector needs the platform recorded against the player");
    }

    /** Anonymous and blank ids have no User row; scoring them must be a no-op. */
    @Test
    void unresolvablePlayerIdsAreSkippedRatherThanThrowing() {
        assertFalse(engine.scoreCompletedGame(boardSolvedIn(Duration.ofSeconds(1)), "anonymous"));
        assertFalse(engine.scoreCompletedGame(boardSolvedIn(Duration.ofSeconds(1)), null));
        assertFalse(engine.scoreCompletedGame(null, "somebody"));
        assertTrue(engine.getCheatSuspicionScores().isEmpty());
        verify(userRepository, never()).findByUsername(anyString());
    }

    /** A player the repository does not know about must not be scored either. */
    @Test
    void anUnknownUsernameIsSkipped() {
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        assertFalse(engine.scoreCompletedGame(boardSolvedIn(Duration.ofMillis(1)), "ghost"));
        assertTrue(engine.getCheatSuspicionScores().isEmpty());
    }

    /** Clean play must decay the score back down rather than latching it on. */
    @Test
    void cleanPlayDecaysAnExistingSuspicionScore() {
        User player = userWith("redeemed", "10.0.0.9", "web");
        when(userRepository.findByUsername("redeemed")).thenReturn(Optional.of(player));

        engine.scoreCompletedGame(boardSolvedIn(Duration.ofMillis(100)), "redeemed");
        double afterOffence = engine.getCheatSuspicionScores().getOrDefault("redeemed", 0.0);
        assertTrue(afterOffence > 0.0);

        // A twelve-minute solve trips nothing.
        engine.scoreCompletedGame(boardSolvedIn(Duration.ofMinutes(12)), "redeemed");
        double afterCleanGame = engine.getCheatSuspicionScores().getOrDefault("redeemed", 0.0);

        assertTrue(afterCleanGame < afterOffence,
            "a clean game must decay suspicion, got " + afterCleanGame + " vs " + afterOffence);
    }
}
