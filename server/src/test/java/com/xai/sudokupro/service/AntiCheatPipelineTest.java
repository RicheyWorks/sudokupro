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

    private static User userWith(long id, String ip, String platform) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
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
        User cheater = userWith(42L, "10.0.0.1", "ios");
        when(userRepository.findById(42L)).thenReturn(Optional.of(cheater));

        assertTrue(engine.getCheatSuspicionScores().isEmpty(),
            "precondition: no suspicion recorded yet");

        // 900ms for a difficulty-1 board is far under the 10s-per-difficulty floor.
        engine.scoreCompletedGame(boardSolvedIn(Duration.ofMillis(900)), "42");

        Map<String, Double> scores = engine.getCheatSuspicionScores();
        assertTrue(scores.containsKey("42"),
            "a suspiciously fast solve must register against the player");
        assertTrue(scores.get("42") > 0.0,
            "the score the scheduler compares against its threshold must be non-zero");
    }

    /**
     * The threshold AntiCheatScheduler enforces on is 75. If repeated offences could not
     * accumulate past it, wiring the pipeline up would still leave enforcement dead.
     */
    @Test
    void repeatedOffencesAccumulatePastTheSchedulerEnforcementThreshold() {
        User cheater = userWith(7L, "10.0.0.2", "web");
        when(userRepository.findById(7L)).thenReturn(Optional.of(cheater));

        for (int i = 0; i < 20; i++) {
            engine.scoreCompletedGame(boardSolvedIn(Duration.ofMillis(500)), "7");
        }

        double score = engine.getCheatSuspicionScores().getOrDefault("7", 0.0);
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
        User innocent = userWith(1L, "10.0.0.3", "android");
        when(userRepository.findById(1L)).thenReturn(Optional.of(innocent));

        engine.scoreCompletedGame(boardSolvedIn(Duration.ZERO), "1");

        assertEquals(0.0, engine.getCheatSuspicionScores().getOrDefault("1", 0.0),
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
        User player = userWith(9L, "203.0.113.7", "ios");
        when(userRepository.findById(9L)).thenReturn(Optional.of(player));

        engine.scoreCompletedGame(boardSolvedIn(Duration.ofMinutes(4)), "9");

        assertEquals(Integer.valueOf(1),
            engine.getIPSolveCounts().getOrDefault("203.0.113.7", Map.of()).get("9"),
            "the IP-clustering detector needs a solve recorded against the IP");
        assertTrue(engine.getDeviceSwitches().getOrDefault("9", Map.of()).containsKey("ios"),
            "the device-switch detector needs the platform recorded against the player");
    }

    /** Anonymous and non-numeric ids have no User row; scoring them must be a no-op. */
    @Test
    void unresolvablePlayerIdsAreSkippedRatherThanThrowing() {
        assertFalse(engine.scoreCompletedGame(boardSolvedIn(Duration.ofSeconds(1)), "anonymous"));
        assertFalse(engine.scoreCompletedGame(boardSolvedIn(Duration.ofSeconds(1)), "not-a-number"));
        assertFalse(engine.scoreCompletedGame(boardSolvedIn(Duration.ofSeconds(1)), null));
        assertFalse(engine.scoreCompletedGame(null, "1"));
        assertTrue(engine.getCheatSuspicionScores().isEmpty());
        verify(userRepository, never()).findById(anyLong());
    }

    /** A player the repository does not know about must not be scored either. */
    @Test
    void anUnknownUserIdIsSkipped() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
        assertFalse(engine.scoreCompletedGame(boardSolvedIn(Duration.ofMillis(1)), "1234"));
        assertTrue(engine.getCheatSuspicionScores().isEmpty());
    }

    /** Clean play must decay the score back down rather than latching it on. */
    @Test
    void cleanPlayDecaysAnExistingSuspicionScore() {
        User player = userWith(5L, "10.0.0.9", "web");
        when(userRepository.findById(5L)).thenReturn(Optional.of(player));

        engine.scoreCompletedGame(boardSolvedIn(Duration.ofMillis(100)), "5");
        double afterOffence = engine.getCheatSuspicionScores().getOrDefault("5", 0.0);
        assertTrue(afterOffence > 0.0);

        // A twelve-minute solve trips nothing.
        engine.scoreCompletedGame(boardSolvedIn(Duration.ofMinutes(12)), "5");
        double afterCleanGame = engine.getCheatSuspicionScores().getOrDefault("5", 0.0);

        assertTrue(afterCleanGame < afterOffence,
            "a clean game must decay suspicion, got " + afterCleanGame + " vs " + afterOffence);
    }
}
