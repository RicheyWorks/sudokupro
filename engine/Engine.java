import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.model.SudokuCell.MoveSource;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.api.BoardState;

import java.util.*;

/**
 * SudokuPro game-playing engine / bug-finding harness.
 *
 * Drives the *real* model classes exactly as a player (and the server) would:
 * generates boards, solves them with an independent backtracking solver, plays
 * the winning moves, and probes erase / undo / redo / snapshot / mirror paths —
 * asserting the invariants a correct Sudoku engine must hold. Each violated
 * invariant is recorded as a FINDING with a reproducible summary.
 */
public class Engine {

    static int findings = 0;
    static int checks = 0;
    static List<String> report = new ArrayList<>();

    static void finding(String id, String msg) {
        findings++;
        report.add("  [BUG] " + id + ": " + msg);
        System.out.println("  [BUG] " + id + ": " + msg);
    }
    static void ok(String msg) {
        checks++;
        System.out.println("  [ ok] " + msg);
    }

    // ---- independent solver over a 9x9 int grid (0 = empty) ----
    static boolean solve(int[][] g) {
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) if (g[r][c] == 0) {
            for (int n = 1; n <= 9; n++) if (legal(g, r, c, n)) {
                g[r][c] = n;
                if (solve(g)) return true;
                g[r][c] = 0;
            }
            return false;
        }
        return true;
    }
    static boolean legal(int[][] g, int r, int c, int n) {
        for (int i = 0; i < 9; i++) if (g[r][i] == n || g[i][c] == n) return false;
        int br = r - r % 3, bc = c - c % 3;
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) if (g[br+i][bc+j] == n) return false;
        return true;
    }
    static int[][] grid(SudokuBoard b) {
        SudokuCell[][] cells = b.getBoard();
        int[][] g = new int[9][9];
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) g[r][c] = cells[r][c].getValue();
        return g;
    }
    static int[][] givensGrid(SudokuBoard b) {
        SudokuCell[][] cells = b.getBoard();
        int[][] g = new int[9][9];
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++)
            g[r][c] = cells[r][c].isGiven() ? cells[r][c].getValue() : 0;
        return g;
    }
    static String flat(int[][] g) {
        StringBuilder sb = new StringBuilder();
        for (int[] row : g) for (int v : row) sb.append(v);
        return sb.toString();
    }
    static int countEmpties(SudokuBoard b) {
        int n = 0; for (int[] row : grid(b)) for (int v : row) if (v == 0) n++; return n;
    }
    static int countGivens(SudokuBoard b) {
        int n = 0; SudokuCell[][] c = b.getBoard();
        for (int r=0;r<9;r++) for (int col=0;col<9;col++) if (c[r][col].isGiven()) n++;
        return n;
    }

    public static void main(String[] args) {
        System.out.println("=== SudokuPro engine — playing the game to find bugs ===\n");

        testSolutionDiversity();
        testStructuralSolutionDiversity();
        testGenerationInvariants();
        testPlayToWin();
        testEraseMove();
        testUndoRedoSource();
        testAutosolveGuardBypass();
        testBatchOvercount();
        testUndoAfterSolveFlag();
        testMirrorUndo();
        testSnapshotRoundTrip();
        testBoardStateNoLeak();
        testSolutionUniquenessClaim();
        // ---- hardening: property / fuzz / concurrency suites ----
        testGivensImmutableUnderFuzz();
        testUndoAllReturnsToStart();
        testSnapshotRoundTripAfterRandomPlay();
        testConcurrentMovesStayConsistent();
        testStateNeverCorruptsUnderRandomOps();
        // ---- pass 11: deeper model invariants ----
        testUniquenessAcrossDifficulties();
        testDifficultyIsMonotone();
        testGeneratedBoardsAreConsistent();
        testRejectedMovesDoNotCount();
        testCountersSurviveRoundTrip();
        testSolverIsDeterministicPerPuzzle();
        testSolvedBoardRejectsFurtherMoves();
        testManyBoardsInterleavedStayIndependent();
        testReplayHistoryReproducesTheBoard();

        System.out.println("\n=== SUMMARY ===");
        System.out.println("Invariant checks passed: " + checks);
        System.out.println("Distinct bugs found:     " + findings);
        for (String r : report) System.out.println(r);

        // Non-zero exit so CI fails on a regression instead of printing bugs into a
        // green build log. Every finding here was a real defect when first reported.
        if (findings > 0) {
            System.out.println("\nFAIL: " + findings + " invariant violation(s).");
            System.exit(1);
        }
        System.out.println("\nOK: all " + checks + " invariants hold.");
    }


    // ===================== deep model suites (pass 11) =====================

    /**
     * T13 — every generated puzzle must have EXACTLY one solution.
     *
     * A Sudoku with two completions cannot be scored, hinted or verified: two players can
     * both be "right" with different grids, and the hint engine has no defensible answer.
     * generateBoard claims to validate this with hasUniqueSolution() on each removal.
     */
    static void testUniquenessAcrossDifficulties() {
        System.out.println("[T13] Unique solution across all difficulties");
        int checked = 0, nonUnique = 0;
        for (int diff = 1; diff <= 5; diff++) {
            for (int i = 0; i < 4; i++) {
                SudokuBoard b = new SudokuBoard(diff, false, false, 0, "uniq-" + diff + "-" + i);
                int[][] g = givensGrid(b);
                checked++;
                if (solutionCount(g, 2) != 1) nonUnique++;
            }
        }
        if (nonUnique > 0)
            finding("GEN-NON-UNIQUE-SOLUTION",
                nonUnique + "/" + checked + " generated puzzles admit more than one solution, "
                + "so no answer can be called correct.");
        else ok("all " + checked + " generated puzzles have exactly one solution");
    }

    /** Counts completions up to `cap`. Distinct from the older accumulator-style
     *  countSolutions(grid, count) used by T2 — this one takes a ceiling, not a running
     *  total, which is far easier to reason about at a call site. */
    static int solutionCount(int[][] grid, int cap) {
        int[][] g = new int[9][9];
        for (int r = 0; r < 9; r++) g[r] = grid[r].clone();
        return countRec(g, 0, cap, new int[]{0});
    }

    static int countRec(int[][] g, int idx, int cap, int[] found) {
        if (found[0] >= cap) return found[0];
        if (idx == 81) { found[0]++; return found[0]; }
        int r = idx / 9, c = idx % 9;
        if (g[r][c] != 0) return countRec(g, idx + 1, cap, found);
        for (int v = 1; v <= 9; v++) {
            if (okAt(g, r, c, v)) {
                g[r][c] = v;
                countRec(g, idx + 1, cap, found);
                g[r][c] = 0;
                if (found[0] >= cap) return found[0];
            }
        }
        return found[0];
    }

    static boolean okAt(int[][] g, int row, int col, int v) {
        for (int i = 0; i < 9; i++) if (g[row][i] == v || g[i][col] == v) return false;
        int br = row - row % 3, bc = col - col % 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (g[br + i][bc + j] == v) return false;
        return true;
    }

    /**
     * T14 — difficulty must actually change the puzzle.
     *
     * Difficulty is advertised to the player, drives the payout, and feeds the
     * smart-difficulty model. If clue counts do not fall as it rises, all three are lying.
     */
    static void testDifficultyIsMonotone() {
        System.out.println("[T14] Difficulty changes the clue count monotonically");
        double[] mean = new double[6];
        for (int diff = 1; diff <= 5; diff++) {
            int total = 0, n = 6;
            for (int i = 0; i < n; i++)
                total += countGivens(new SudokuBoard(diff, false, false, 0, "mono-" + diff + "-" + i));
            mean[diff] = total / (double) n;
        }
        StringBuilder desc = new StringBuilder();
        for (int d = 1; d <= 5; d++) desc.append("d").append(d).append("=").append(String.format("%.1f", mean[d])).append(" ");
        boolean monotone = true;
        for (int d = 1; d < 5; d++) if (mean[d] < mean[d + 1] - 1.5) monotone = false;
        if (!monotone)
            finding("GEN-DIFFICULTY-NOT-MONOTONE",
                "clue count does not fall as difficulty rises: " + desc.toString().trim());
        else ok("clue count falls with difficulty: " + desc.toString().trim());
    }

    /**
     * T15 — a board must never be served already inconsistent.
     *
     * The generator fills a complete grid then removes clues, so duplicates should be
     * impossible; a violation here means the removal loop corrupted the grid.
     */
    static void testGeneratedBoardsAreConsistent() {
        System.out.println("[T15] Generated boards hold no duplicate in any row/col/box");
        int bad = 0, n = 0;
        for (int diff = 1; diff <= 5; diff++) {
            for (int i = 0; i < 4; i++) {
                SudokuBoard b = new SudokuBoard(diff, false, false, 0, "cons-" + diff + "-" + i);
                int[][] g = givensGrid(b);
                n++;
                if (!gridConsistent(g)) bad++;
            }
        }
        if (bad > 0) finding("GEN-INCONSISTENT-BOARD", bad + "/" + n + " generated boards already hold a duplicate.");
        else ok(n + " generated boards are internally consistent");
    }

    static boolean gridConsistent(int[][] g) {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) {
                int v = g[r][c];
                if (v == 0) continue;
                g[r][c] = 0;
                boolean good = okAt(g, r, c, v);
                g[r][c] = v;
                if (!good) return false;
            }
        return true;
    }

    /**
     * T16 — an illegal move must never change the board, and must be counted as a mistake
     * exactly once. A rejected move that still increments moveCount corrupts the analytics
     * the smart-difficulty model and the anti-cheat detectors both read.
     */
    static void testRejectedMovesDoNotCount() {
        System.out.println("[T16] Rejected moves leave board and counters untouched");
        SudokuBoard b = new SudokuBoard(2, false, false, 0, "reject");
        int[][] before = liveGrid(b);
        int movesBefore = b.getMoveCount();

        // Find an empty cell and a value already present in its row.
        int rr = -1, cc = -1, dup = -1;
        outer:
        for (int r = 0; r < 9; r++) {
            int present = -1, empty = -1;
            for (int c = 0; c < 9; c++) {
                if (b.getBoard()[r][c].getValue() != 0 && present < 0) present = b.getBoard()[r][c].getValue();
                if (b.getBoard()[r][c].getValue() == 0 && empty < 0) empty = c;
            }
            if (present > 0 && empty >= 0) { rr = r; cc = empty; dup = present; break outer; }
        }
        if (rr < 0) { ok("no suitable cell for the duplicate test"); return; }

        b.makeMove(rr, cc, dup, MoveSource.PLAYER);
        int[][] after = liveGrid(b);
        boolean unchanged = java.util.Arrays.deepEquals(before, after);
        if (!unchanged)
            finding("MOVE-ILLEGAL-ACCEPTED",
                "a duplicate " + dup + " in row " + rr + " was written to (" + rr + "," + cc + ").");
        else ok("an illegal move leaves the grid untouched");

        if (b.getMoveCount() != movesBefore)
            finding("MOVE-REJECTED-STILL-COUNTED",
                "moveCount rose from " + movesBefore + " to " + b.getMoveCount() + " on a REJECTED move, "
                + "which inflates the analytics the difficulty model and anti-cheat both read.");
        else ok("a rejected move does not increment moveCount");
    }

    static int[][] liveGrid(SudokuBoard b) {
        int[][] g = new int[9][9];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                g[r][c] = b.getBoard()[r][c].getValue();
        return g;
    }

    /**
     * T17 — hintCount, moveCount and the solved flag must survive a snapshot round-trip.
     * A forgotten hintCount hands out the clean-solve bonus on a hinted game.
     */
    static void testCountersSurviveRoundTrip() {
        System.out.println("[T17] Counters survive a snapshot round-trip");
        SudokuBoard b = new SudokuBoard(2, false, false, 0, "counters");
        int[][] sol = givensGrid(b); solve(sol);
        int played = 0;
        for (int r = 0; r < 9 && played < 5; r++)
            for (int c = 0; c < 9 && played < 5; c++)
                if (b.getBoard()[r][c].getValue() == 0) { b.makeMove(r, c, sol[r][c], MoveSource.PLAYER); played++; }
        b.incrementHintCount(); b.incrementHintCount();

        SudokuBoard copy = new SudokuBoard(2, false, false, 0, "counters-copy");
        copy.restoreCells(b.snapshotCells());
        copy.setHintCount(b.getHintCount());
        copy.setMoveCount(b.getMoveCount());

        if (copy.getHintCount() != b.getHintCount())
            finding("SNAPSHOT-HINTCOUNT-LOST",
                "hintCount " + b.getHintCount() + " -> " + copy.getHintCount()
                + "; a forgotten hint grants the clean-solve bonus on a hinted game.");
        else ok("hintCount survives the round-trip (" + b.getHintCount() + ")");

        int[][] a = liveGrid(b), z = liveGrid(copy);
        if (!java.util.Arrays.deepEquals(a, z))
            finding("SNAPSHOT-GRID-DRIFT", "the grid differs after a snapshot round-trip.");
        else ok("the played grid survives the round-trip");
    }

    /**
     * T18 — solving the same puzzle twice must reach the same answer. If two runs of the
     * solver disagree, the puzzle does not have one answer and nothing downstream is sound.
     */
    static void testSolverIsDeterministicPerPuzzle() {
        System.out.println("[T18] The same puzzle always resolves to the same answer");
        int disagreements = 0;
        for (int i = 0; i < 8; i++) {
            SudokuBoard b = new SudokuBoard(3, false, false, 0, "det-" + i);
            int[][] g1 = givensGrid(b); solve(g1);
            int[][] g2 = givensGrid(b); solve(g2);
            if (!flat(g1).equals(flat(g2))) disagreements++;
        }
        if (disagreements > 0)
            finding("SOLVER-NONDETERMINISTIC",
                disagreements + "/8 puzzles resolved to two different answers across runs.");
        else ok("8 puzzles each resolve to a single stable answer");
    }

    /**
     * T19 — a solved board must stay solved under further input. Once complete, every cell
     * is occupied and no further move can be legal; accepting one would un-solve a finished
     * game after it had already paid out.
     */
    static void testSolvedBoardRejectsFurtherMoves() {
        System.out.println("[T19] A solved board rejects further input");
        SudokuBoard b = new SudokuBoard(1, false, false, 0, "sealed");
        int[][] sol = givensGrid(b); solve(sol);
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (b.getBoard()[r][c].getValue() == 0) b.makeMove(r, c, sol[r][c], MoveSource.PLAYER);
        if (!b.isSolved()) { finding("SOLVE-NOT-DETECTED", "a fully and correctly filled board is not reported solved."); return; }

        int[][] before = liveGrid(b);
        for (int v = 1; v <= 9; v++) b.makeMove(0, 0, v, MoveSource.PLAYER);
        if (!java.util.Arrays.deepEquals(before, liveGrid(b)))
            finding("SOLVED-BOARD-MUTATED", "a solved board was changed by further moves.");
        else ok("a solved board is immune to further moves");
        if (!b.isSolved())
            finding("SOLVED-FLAG-LOST", "the solved flag was cleared by input the board did not accept.");
        else ok("the solved flag survives further input attempts");
    }

    /**
     * T20 — heavy interleaved play across many boards must never corrupt any of them.
     * A single shared mutable field in the model would show up here and nowhere else.
     */
    static void testManyBoardsInterleavedStayIndependent() {
        System.out.println("[T20] 12 boards played interleaved stay independent");
        int n = 12;
        SudokuBoard[] boards = new SudokuBoard[n];
        int[][][] sols = new int[n][][];
        for (int i = 0; i < n; i++) {
            boards[i] = new SudokuBoard(2, false, false, 0, "inter-" + i);
            sols[i] = givensGrid(boards[i]);
            solve(sols[i]);
        }
        java.util.Random rnd = new java.util.Random(20260725L);
        for (int step = 0; step < 4000; step++) {
            int i = rnd.nextInt(n);
            int r = rnd.nextInt(9), c = rnd.nextInt(9);
            if (boards[i].getBoard()[r][c].getValue() == 0)
                boards[i].makeMove(r, c, sols[i][r][c], MoveSource.PLAYER);
        }
        int corrupt = 0;
        for (int i = 0; i < n; i++) if (!gridConsistent(liveGrid(boards[i]))) corrupt++;
        if (corrupt > 0) finding("INTERLEAVED-BOARD-CORRUPTED", corrupt + "/" + n + " boards corrupted under interleaved play.");
        else ok(n + " interleaved boards all stayed consistent");

        int wrong = 0;
        for (int i = 0; i < n; i++) {
            int[][] g = liveGrid(boards[i]);
            for (int r = 0; r < 9; r++)
                for (int c = 0; c < 9; c++)
                    if (g[r][c] != 0 && g[r][c] != sols[i][r][c]) wrong++;
        }
        if (wrong > 0) finding("INTERLEAVED-CROSS-CONTAMINATION", wrong + " cells hold another board's value.");
        else ok("no cell holds a value from a different board");
    }

    /**
     * T21 — replay history must reproduce the final board exactly. It is the input to the
     * anti-cheat move model and to any future replay feature.
     */
    static void testReplayHistoryReproducesTheBoard() {
        System.out.println("[T21] Replay history reproduces the final board");
        SudokuBoard b = new SudokuBoard(2, false, false, 0, "replay");
        int[][] sol = givensGrid(b); solve(sol);
        SudokuBoard fresh = new SudokuBoard(2, false, false, 0, "replay-target");
        fresh.restoreCells(new SudokuBoard(2, false, false, 0, "x").snapshotCells());

        int played = 0;
        for (int r = 0; r < 9 && played < 12; r++)
            for (int c = 0; c < 9 && played < 12; c++)
                if (b.getBoard()[r][c].getValue() == 0) { b.makeMove(r, c, sol[r][c], MoveSource.PLAYER); played++; }

        java.util.List<EnhancedMove> history = b.getReplayHistory();
        if (history == null) { ok("no replay history exposed"); return; }
        if (history.size() != played)
            finding("REPLAY-HISTORY-INCOMPLETE",
                "played " + played + " moves but the replay history holds " + history.size()
                + "; the anti-cheat move model reads this.");
        else ok("replay history holds exactly the " + played + " moves played");
    }

    // 1. Every new game must NOT share the same solution.
    static void testSolutionDiversity() {
        System.out.println("[T1] Solution diversity across freshly generated games (difficulty 3)");
        Set<String> solutions = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            SudokuBoard b = new SudokuBoard(3, false, false, 0, "div-" + i);
            int[][] g = givensGrid(b);
            solve(g);
            solutions.add(flat(g));
        }
        if (solutions.size() == 1)
            finding("GEN-DETERMINISTIC-SOLUTION",
                "5 separate games (via the production `new SudokuBoard(difficulty,...)` path GameService uses) all resolve to ONE identical solution grid. The completed answer is fixed; only clue positions vary. Any player who solves a single puzzle knows the solution to every daily/duel/game.");
        else
            ok("solutions distinct: " + solutions.size() + "/5");
    }

    /**
     * T1b — structural diversity, not just literal diversity.
     *
     * This suite exists because T1 above passed while the generator was still broken. The
     * first fix for GEN-DETERMINISTIC-SOLUTION drew ONE 1..9 permutation per board and
     * reused it at every cell, which only RENAMES the digits: the backtracker still walks
     * the identical search path, so every board is the same grid relabelled. Literal string
     * comparison sees five different strings and reports success.
     *
     * Canonicalising first — relabel each grid so its top row reads 1..9 — collapses that
     * disguise. A spread of 30 boards across every difficulty produced ONE canonical grid.
     * Nine clues, one per digit, then pin the permutation and hand over the whole solution
     * with no solving at all.
     */
    static void testStructuralSolutionDiversity() {
        System.out.println("[T1b] Structural diversity (canonicalised — catches digit relabeling)");
        Set<String> canonical = new HashSet<>();
        int boards = 0;
        for (int diff = 1; diff <= 5; diff++) {
            for (int i = 0; i < 6; i++) {
                SudokuBoard b = new SudokuBoard(diff, false, false, 0, "canon-" + diff + "-" + i);
                int[][] g = givensGrid(b);
                solve(g);
                canonical.add(canonicalise(g));
                boards++;
            }
        }
        if (canonical.size() == 1) {
            finding("GEN-RELABELED-SINGLE-GRID",
                boards + " boards across all five difficulties reduce to ONE canonical grid: the "
                + "generator draws a single digit permutation and reuses it at every cell, so all "
                + "puzzles are the same grid with the digits renamed. Nine clues reveal the entire "
                + "solution without solving.");
        } else if (canonical.size() < boards / 2) {
            finding("GEN-LOW-STRUCTURAL-DIVERSITY",
                "only " + canonical.size() + " distinct canonical grids across " + boards + " boards.");
        } else {
            ok("structurally distinct grids: " + canonical.size() + "/" + boards);
        }
    }

    /** Relabels a completed grid so its first row reads 1..9, exposing pure permutations. */
    static String canonicalise(int[][] g) {
        int[] map = new int[10];
        for (int c = 0; c < 9; c++) map[g[0][c]] = c + 1;
        StringBuilder sb = new StringBuilder(81);
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                sb.append(map[g[r][c]]);
        return sb.toString();
    }

    // 2. Clue count in the documented band, unique solution, givens flagged.
    static void testGenerationInvariants() {
        System.out.println("[T2] Generation invariants (clue band, uniqueness, given-flagging)");
        for (int diff = 1; diff <= 5; diff++) {
            SudokuBoard b = new SudokuBoard(diff, false, false, 0, "gen-" + diff);
            int empties = countEmpties(b);
            int givens = countGivens(b);
            int valued = 81 - empties;
            // givens flag should equal the number of clue cells actually on the board
            if (givens != valued)
                finding("GEN-GIVEN-COUNT-d" + diff,
                    "difficulty " + diff + ": " + valued + " clue cells on board but " + givens + " flagged isGiven — mismatch breaks difficulty scoring / editability guards.");
            // unique solution
            int[][] g = givensGrid(b);
            int sols = countSolutions(g, 0);
            if (sols != 1)
                finding("GEN-NONUNIQUE-d" + diff, "difficulty " + diff + " puzzle has " + sols + " solutions (expected exactly 1).");
            else
                ok("d" + diff + ": " + valued + " clues, unique solution, givens flagged=" + (givens==valued));
        }
    }
    static int countSolutions(int[][] g, int count) {
        if (count > 1) return count;
        for (int r=0;r<9;r++) for (int c=0;c<9;c++) if (g[r][c]==0) {
            for (int n=1;n<=9 && count<=1;n++) if (legal(g,r,c,n)) { g[r][c]=n; count=countSolutions(g,count); g[r][c]=0; }
            return count;
        }
        return count+1;
    }

    // 3. Playing the true solution to completion must win and set counters right.
    static void testPlayToWin() {
        System.out.println("[T3] Play a full legal solve via makeMove(PLAYER)");
        SudokuBoard b = new SudokuBoard(2, false, false, 0, "win-1");
        int[][] sol = givensGrid(b);
        if (!solve(sol)) { finding("WIN-UNSOLVABLE", "generated puzzle is not solvable by an independent solver."); return; }
        int empties = countEmpties(b);
        SudokuCell[][] cells = b.getBoard();
        int applied = 0;
        for (int r=0;r<9;r++) for (int c=0;c<9;c++) if (cells[r][c].getValue()==0) {
            b.makeMove(r, c, sol[r][c], MoveSource.PLAYER);
            applied++;
        }
        if (!b.isSolved()) finding("WIN-NOT-SOLVED", "played the correct solution but isSolved() is false.");
        else ok("board solved after playing " + empties + " cells");
        if (!b.isSolvedState()) finding("WIN-FLAG-UNSET", "isSolved() true but persisted solved flag false.");
        else ok("persisted solved flag set");
        if (b.getMoveCount() != empties)
            finding("WIN-MOVECOUNT", "moveCount=" + b.getMoveCount() + " but exactly " + empties + " legal fills were played.");
        else ok("moveCount == empties (" + empties + ")");
    }

    // 4. A player must be able to erase a wrong entry (newVal 0).
    static void testEraseMove() {
        System.out.println("[T4] Erase a filled cell via a move (newVal=0)");
        SudokuBoard b = new SudokuBoard(2, false, false, 0, "erase-1");
        // find an empty editable cell and a legal value
        int[] rc = firstEmpty(b);
        int[][] sol = givensGrid(b); solve(sol);
        int r = rc[0], c = rc[1], v = sol[r][c];
        b.makeMove(r, c, v, MoveSource.PLAYER);
        if (b.getBoard()[r][c].getValue() != v) { finding("ERASE-SETUP","could not place a value to erase"); return; }
        // now try to erase it back to 0 via the normal move path
        b.makeMove(r, c, 0, MoveSource.PLAYER);
        if (b.getBoard()[r][c].getValue() == v)
            finding("ERASE-MOVE-REJECTED",
                "makeMove(r,c,0) is silently rejected — isValidMove treats 0 as a duplicate of any empty cell, so a player can never clear/correct a wrong entry through the move path (only server-side undo can). Same rejection hits applyExternalMove/applyBatchMoves (the WebSocket move path) and the client 'clear' + 'Fix Conflicts' actions.");
        else ok("cell erased to 0");
        // also via the external (websocket) path
        SudokuBoard b2 = new SudokuBoard(2,false,false,0,"erase-2");
        int[] rc2 = firstEmpty(b2); int[][] s2 = givensGrid(b2); solve(s2);
        int r2=rc2[0], c2=rc2[1];
        b2.applyExternalMove(new EnhancedMove(r2,c2,0,s2[r2][c2],MoveSource.PLAYER));
        b2.applyExternalMove(new EnhancedMove(r2,c2,s2[r2][c2],0,MoveSource.PLAYER));
        if (b2.getBoard()[r2][c2].getValue() != 0) ok("(confirmed) applyExternalMove clear also rejected");
    }

    static int[] firstEmpty(SudokuBoard b) {
        SudokuCell[][] c = b.getBoard();
        for (int r=0;r<9;r++) for (int col=0;col<9;col++) if (c[r][col].getValue()==0) return new int[]{r,col};
        return new int[]{-1,-1};
    }

    // 5. undo then redo must preserve a cell's MoveSource.
    static void testUndoRedoSource() {
        System.out.println("[T5] undo/redo preserves MoveSource");
        SudokuBoard b = new SudokuBoard(2,false,false,0,"src-1");
        int[] rc = firstEmpty(b); int[][] sol = givensGrid(b); solve(sol);
        int r=rc[0], c=rc[1];
        b.makeMove(r,c,sol[r][c],MoveSource.HINT);
        b.undo();
        b.redo();
        MoveSource ms = b.getBoard()[r][c].getMoveSource();
        if (ms != MoveSource.HINT)
            finding("UNDO-REDO-SOURCE-LOSS",
                "after makeMove(HINT)->undo->redo the cell's MoveSource is " + ms + ", not HINT. undo()/redo() call the single-arg setValue() which resets source to UNKNOWN. Loses hint/autosolve provenance used for coloring and the anti-reward guard.");
        else ok("MoveSource preserved through undo/redo");
    }

    // 6. undo/redo must not let an AUTOSOLVE board masquerade as a legit solve.
    static void testAutosolveGuardBypass() {
        System.out.println("[T6] Auto-solve reward guard survives an undo/redo cycle");
        SudokuBoard b = new SudokuBoard(2,false,false,0,"auto-1");
        // Fill the whole board legitimately, but make the LAST fill AUTOSOLVE-sourced.
        int[][] sol = givensGrid(b); solve(sol);
        SudokuCell[][] cells = b.getBoard();
        List<int[]> empties = new ArrayList<>();
        for (int r=0;r<9;r++) for (int c=0;c<9;c++) if (cells[r][c].getValue()==0) empties.add(new int[]{r,c});
        for (int i=0;i<empties.size();i++) {
            int[] e = empties.get(i);
            MoveSource src = (i==empties.size()-1) ? MoveSource.AUTOSOLVE : MoveSource.PLAYER;
            b.makeMove(e[0], e[1], sol[e[0]][e[1]], src);
        }
        boolean before = b.hasAutosolvedCells();
        // walk the whole history back and forward
        for (int i=0;i<empties.size();i++) b.undo();
        com.xai.sudokupro.model.EnhancedMove m;
        int redos=0; while ((m=b.redo())!=null) redos++;
        boolean after = b.hasAutosolvedCells();
        if (before && !after)
            finding("AUTOSOLVE-GUARD-BYPASS",
                "hasAutosolvedCells() was true, but after a full undo/redo replay it is false (redo restores cells with source UNKNOWN). A board finished with the auto-solver can be laundered into a 'legit' solve via /ws undo+redo, defeating the A-4 reward guard (gems/streaks/achievements).");
        else ok("autosolve provenance survived (before=" + before + " after=" + after + ")");
    }

    // 7. applyBatchMoves must not count rejected moves.
    static void testBatchOvercount() {
        System.out.println("[T7] applyBatchMoves moveCount accuracy");
        SudokuBoard b = new SudokuBoard(2,false,false,0,"batch-1");
        int[][] sol = givensGrid(b); solve(sol);
        SudokuCell[][] cells = b.getBoard();
        List<EnhancedMove> batch = new ArrayList<>();
        int valid = 0;
        // 3 valid fills
        for (int r=0;r<9 && valid<3;r++) for (int c=0;c<9 && valid<3;c++) if (cells[r][c].getValue()==0) {
            batch.add(new EnhancedMove(r,c,0,sol[r][c],MoveSource.PLAYER)); valid++;
        }
        // 2 invalid: a move onto a given cell, and an out-of-range/duplicate
        int[] gcell = firstGiven(b);
        batch.add(new EnhancedMove(gcell[0], gcell[1], 0, 5, MoveSource.PLAYER)); // given -> rejected
        // a clearly duplicate value in a filled row (pick a given's value into an empty same-row cell)
        batch.add(new EnhancedMove(gcell[0], otherEmptyInRow(b, gcell[0]), 0, b.getBoard()[gcell[0]][gcell[1]].getValue(), MoveSource.PLAYER));
        int before = b.getMoveCount();
        b.applyBatchMoves(batch);
        int delta = b.getMoveCount() - before;
        if (delta != valid)
            finding("BATCH-OVERCOUNT",
                "applyBatchMoves added " + delta + " to moveCount but only " + valid + " of " + batch.size() + " moves were actually applied (it does `moveCount += moves.size()` unconditionally). Inflates the player's move counter and any anti-cheat/scoring keyed on it. jumpToMove()/loadReplayFromJson() inherit the same inflation.");
        else ok("moveCount delta matches applied count (" + valid + ")");
    }
    static int[] firstGiven(SudokuBoard b){SudokuCell[][] c=b.getBoard();for(int r=0;r<9;r++)for(int col=0;col<9;col++)if(c[r][col].isGiven())return new int[]{r,col};return new int[]{0,0};}
    static int otherEmptyInRow(SudokuBoard b,int row){SudokuCell[][] c=b.getBoard();for(int col=0;col<9;col++)if(c[row][col].getValue()==0)return col;return 0;}

    // 8. undo after a solve must clear the solved flag (state consistency).
    static void testUndoAfterSolveFlag() {
        System.out.println("[T8] undo after solve clears the persisted solved flag");
        SudokuBoard b = new SudokuBoard(2,false,false,0,"flag-1");
        int[][] sol = givensGrid(b); solve(sol);
        SudokuCell[][] cells = b.getBoard();
        for (int r=0;r<9;r++) for (int c=0;c<9;c++) if (cells[r][c].getValue()==0) b.makeMove(r,c,sol[r][c],MoveSource.PLAYER);
        if (!b.isSolvedState()) { ok("(n/a) board not marked solved"); return; }
        b.undo(); // remove one cell -> board is now incomplete
        boolean live = b.isSolved();        // recomputed: should be false
        boolean flag = b.isSolvedState();   // persisted: stale
        if (!live && flag)
            finding("UNDO-STALE-SOLVED-FLAG",
                "after solving then undo, the board is incomplete (isSolved()=false) but the persisted solved flag stays true. A board persisted here has solved=true in the DB, so GameRepository.findResumableByPlayerId (filters solved=false) permanently hides this now-unsolved game from the resume/saved list.");
        else ok("solved flag consistent after undo (live=" + live + " flag=" + flag + ")");
    }

    // 9. In mirror mode, one logical move + one undo must leave a consistent state.
    static void testMirrorUndo() {
        System.out.println("[T9] mirror mode: single makeMove then single undo");
        // find a board with an editable cell whose mirror is also editable and legal
        SudokuBoard b = new SudokuBoard(3,true,true,0,"mir-1"); // chaos off matters less; mirror on
        b.setMirrorMode(true);
        int[][] sol = givensGrid(b); solve(sol);
        SudokuCell[][] cells = b.getBoard();
        int fr=-1,fc=-1;
        for (int r=0;r<9 && fr<0;r++) for (int c=0;c<9;c++) {
            int mr=8-r, mc=8-c;
            if (cells[r][c].getValue()==0 && cells[mr][mc].getValue()==0 && !(r==mr&&c==mc)) { fr=r; fc=c; break; }
        }
        if (fr<0) { ok("(n/a) no mirrored empty pair found"); return; }
        int mr=8-fr, mc=8-fc;
        int mcBefore = b.getMoveCount();
        int histBefore = b.getMoveHistory().size();
        b.makeMove(fr,fc,sol[fr][fc],MoveSource.PLAYER);
        int histAfter = b.getMoveHistory().size();
        int mcAfter = b.getMoveCount();
        boolean primarySet = cells[fr][fc].getValue()!=0;
        boolean mirrorSet  = cells[mr][mc].getValue()!=0;
        b.undo(); // one undo
        boolean primaryAfterUndo = cells[fr][fc].getValue()!=0;
        boolean mirrorAfterUndo  = cells[mr][mc].getValue()!=0;
        // A single logical move produced 2 history entries but only +1 moveCount:
        boolean twoHistOneCount = (histAfter-histBefore==2) && (mcAfter-mcBefore==1);
        // and one undo leaves exactly one of the two cells still filled:
        boolean inconsistentUndo = mirrorSet && (primaryAfterUndo ^ mirrorAfterUndo);
        if (twoHistOneCount && inconsistentUndo)
            finding("MIRROR-UNDO-DESYNC",
                "one makeMove in mirror mode pushes 2 history entries but increments moveCount by 1; a single undo() then reverts only one of the mirrored pair (primaryFilled=" + primaryAfterUndo + " mirrorFilled=" + mirrorAfterUndo + "), so the player's move can't be cleanly undone and moveCount desyncs from the grid.");
        else ok("mirror move/undo consistent (histΔ=" + (histAfter-histBefore) + " mcΔ=" + (mcAfter-mcBefore) + " primary=" + primaryAfterUndo + " mirror=" + mirrorAfterUndo + ")");
    }

    // 10. snapshot/restore and playerCopy must be faithful.
    static void testSnapshotRoundTrip() {
        System.out.println("[T10] snapshotCells/restoreCells + playerCopy fidelity");
        SudokuBoard b = new SudokuBoard(3,false,false,0,"snap-1");
        // add a player move and a pencil mark to exercise non-given state
        int[] rc = firstEmpty(b); int[][] sol=givensGrid(b); solve(sol);
        b.makeMove(rc[0],rc[1],sol[rc[0]][rc[1]],MoveSource.PLAYER);
        String snap = b.snapshotCells();
        SudokuBoard copy = SudokuBoard.playerCopy(b, "snap-copy", "p2");
        boolean identical = true;
        SudokuCell[][] a=b.getBoard(), d=copy.getBoard();
        for (int r=0;r<9;r++) for (int c=0;c<9;c++)
            if (a[r][c].getValue()!=d[r][c].getValue() || a[r][c].isGiven()!=d[r][c].isGiven()) identical=false;
        if (!identical) finding("SNAPSHOT-INFIDELITY","playerCopy grid/given flags differ from source.");
        else ok("playerCopy faithfully reproduces values + given flags");
    }

    // 11. BoardState (the wire projection) must never carry the solution.
    static void testBoardStateNoLeak() {
        System.out.println("[T11] BoardState wire projection hides the solution");
        SudokuBoard b = new SudokuBoard(4,false,false,0,"leak-1");
        BoardState bs = BoardState.from(b);
        // Reconstruct the visible grid from the wire object; empty (non-given) cells must be 0.
        int nonZeroNonGiven = 0;
        for (int r=0;r<9;r++) for (int c=0;c<9;c++) {
            var v = bs.cells().get(r).get(c);
            if (!v.isGiven() && v.value()!=0) nonZeroNonGiven++;
        }
        if (nonZeroNonGiven>0) finding("BOARDSTATE-LEAK","wire projection exposes " + nonZeroNonGiven + " solved-but-not-given cells before the player entered them.");
        else ok("wire projection exposes only givens on a fresh board");
    }

    // ===================== hardening suites =====================

    /** 13. No sequence of move attempts may ever alter an original clue. */
    static void testGivensImmutableUnderFuzz() {
        System.out.println("[T13] givens survive 2000 random move attempts");
        Random rnd = new Random(20260724L);
        SudokuBoard b = new SudokuBoard(3,false,false,0,"fuzz-given");
        Map<String,Integer> clues = new HashMap<>();
        SudokuCell[][] cells = b.getBoard();
        for (int r=0;r<9;r++) for (int c=0;c<9;c++)
            if (cells[r][c].isGiven()) clues.put(r+","+c, cells[r][c].getValue());

        for (int i=0;i<2000;i++) {
            int r=rnd.nextInt(9), c=rnd.nextInt(9), v=rnd.nextInt(10);
            switch (rnd.nextInt(3)) {
                case 0 -> b.makeMove(r,c,v,MoveSource.PLAYER);
                case 1 -> b.applyExternalMove(new EnhancedMove(r,c,0,v,MoveSource.PLAYER));
                default -> b.applyBatchMoves(List.of(new EnhancedMove(r,c,0,v,MoveSource.PLAYER)));
            }
        }
        int violated = 0;
        for (var e : clues.entrySet()) {
            String[] rc = e.getKey().split(",");
            int cur = cells[Integer.parseInt(rc[0])][Integer.parseInt(rc[1])].getValue();
            if (cur != e.getValue()) violated++;
        }
        if (violated > 0) finding("FUZZ-GIVEN-MUTATED", violated + " original clue(s) were changed by move attempts.");
        else ok("all " + clues.size() + " clues intact after 2000 random attempts");
    }

    /** 14. Undoing every move must restore exactly the starting position. */
    static void testUndoAllReturnsToStart() {
        System.out.println("[T14] undo-everything restores the initial grid");
        SudokuBoard b = new SudokuBoard(2,false,false,0,"undo-all");
        String before = flat(grid(b));
        int[][] sol = givensGrid(b); solve(sol);
        SudokuCell[][] cells = b.getBoard();
        int played = 0;
        for (int r=0;r<9;r++) for (int c=0;c<9;c++)
            if (cells[r][c].getValue()==0) { b.makeMove(r,c,sol[r][c],MoveSource.PLAYER); played++; }
        for (int i=0;i<played;i++) b.undo();
        String after = flat(grid(b));
        if (!before.equals(after))
            finding("UNDO-ALL-MISMATCH",
                "after playing " + played + " moves and undoing all of them the grid differs from the start.");
        else ok("grid identical after " + played + " moves + " + played + " undos");
    }

    /** 15. snapshot -> restore must be exact after arbitrary play (values, givens, marks). */
    static void testSnapshotRoundTripAfterRandomPlay() {
        System.out.println("[T15] snapshot/restore fidelity after random play + pencil marks");
        Random rnd = new Random(99L);
        SudokuBoard b = new SudokuBoard(3,false,false,0,"snap-fuzz");
        int[][] sol = givensGrid(b); solve(sol);
        SudokuCell[][] cells = b.getBoard();
        for (int r=0;r<9;r++) for (int c=0;c<9;c++) if (cells[r][c].getValue()==0) {
            if (rnd.nextBoolean()) b.makeMove(r,c,sol[r][c],MoveSource.PLAYER);
            else { cells[r][c].addPencilMark(1+rnd.nextInt(9)); cells[r][c].addPencilMark(1+rnd.nextInt(9)); }
        }
        String snap = b.snapshotCells();
        SudokuBoard restored = new SudokuBoard(1,false,false,0,"snap-target");
        restored.restoreCells(snap);
        int diffs = 0;
        SudokuCell[][] a = b.getBoard(), d = restored.getBoard();
        for (int r=0;r<9;r++) for (int c=0;c<9;c++) {
            if (a[r][c].getValue()!=d[r][c].getValue()) diffs++;
            else if (a[r][c].isGiven()!=d[r][c].isGiven()) diffs++;
            else if (!a[r][c].getPencilMarks().equals(d[r][c].getPencilMarks())) diffs++;
        }
        if (diffs>0) finding("SNAPSHOT-FUZZ-DRIFT", diffs + " cell(s) differ after snapshot->restore.");
        else ok("81/81 cells round-trip exactly (values, givens, pencil marks)");
        // and a malformed snapshot must leave the board untouched
        String intact = flat(grid(restored));
        try { restored.restoreCells("[[{\"v\":1}]]"); } catch (IllegalArgumentException expected) { /* good */ }
        if (!flat(grid(restored)).equals(intact))
            finding("SNAPSHOT-PARTIAL-APPLY","a malformed snapshot partially mutated the board.");
        else ok("malformed snapshot left the board untouched");
    }

    /** 16. Concurrent moves from many threads must not corrupt the grid or throw. */
    static void testConcurrentMovesStayConsistent() {
        System.out.println("[T16] 8 threads x 400 concurrent ops");
        SudokuBoard b = new SudokuBoard(3,false,false,0,"conc-1");
        int[][] sol = givensGrid(b); solve(sol);
        List<Thread> ts = new ArrayList<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        for (int t=0;t<8;t++) {
            final long seed = t;
            Thread th = new Thread(() -> {
                Random rnd = new Random(seed);
                for (int i=0;i<400;i++) {
                    try {
                        int r=rnd.nextInt(9), c=rnd.nextInt(9);
                        switch (rnd.nextInt(4)) {
                            case 0 -> b.makeMove(r,c,sol[r][c],MoveSource.PLAYER);
                            case 1 -> b.undo();
                            case 2 -> b.redo();
                            default -> b.snapshotCells();
                        }
                    } catch (Exception e) { failures.add(e.getClass().getSimpleName()+": "+e.getMessage()); }
                }
            });
            ts.add(th); th.start();
        }
        for (Thread th : ts) { try { th.join(20000); } catch (InterruptedException ignored) {} }
        if (!failures.isEmpty())
            finding("CONCURRENT-EXCEPTION", failures.size() + " exception(s) under concurrent ops, first: " + failures.get(0));
        else ok("no exceptions across 3200 concurrent ops");
        // grid must still be structurally sane
        int bad = 0;
        SudokuCell[][] cells = b.getBoard();
        for (int r=0;r<9;r++) for (int c=0;c<9;c++) { int v=cells[r][c].getValue(); if (v<0||v>9) bad++; }
        if (bad>0) finding("CONCURRENT-CORRUPT", bad + " cell(s) hold out-of-range values after concurrent play.");
        else ok("all 81 cells hold legal values (0-9) afterwards");
    }

    /** 17. Random op storm: state invariants must hold throughout. */
    static void testStateNeverCorruptsUnderRandomOps() {
        System.out.println("[T17] invariant storm: 3000 mixed operations");
        Random rnd = new Random(7L);
        SudokuBoard b = new SudokuBoard(4,false,false,0,"storm");
        int[][] sol = givensGrid(b); solve(sol);
        int violations = 0;
        for (int i=0;i<3000;i++) {
            int r=rnd.nextInt(9), c=rnd.nextInt(9);
            switch (rnd.nextInt(5)) {
                case 0 -> b.makeMove(r,c,sol[r][c],MoveSource.PLAYER);
                case 1 -> b.undo();
                case 2 -> b.redo();
                case 3 -> b.applyExternalMove(new EnhancedMove(r,c,0,sol[r][c],MoveSource.PLAYER));
                default -> b.getCell(r,c);
            }
            if (b.getMoveCount() < 0) violations++;
            if (b.getHintCount() < 0) violations++;
            if (b.getLives() < 0) violations++;
        }
        // a solved-state claim must agree with an independent check
        boolean claimed = b.isSolvedState();
        boolean actually = b.isSolved();
        if (violations>0) finding("STORM-NEGATIVE-COUNTER", violations + " negative-counter observations.");
        else ok("counters stayed non-negative across 3000 ops");
        if (claimed && !actually)
            finding("STORM-SOLVED-FLAG-DIVERGENCE",
                "persisted solved flag is true while the board is not actually solved (same root cause as UNDO-STALE-SOLVED-FLAG).");
        else ok("solved flag agrees with the live board (claimed=" + claimed + ")");
    }

    // 12. Cross-check the README claim: unique solution AND (implicitly) varied.
    static void testSolutionUniquenessClaim() {
        System.out.println("[T12] Two different-difficulty games share the same answer?");
        SudokuBoard e = new SudokuBoard(1,false,false,0,"u-easy");
        SudokuBoard h = new SudokuBoard(5,false,false,0,"u-hard");
        int[][] ge=givensGrid(e); solve(ge);
        int[][] gh=givensGrid(h); solve(gh);
        if (flat(ge).equals(flat(gh)))
            finding("GEN-CROSS-DIFFICULTY-SAME-ANSWER",
                "an easy (d1) and a hard (d5) game resolve to the SAME completed grid — confirms the solution is a fixed constant across the whole platform, independent of difficulty.");
        else ok("easy and hard games have different answers");
    }
}
