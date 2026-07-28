package com.xai.sudokupro.service;

import com.xai.sudokupro.model.GameEvent;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.repository.leaderboard.LeaderboardRepository;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnalyticsService#getPlayerSkillScores()} and the leaderboard that
 * consumes it.
 *
 * <p><b>Defect class this file protects against: a stubbed collaborator that silently
 * empties a whole feature.</b> {@code getPlayerSkillScores()} was
 * {@code return new HashMap<>();} — a compatibility stub sitting under a
 * "// ---- COMPATIBILITY METHODS ----" banner. Three production callers read it:
 * {@link LeaderboardService#getTopPlayersCombinedPaged(int, int)} ranks by it,
 * {@link AntiCheatEngine#detectCheating(com.xai.sudokupro.model.SudokuBoard, User)} compares
 * a player against the peer average of it, and {@code AntiCheatScheduler} runs its
 * skill-anomaly detector over it. All three degraded to no-ops without an error anywhere:
 * the combined leaderboard endpoint returned {@code []} for every request, forever, and
 * looked healthy doing it. The whole ranking, paging, tie-breaking and suspicion-filtering
 * machinery above the stub was correct and unreachable.
 *
 * <p>The tests below are deliberately <em>relative</em>: each one varies exactly one input
 * dimension between two otherwise-identical players and asserts the direction of the
 * resulting difference, plus the documented range invariant. None of them recompute the
 * production formula — a test carrying its own copy of the weights would pass against any
 * self-consistent nonsense and would have to be rewritten every time the weights are tuned.
 */
class PlayerSkillScoreTest {

    private AnalyticsService analytics;

    @BeforeEach
    void setUp() {
        analytics = new AnalyticsService(
                mock(AISolverService.class), mock(UserRepository.class), mock(GameRepository.class));
    }

    // ------------------------------------------------------------------
    // Event helpers — these mirror exactly what GameService emits in production:
    // MOVE from applyMove, HINT from getHint, SOLVE from endGame on a solved board.
    // ------------------------------------------------------------------

    private void recordSolve(String playerId, long seconds) {
        analytics.recordEvent(new GameEvent(GameEvent.EventType.SOLVE, playerId,
                Map.of("solveTimeSeconds", String.valueOf(seconds))));
    }

    private void recordMoves(String playerId, int count) {
        for (int i = 0; i < count; i++) {
            analytics.recordEvent(new GameEvent(GameEvent.EventType.MOVE, playerId,
                    Map.of("row", String.valueOf(i % 9), "col", String.valueOf(i % 9), "value", "1")));
        }
    }

    private void recordHints(String playerId, int count) {
        for (int i = 0; i < count; i++) {
            analytics.recordEvent(new GameEvent(GameEvent.EventType.HINT, playerId,
                    Map.of("hint", "r1c1=5", "gameId", "g")));
        }
    }

    // ------------------------------------------------------------------
    // The headline defect: the combined leaderboard is empty in production
    // ------------------------------------------------------------------

    /**
     * Reproduction of the P1. This wires the <em>real</em> {@link AnalyticsService} into the
     * real {@link LeaderboardService} — mocking the analytics service here would stub the
     * very method under test and the test could not fail. Two players finish real puzzles,
     * and the combined board must show them.
     *
     * <p>Against the stub this returns an empty list: no skill scores means no page ids
     * means an early {@code return Collections.emptyList()}.
     */
    @Test
    void combinedLeaderboardReturnsRowsOncePlayersHaveSolvedPuzzles() {
        UserRepository userRepository = mock(UserRepository.class);
        LeaderboardService leaderboard = new LeaderboardService(
                userRepository, mock(LeaderboardRepository.class), analytics,
                mock(AntiCheatEngine.class), mock(MultiplayerBroadcaster.class), mock(EventEngine.class));

        // Ada is the stronger solver: same puzzle count, half the time, no hints. The
        // keys are USERNAMES — production's shape. This test used to seed "1"/"2", the
        // numeric-string ids the old Long.parseLong path happened to accept, which is how
        // the headline regression test for this board passed while the board itself
        // returned empty for every real player.
        recordSolve("ada", 120);
        recordMoves("ada", 40);
        recordSolve("bob", 600);
        recordMoves("bob", 40);
        recordHints("bob", 6);

        when(userRepository.findByUsernameIn(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new User(1L, "ada"), new User(2L, "bob")));

        List<LeaderboardService.LeaderboardSnapshot> board = leaderboard.getTopPlayersCombinedPaged(0, 10);

        assertEquals(2, board.size(),
                "the combined board must show the players who actually solved puzzles; "
                        + "an empty board here is the stubbed-scorer defect");
        assertEquals("ada", board.get(0).username(), "the stronger solver must rank first");
        assertEquals("bob", board.get(1).username());
        assertEquals(List.of(1, 2), board.stream()
                .map(LeaderboardService.LeaderboardSnapshot::rank).toList());
    }

    // ------------------------------------------------------------------
    // Eligibility
    // ------------------------------------------------------------------

    /**
     * A solve is the evidence a skill score is built from. Someone who has only pushed
     * numbers around and taken hints without ever finishing a puzzle has produced no
     * evidence of skill, so they must not be scored at all — scoring them 0 would put them
     * on the combined board below everyone else, which is a claim the data does not support.
     */
    @Test
    void aPlayerWhoHasNeverFinishedAPuzzleIsNotScored() {
        recordMoves("drifter", 200);
        recordHints("drifter", 12);
        recordSolve("finisher", 300);

        Map<String, Double> scores = analytics.getPlayerSkillScores();

        assertFalse(scores.containsKey("drifter"), "no solve means no skill evidence");
        assertTrue(scores.containsKey("finisher"));
    }

    // ------------------------------------------------------------------
    // Directional properties — one varied dimension per test
    // ------------------------------------------------------------------

    /** Same puzzles, same moves, no hints either side: the quicker player scores higher. */
    @Test
    void aFasterSolverScoresHigherThanASlowerOne() {
        recordSolve("quick", 60);
        recordMoves("quick", 45);
        recordSolve("slow", 900);
        recordMoves("slow", 45);

        Map<String, Double> scores = analytics.getPlayerSkillScores();

        assertTrue(scores.get("quick") > scores.get("slow"),
                "solve speed must raise the score: quick=" + scores.get("quick") + " slow=" + scores.get("slow"));
    }

    /** Identical solve times and move counts; only hint reliance differs. */
    @Test
    void leaningOnHintsLowersTheScore() {
        recordSolve("unaided", 300);
        recordMoves("unaided", 45);
        recordSolve("assisted", 300);
        recordMoves("assisted", 45);
        recordHints("assisted", 8);

        Map<String, Double> scores = analytics.getPlayerSkillScores();

        assertTrue(scores.get("unaided") > scores.get("assisted"),
                "hint usage must lower the score: unaided=" + scores.get("unaided")
                        + " assisted=" + scores.get("assisted"));
    }

    /** Identical solve times and hint counts; only the number of moves spent differs. */
    @Test
    void spendingMoreMovesPerSolveLowersTheScore() {
        recordSolve("efficient", 300);
        recordMoves("efficient", 35);
        recordSolve("flailing", 300);
        recordMoves("flailing", 400);

        Map<String, Double> scores = analytics.getPlayerSkillScores();

        assertTrue(scores.get("efficient") > scores.get("flailing"),
                "move economy must raise the score: efficient=" + scores.get("efficient")
                        + " flailing=" + scores.get("flailing"));
    }

    /**
     * The score is a per-solve rate, not a lifetime accumulation — otherwise the board would
     * just rank by hours played, and it would drift upward forever for regulars (the same
     * defect {@code getAverageSolveTime} carried). Twenty identical solves must score the
     * same as one.
     */
    @Test
    void theScoreIsARateNotAnAccumulationSoItDoesNotDriftWithPlaytime() {
        recordSolve("oneshot", 240);
        recordMoves("oneshot", 50);
        recordHints("oneshot", 1);

        for (int i = 0; i < 20; i++) {
            recordSolve("regular", 240);
            recordMoves("regular", 50);
            recordHints("regular", 1);
        }

        Map<String, Double> scores = analytics.getPlayerSkillScores();

        assertEquals(scores.get("oneshot"), scores.get("regular"), 1e-9,
                "identical per-puzzle performance must produce an identical score regardless of volume");
    }

    // ------------------------------------------------------------------
    // Range and lifecycle
    // ------------------------------------------------------------------

    /**
     * Callers depend on the range. {@code AntiCheatEngine} compares a player against
     * {@code peerAverage * 2.5}, and {@code AntiCheatScheduler} against
     * {@code peerAverage + 50}; both are meaningless if the scale is unbounded, and a
     * negative score would sort a player below a non-participant.
     */
    @Test
    void everyScoreLiesInsideTheDocumentedZeroToOneHundredRange() {
        recordSolve("instant", 0);                 // best case: no time, no moves, no hints
        recordSolve("glacial", 86_400);            // worst case: a full day per puzzle
        recordMoves("glacial", 5_000);
        recordHints("glacial", 5_000);

        Map<String, Double> scores = analytics.getPlayerSkillScores();

        assertEquals(2, scores.size());
        scores.forEach((player, score) -> {
            assertTrue(score >= 0.0 && score <= 100.0, player + " scored out of range: " + score);
            assertTrue(Double.isFinite(score), player + " scored a non-finite value: " + score);
        });
        assertTrue(scores.get("instant") > scores.get("glacial"));
    }

    /** {@code resetAnalytics} must take the derived scores with it, not leave ghosts behind. */
    @Test
    void resetAnalyticsClearsTheScores() {
        recordSolve("p1", 100);
        assertFalse(analytics.getPlayerSkillScores().isEmpty());

        analytics.resetAnalytics();

        assertTrue(analytics.getPlayerSkillScores().isEmpty());
    }
}
