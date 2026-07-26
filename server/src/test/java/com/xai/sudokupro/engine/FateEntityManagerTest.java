package com.xai.sudokupro.engine;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.service.AISolverService;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.util.MemoryBank;
import com.xai.sudokupro.util.SecureRandomGenerator;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FateEntityManager} is decoration that runs inside a player's request, and its
 * two per-player counters are written from request threads. This class protects against
 * the defects that combination invites:
 *
 * <ul>
 *   <li><b>A cosmetic subsystem breaking a real request.</b> {@code evaluateAndTrigger}
 *       ran every entity in one unguarded loop, so a single entity throwing skipped all
 *       the entities after it and propagated the failure to the caller; a null board took
 *       the same route.</li>
 *   <li><b>A flavour roll charging the player.</b> AIDoubter called
 *       {@code getNextLogicalMove}, which increments the board's hint counter — the very
 *       field that disqualifies a perfect clear.</li>
 *   <li><b>Lost updates.</b> Both counters were {@code put(id, getOrDefault(id, 0) + 1)},
 *       a read-modify-write that a ConcurrentHashMap does not make atomic.</li>
 *   <li><b>Unbounded growth on caller-supplied keys.</b> Nothing bounded or removed
 *       entries, and {@code resetPlayerStreak} created one for any id handed to it.</li>
 * </ul>
 *
 * <p>Entity firing is probabilistic, so the RNG is pinned with a {@link SecureRandom} whose
 * {@code nextDouble()} is fixed: 0.0 makes every {@code chance(p)} true and 0.9 makes every
 * one of them false (no entity uses a probability above 0.06).
 */
class FateEntityManagerTest {

    /** Fixes {@code nextDouble()} so {@code chance(p)} is deterministic. */
    private static final class FixedRandom extends SecureRandom {
        private final double value;
        FixedRandom(double value) { this.value = value; }
        @Override public double nextDouble() { return value; }
    }

    private static final double ALWAYS = 0.0;
    private static final double NEVER  = 0.9;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ChaosEngine chaosEngine = mock(ChaosEngine.class);
    private AISolverService solver = mock(AISolverService.class);

    /**
     * SecureRandomGenerator keeps its SecureRandom in a static ThreadLocal, so a pinned
     * generator would leak into every later test on this thread if it were not put back.
     */
    @AfterEach
    void restoreThreadLocalRandom() {
        new SecureRandomGenerator(new SecureRandom(), registry);
    }

    private FateEntityManager managerWith(double roll) {
        return new FateEntityManager(
            chaosEngine,
            mock(GameService.class),
            new SecureRandomGenerator(new FixedRandom(roll), registry),
            solver,
            mock(MultiplayerBroadcaster.class),
            new MemoryBank());
    }

    /** A real, solvable board: the Wikipedia example puzzle. */
    private static SudokuBoard realBoard() {
        int[][] grid = {
            {5, 3, 0, 0, 7, 0, 0, 0, 0},
            {6, 0, 0, 1, 9, 5, 0, 0, 0},
            {0, 9, 8, 0, 0, 0, 0, 6, 0},
            {8, 0, 0, 0, 6, 0, 0, 0, 3},
            {4, 0, 0, 8, 0, 3, 0, 0, 1},
            {7, 0, 0, 0, 2, 0, 0, 0, 6},
            {0, 6, 0, 0, 0, 0, 2, 8, 0},
            {0, 0, 0, 4, 1, 9, 0, 0, 5},
            {0, 0, 0, 0, 8, 0, 0, 7, 9},
        };
        SudokuCell[][] cells = new SudokuCell[9][9];
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                cells[r][c] = new SudokuCell();
                if (grid[r][c] != 0) {
                    cells[r][c].setValue(grid[r][c], SudokuCell.MoveSource.INITIAL);
                    cells[r][c].setGiven(true);
                }
            }
        }
        return new SudokuBoard(cells, false, false, 0L, "fate-test");
    }

    // ── wiring ────────────────────────────────────────────────────────────────

    @Test
    void refusesToBeBuiltWithoutItsCollaborators() {
        SecureRandomGenerator rng = new SecureRandomGenerator(registry);
        GameService game = mock(GameService.class);
        MultiplayerBroadcaster caster = mock(MultiplayerBroadcaster.class);
        MemoryBank bank = new MemoryBank();

        assertThrows(NullPointerException.class,
            () -> new FateEntityManager(null, game, rng, solver, caster, bank));
        assertThrows(NullPointerException.class,
            () -> new FateEntityManager(chaosEngine, game, null, solver, caster, bank));
        assertThrows(NullPointerException.class,
            () -> new FateEntityManager(chaosEngine, game, rng, null, caster, bank));
    }

    // ── evaluateAndTrigger ────────────────────────────────────────────────────

    /**
     * Reproduction: with the entity loop unguarded, AIDoubter's exception escaped
     * {@code evaluateAndTrigger} into the caller and EntropyDealer — registered after it —
     * never ran.
     */
    @Test
    void oneBrokenEntityNeitherStopsTheRestNorReachesTheCaller() {
        when(solver.getNextLogicalMoveAsEnhancedMove(any()))
            .thenThrow(new IllegalStateException("solver exploded"));
        FateEntityManager manager = managerWith(ALWAYS);

        assertDoesNotThrow(() -> manager.evaluateAndTrigger("player-1", realBoard()));

        // EntropyDealer is registered after AIDoubter; reaching it proves the loop survived.
        verify(chaosEngine, atLeastOnce()).boostEntropy(any());
    }

    @Test
    void aNullBoardIsIgnoredInsteadOfBeingJudged() {
        FateEntityManager manager = managerWith(ALWAYS);

        assertDoesNotThrow(() -> manager.evaluateAndTrigger("player-1", null));

        verifyNoInteractions(chaosEngine);
        verifyNoInteractions(solver);
    }

    @Test
    void nothingTriggersWhenEveryRollFails() {
        FateEntityManager manager = managerWith(NEVER);

        manager.evaluateAndTrigger("player-1", realBoard());

        verifyNoInteractions(chaosEngine);
        verifyNoInteractions(solver);
    }

    @Test
    void entropyDealerReseedsTheChaosEngineWhenItTriggers() {
        FateEntityManager manager = managerWith(ALWAYS);

        manager.evaluateAndTrigger("player-1", realBoard());

        verify(chaosEngine, atLeastOnce()).boostEntropy(any(byte[].class));
    }

    /**
     * Reproduction: AIDoubter used {@code getNextLogicalMove}, which calls
     * {@code board.incrementHintCount()} — so a triggered entity billed the player for a
     * hint they never asked for and {@code isPerfectClear()} could never be true again.
     */
    @Test
    void aTriggeredEntityDoesNotChargeThePlayerAHint() {
        FateEntityManager manager = new FateEntityManager(
            chaosEngine,
            mock(GameService.class),
            new SecureRandomGenerator(new FixedRandom(ALWAYS), registry),
            new AISolverService(new SecureRandomGenerator(registry)),   // the real solver
            mock(MultiplayerBroadcaster.class),
            new MemoryBank());
        SudokuBoard board = realBoard();

        manager.evaluateAndTrigger("player-1", board);

        assertEquals(0, board.getHintCount(),
            "a flavour entity must not consume the player's hint budget");
        assertFalse(board.isUsedUndo());
    }

    /** No entity may touch the puzzle itself. */
    @Test
    void triggeringEveryEntityLeavesTheGridUntouched() {
        FateEntityManager manager = new FateEntityManager(
            chaosEngine,
            mock(GameService.class),
            new SecureRandomGenerator(new FixedRandom(ALWAYS), registry),
            new AISolverService(new SecureRandomGenerator(registry)),
            mock(MultiplayerBroadcaster.class),
            new MemoryBank());
        SudokuBoard board = realBoard();
        String before = board.snapshotCells();

        manager.evaluateAndTrigger("player-1", board);

        assertEquals(before, board.snapshotCells());
        assertEquals(0, board.getMoveCount());
    }

    /**
     * Entity chatter used to be a raw {@code System.out.println} on a request thread —
     * a global monitor plus output no log collector ever sees. Asserting on a logging
     * event rather than on stdout is deliberate: the console appender writes to whatever
     * {@code System.out} currently is, so capturing the stream cannot tell the two apart.
     */
    @Test
    void entityChatterIsEmittedThroughTheLogger() {
        FateEntityManager manager = managerWith(ALWAYS);
        ch.qos.logback.classic.Logger fateLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FateEntityManager.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        fateLogger.addAppender(appender);
        try {
            manager.evaluateAndTrigger("player-1", realBoard());
        } finally {
            fateLogger.detachAppender(appender);
        }

        assertTrue(appender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("[FATE ENTITY]")),
            "no entity chatter reached the logger: " + appender.list);
    }

    // ── per-player counters ───────────────────────────────────────────────────

    @Test
    void failsAndStreaksAreCountedSeparately() {
        FateEntityManager manager = managerWith(NEVER);

        manager.recordPlayerFail("alice");
        manager.recordPlayerFail("alice");
        manager.recordPlayerStreak("alice");

        assertEquals(2, manager.getPlayerFails("alice"));
        assertEquals(1, manager.getPlayerStreak("alice"));
        assertEquals(0, manager.getPlayerFails("bob"));
        assertEquals(0, manager.getPlayerStreak("bob"));
    }

    /**
     * Reproduction of the lost-update bug: eight threads each adding 20,000 to the same
     * key. {@code put(id, getOrDefault(id, 0) + 1)} loses tens of thousands of them.
     */
    @Test
    void concurrentFailsAreAllCounted() throws Exception {
        FateEntityManager manager = managerWith(NEVER);
        int threads = 8, perThread = 20_000;

        runConcurrently(threads, () -> {
            for (int i = 0; i < perThread; i++) manager.recordPlayerFail("alice");
        });

        assertEquals(threads * perThread, manager.getPlayerFails("alice"));
    }

    @Test
    void concurrentStreaksAreAllCounted() throws Exception {
        FateEntityManager manager = managerWith(NEVER);
        int threads = 8, perThread = 20_000;

        runConcurrently(threads, () -> {
            for (int i = 0; i < perThread; i++) manager.recordPlayerStreak("alice");
        });

        assertEquals(threads * perThread, manager.getPlayerStreak("alice"));
    }

    @Test
    void resettingAStreakClearsIt() {
        FateEntityManager manager = managerWith(NEVER);
        manager.recordPlayerStreak("alice");
        manager.recordPlayerStreak("alice");

        manager.resetPlayerStreak("alice");

        assertEquals(0, manager.getPlayerStreak("alice"));
        assertEquals(0, manager.trackedStreakCount(), "the entry itself should be gone");
    }

    /** A reset must not be a way to allocate a permanent entry for an arbitrary id. */
    @Test
    void resettingAnUnknownPlayerDoesNotStartTrackingThem() {
        FateEntityManager manager = managerWith(NEVER);

        manager.resetPlayerStreak("never-seen-before");

        assertEquals(0, manager.trackedStreakCount());
    }

    @Test
    void nullAndBlankPlayerIdsAreIgnoredRatherThanThrowing() {
        FateEntityManager manager = managerWith(NEVER);

        assertDoesNotThrow(() -> {
            manager.recordPlayerFail(null);
            manager.recordPlayerStreak(null);
            manager.resetPlayerStreak(null);
            manager.recordPlayerFail("   ");
            manager.recordPlayerStreak("");
        });

        assertEquals(0, manager.getPlayerFails(null));
        assertEquals(0, manager.getPlayerStreak(null));
        assertEquals(0, manager.trackedFailCount());
        assertEquals(0, manager.trackedStreakCount());
    }

    @Test
    void trackingIsCappedRatherThanGrowingForever() {
        FateEntityManager manager = managerWith(NEVER);

        for (int i = 0; i < FateEntityManager.MAX_TRACKED_PLAYERS + 500; i++) {
            manager.recordPlayerFail("player-" + i);
            manager.recordPlayerStreak("player-" + i);
        }

        assertTrue(manager.trackedFailCount() <= FateEntityManager.MAX_TRACKED_PLAYERS,
            "fails map grew to " + manager.trackedFailCount());
        assertTrue(manager.trackedStreakCount() <= FateEntityManager.MAX_TRACKED_PLAYERS,
            "streaks map grew to " + manager.trackedStreakCount());
    }

    /** The cap must stop new keys, not stop counting for players already tracked. */
    @Test
    void anAlreadyTrackedPlayerKeepsCountingAfterTheCapIsReached() {
        FateEntityManager manager = managerWith(NEVER);
        manager.recordPlayerFail("alice");
        for (int i = 0; i < FateEntityManager.MAX_TRACKED_PLAYERS + 500; i++) {
            manager.recordPlayerFail("player-" + i);
        }

        manager.recordPlayerFail("alice");
        manager.recordPlayerFail("alice");

        assertEquals(3, manager.getPlayerFails("alice"));
    }

    private static void runConcurrently(int threads, Runnable body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    body.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "workers did not finish");
    }
}
