package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Chaos mode must disrupt the player without corrupting the puzzle.
 *
 * <p>{@code triggerChaosSwap} used to swap two arbitrary non-given cells with no legality
 * check whatsoever. Swapping two different values across a Sudoku almost always creates a
 * duplicate — moving a 5 into a row that already holds one — and the damage compounds:
 * {@code isValidMove} then rejects the player's own CORRECT moves, because the stray
 * duplicate blocks the value that genuinely belongs in that cell. The player is left
 * unable to finish a game they were playing correctly, with nothing on screen explaining
 * why.
 *
 * <p>Found by the live engine (engine/live_engine.py, suite L21), which plays chaos games
 * against a running server and checks the board's consistency afterwards. The in-process
 * harness could not have found it: chaos is triggered from {@code GameService.applyMove},
 * which the model-level engine never calls.
 */
class ChaosSwapLegalityTest {

    /** True when no filled cell duplicates another in its row, column or box. */
    private static boolean consistent(SudokuBoard board) {
        int[][] g = new int[9][9];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                g[r][c] = board.getBoard()[r][c].getValue();
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                int v = g[r][c];
                if (v == 0) continue;
                g[r][c] = 0;
                boolean good = legal(g, r, c, v);
                g[r][c] = v;
                if (!good) return false;
            }
        }
        return true;
    }

    private static boolean legal(int[][] g, int r, int c, int v) {
        for (int i = 0; i < 9; i++) if (g[r][i] == v || g[i][c] == v) return false;
        int br = (r / 3) * 3, bc = (c / 3) * 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (g[br + i][bc + j] == v) return false;
        return true;
    }

    /** Plays the puzzle's own solution into `filledCells` empty cells. */
    private static SudokuBoard partlyPlayedBoard(String id, int filledCells) {
        SudokuBoard board = new SudokuBoard(2, true, false, 0, id);
        int[][] grid = new int[9][9];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                grid[r][c] = board.getBoard()[r][c].getValue();
        assertTrue(fill(grid, 0), "test setup: the generated puzzle must be solvable");

        int done = 0;
        for (int r = 0; r < 9 && done < filledCells; r++) {
            for (int c = 0; c < 9 && done < filledCells; c++) {
                if (board.getBoard()[r][c].getValue() == 0) {
                    board.makeMove(r, c, grid[r][c], SudokuCell.MoveSource.PLAYER);
                    done++;
                }
            }
        }
        return board;
    }

    private static boolean fill(int[][] g, int idx) {
        if (idx == 81) return true;
        int r = idx / 9, c = idx % 9;
        if (g[r][c] != 0) return fill(g, idx + 1);
        for (int v = 1; v <= 9; v++) {
            if (!legal(g, r, c, v)) continue;
            g[r][c] = v;
            if (fill(g, idx + 1)) return true;
            g[r][c] = 0;
        }
        return false;
    }

    /** Invokes the private swap directly — it is the unit under test. */
    private static void chaosSwap(GameService service, SudokuBoard board) throws Exception {
        Method m = GameService.class.getDeclaredMethod("triggerChaosSwap", SudokuBoard.class);
        m.setAccessible(true);
        m.invoke(service, board);
    }

    private static GameService serviceForSwapOnly() {
        // triggerChaosSwap touches only the board and the RNG, so the rest of the graph
        // can be mocks; this keeps the test on the arithmetic rather than the wiring.
        return new GameService(
            mock(AISolverService.class),
            mock(com.xai.sudokupro.repository.GameRepository.class),
            mock(com.xai.sudokupro.websocket.MultiplayerBroadcaster.class),
            mock(org.springframework.data.redis.core.RedisTemplate.class),
            new com.xai.sudokupro.util.SecureRandomGenerator(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            mock(PlayerStateStore.class),
            mock(GameLockManager.class),
            mock(AnalyticsService.class),
            mock(AntiCheatEngine.class));
    }

    @Test
    void aChaosSwapNeverLeavesTheBoardIllegal() throws Exception {
        GameService service = serviceForSwapOnly();
        for (int attempt = 0; attempt < 40; attempt++) {
            SudokuBoard board = partlyPlayedBoard("chaos-" + attempt, 20);
            assertTrue(consistent(board), "test setup: the board starts consistent");

            for (int tick = 0; tick < 25; tick++) {
                chaosSwap(service, board);
                assertTrue(consistent(board),
                    "a chaos swap left the board holding a duplicate on attempt "
                        + attempt + ", tick " + tick + " — the player's own correct moves "
                        + "would now be rejected and the game becomes unwinnable");
            }
        }
    }

    /**
     * The player must still be able to finish. A legal-but-shuffled board keeps every
     * remaining move available, which is the whole difference between disruption and
     * corruption.
     */
    @Test
    void aBoardStaysCompletableAfterRepeatedChaos() throws Exception {
        GameService service = serviceForSwapOnly();
        SudokuBoard board = partlyPlayedBoard("chaos-complete", 25);
        for (int tick = 0; tick < 30; tick++) chaosSwap(service, board);

        int[][] grid = new int[9][9];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                grid[r][c] = board.getBoard()[r][c].isGiven()
                    ? board.getBoard()[r][c].getValue() : 0;
        assertTrue(fill(grid, 0),
            "after repeated chaos the puzzle's own clues no longer admit a solution");
    }

    /** Chaos must never touch a given clue — those define the puzzle. */
    @Test
    void chaosNeverMovesAGivenClue() throws Exception {
        GameService service = serviceForSwapOnly();
        SudokuBoard board = partlyPlayedBoard("chaos-givens", 20);
        int[][] givensBefore = new int[9][9];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                givensBefore[r][c] = board.getBoard()[r][c].isGiven()
                    ? board.getBoard()[r][c].getValue() : 0;

        for (int tick = 0; tick < 30; tick++) chaosSwap(service, board);

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (givensBefore[r][c] == 0) continue;
                assertEquals(givensBefore[r][c], board.getBoard()[r][c].getValue(),
                    "chaos moved the given clue at (" + r + "," + c + ")");
                assertTrue(board.getBoard()[r][c].isGiven(),
                    "chaos cleared the given flag at (" + r + "," + c + ")");
            }
        }
    }

    /** An empty or nearly-empty board must not throw or spin. */
    @Test
    void chaosOnABoardWithNothingToSwapIsANoOp() throws Exception {
        GameService service = serviceForSwapOnly();
        SudokuBoard board = new SudokuBoard(2, true, false, 0, "chaos-empty");
        assertDoesNotThrow(() -> chaosSwap(service, board));
        assertTrue(consistent(board));
    }
}
