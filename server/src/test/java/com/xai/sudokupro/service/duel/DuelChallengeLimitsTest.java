package com.xai.sudokupro.service.duel;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.model.SudokuGenerator;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.model.api.DuelInfo;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.service.duel.DuelStateStore.DuelRecord;
import com.xai.sudokupro.util.SecureRandomGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Challenge-lifecycle limits on the REAL {@link DuelService}: who may issue a challenge,
 * how many may be outstanding at once, who may accept or decline one, and what a second
 * acceptance does.
 *
 * <p>Defect classes this guards against:
 * <ul>
 *   <li><b>Self-dealing.</b> A duel with yourself is a rating/achievement faucet: the
 *       settlement credits a win and a loss to the same account, and the win counter feeds
 *       both the {@code DuelChampion} achievement and the ladder tie-break.</li>
 *   <li><b>Challenge spam / unbounded state.</b> Every challenge writes a duel record plus
 *       a member in BOTH players' per-player sets, and {@code GET /api/duel} does one store
 *       round trip per member. Without a cap on simultaneous unanswered challenges one
 *       player can make another player's own duel list arbitrarily expensive.</li>
 *   <li><b>Replayed state transitions.</b> {@code accept} generates a puzzle and adopts two
 *       live games. Accepting twice must not hand out a second pair of boards (the first
 *       pair is what the race is being run on, and the duel-id-keyed game ids collide), and
 *       a duel that was declined must not become acceptable again.</li>
 *   <li><b>Elo minting.</b> {@code recordResult} clamps the transfer to what the loser
 *       actually owns; these tests drive it through the PUBLIC {@code onGameEnded} entry
 *       point rather than by reflection, so they also cover the guards that decide whether
 *       settlement runs at all. A prior pass fixed the floor arithmetic — the tests below
 *       pin that the fix holds and that no other route to settlement bypasses it.</li>
 * </ul>
 *
 * <p>Redis is deliberately a mock that throws on every call, so {@link DuelStateStore}
 * runs its documented in-memory fallback. That is the single-replica code path and it needs
 * no infrastructure.
 */
class DuelChallengeLimitsTest {

    private final Map<String, User> users = new HashMap<>();

    private DuelStateStore duels;
    private DuelService service;
    private GameService gameService;

    @BeforeEach
    void setUp() {
        UserRepository repo = mock(UserRepository.class);
        lenient().when(repo.findByUsername(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(users.get(inv.<String>getArgument(0))));
        lenient().when(repo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });
        lenient().when(repo.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });

        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });

        duels = new DuelStateStore(downRedis);
        gameService = mock(GameService.class);
        service = new DuelService(
            gameService,
            new SudokuGenerator(new SecureRandomGenerator(new SimpleMeterRegistry())),
            duels, repo, mock(NotificationService.class));
    }

    /** Registers a real player row so the challenge existence guard is satisfied. */
    private User player(String name) {
        User u = new User(null, name);
        users.put(name, u);
        return u;
    }

    private int rating(String who) {
        return users.get(who).getDuelRating();
    }

    private long pendingCount(String playerId) {
        return service.duelsFor(playerId).stream()
            .filter(d -> "PENDING".equals(d.status())).count();
    }

    // =================================================================
    // who may issue a challenge
    // =================================================================

    @Test
    void youCannotDuelYourself() {
        player("solo");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.challenge("solo", "solo", 2));

        assertTrue(e.getMessage().toLowerCase().contains("yourself"), e.getMessage());
        assertEquals(0, service.duelsFor("solo").size(),
            "a refused self-challenge must not leave a duel record behind");
    }

    @Test
    void youCannotChallengeAPlayerWhoDoesNotExist() {
        player("alice");

        assertThrows(IllegalArgumentException.class,
            () -> service.challenge("alice", "nobody", 2));

        assertEquals(0, service.duelsFor("alice").size(),
            "a challenge to a nonexistent name must not write a record into the challenger's "
                + "own duel set either");
        assertEquals(0, service.duelsFor("nobody").size());
    }

    @Test
    void bothPlayersMustBeNamed() {
        player("alice");
        assertThrows(IllegalArgumentException.class, () -> service.challenge(null, "alice", 2));
        assertThrows(IllegalArgumentException.class, () -> service.challenge("  ", "alice", 2));
        assertThrows(IllegalArgumentException.class, () -> service.challenge("alice", "", 2));
    }

    // =================================================================
    // how many challenges may be outstanding
    // =================================================================

    /**
     * The cap is three simultaneous unanswered challenges to one opponent, so the FOURTH
     * is the first to be refused. Hand-derived from
     * {@code DuelService.MAX_OUTSTANDING_CHALLENGES == 3} — the numbers below are literal
     * on purpose, an off-by-one in the guard has to break this test.
     */
    @Test
    void theFourthSimultaneousChallengeToTheSameOpponentIsRefused() {
        player("alice");
        player("bob");

        assertNotNull(service.challenge("alice", "bob", 2));
        assertNotNull(service.challenge("alice", "bob", 2));
        assertNotNull(service.challenge("alice", "bob", 2));

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> service.challenge("alice", "bob", 2),
            "a fourth unanswered challenge to the same opponent must be refused");
        assertTrue(e.getMessage().contains("pending"), e.getMessage());

        assertEquals(3, pendingCount("bob"),
            "bob's duel list must hold exactly the three challenges that were allowed");
        assertEquals(3, pendingCount("alice"));
    }

    /**
     * The cap is per challenger→opponent PAIR, not per victim: a second, unrelated player
     * must not be locked out because someone else filled their quota. (Reproduction for the
     * over-broad variant of the guard, which counts every pending duel on the opponent.)
     */
    @Test
    void oneChallengersQuotaDoesNotBlockAnother() {
        player("alice");
        player("carol");
        player("bob");
        for (int i = 0; i < 3; i++) service.challenge("alice", "bob", 2);

        assertNotNull(service.challenge("carol", "bob", 2),
            "carol has issued no challenges of her own and must not be refused");
        assertEquals(4, pendingCount("bob"));
    }

    /** Declining is what frees a slot; without the status write the quota would be permanent. */
    @Test
    void decliningAChallengeFreesTheSlot() {
        player("alice");
        player("bob");
        String first = service.challenge("alice", "bob", 2);
        service.challenge("alice", "bob", 2);
        service.challenge("alice", "bob", 2);
        assertThrows(IllegalStateException.class, () -> service.challenge("alice", "bob", 2));

        service.decline(first, "bob");

        assertEquals(2, pendingCount("bob"), "the declined duel must leave the pending set");
        assertNotNull(service.challenge("alice", "bob", 2),
            "with a slot free the next challenge must be allowed");
        assertEquals(3, pendingCount("bob"));
    }

    // =================================================================
    // who may accept / decline, and how often
    // =================================================================

    @Test
    void theChallengerCannotAcceptTheirOwnChallenge() {
        player("alice");
        player("bob");
        String duelId = service.challenge("alice", "bob", 1);

        assertThrows(SecurityException.class, () -> service.accept(duelId, "alice"),
            "accepting your own challenge is a self-duel by the back door");

        verify(gameService, never()).adoptGame(any());
        assertEquals("PENDING", service.duelsFor("bob").get(0).status());
    }

    @Test
    void aStrangerCannotAcceptSomebodyElsesDuel() {
        player("alice");
        player("bob");
        player("mallory");
        String duelId = service.challenge("alice", "bob", 1);

        assertThrows(SecurityException.class, () -> service.accept(duelId, "mallory"));

        verify(gameService, never()).adoptGame(any());
        assertEquals("PENDING", service.duelsFor("bob").get(0).status());
    }

    @Test
    void aStrangerCannotDeclineSomebodyElsesDuel() {
        player("alice");
        player("bob");
        player("mallory");
        String duelId = service.challenge("alice", "bob", 1);

        assertThrows(SecurityException.class, () -> service.decline(duelId, "mallory"));

        assertEquals("PENDING", service.duelsFor("bob").get(0).status(),
            "a stranger must not be able to kill a duel they are not part of");
    }

    /**
     * A second acceptance must not generate a second puzzle: both players are already
     * racing on the first pair of boards, and the board ids are derived from the duel id,
     * so a fresh pair would silently replace the live games under them.
     */
    @Test
    void aDuelCannotBeAcceptedTwice() {
        player("alice");
        player("bob");
        String duelId = service.challenge("alice", "bob", 1);

        SudokuBoard mine = service.accept(duelId, "bob");
        assertNotNull(mine);

        assertThrows(IllegalStateException.class, () -> service.accept(duelId, "bob"));

        verify(gameService, times(2)).adoptGame(any());  // exactly one pair of boards, ever
        assertEquals("ACTIVE", service.duelsFor("bob").get(0).status());
    }

    @Test
    void aDeclinedDuelCannotLaterBeAcceptedOrDeclinedAgain() {
        player("alice");
        player("bob");
        String duelId = service.challenge("alice", "bob", 1);
        service.decline(duelId, "bob");

        assertThrows(IllegalStateException.class, () -> service.accept(duelId, "bob"));
        assertThrows(IllegalStateException.class, () -> service.decline(duelId, "bob"));

        verify(gameService, never()).adoptGame(any());
        assertEquals("DECLINED", service.duelsFor("bob").get(0).status());
    }

    @Test
    void anUnknownDuelIdIsRejected() {
        player("bob");
        assertThrows(IllegalArgumentException.class, () -> service.accept("no-such-duel", "bob"));
        assertThrows(IllegalArgumentException.class, () -> service.decline("no-such-duel", "bob"));
        assertThrows(IllegalArgumentException.class, () -> service.rematch("no-such-duel", "bob"));
    }

    /** Both duellists get a board, and both boards carry the same puzzle. */
    @Test
    void acceptingHandsBothPlayersTheirOwnCopyOfOnePuzzle() {
        player("alice");
        player("bob");
        String duelId = service.challenge("alice", "bob", 1);

        SudokuBoard bobsBoard = service.accept(duelId, "bob");

        assertEquals("bob", bobsBoard.getPlayerId());
        assertEquals("duel-" + duelId + ":bob", bobsBoard.getGameId());
        var adopted = org.mockito.ArgumentCaptor.forClass(SudokuBoard.class);
        verify(gameService, times(2)).adoptGame(adopted.capture());
        List<SudokuBoard> boards = adopted.getAllValues();
        assertEquals("duel-" + duelId + ":alice", boards.get(0).getGameId());
        assertEquals("duel-" + duelId + ":bob", boards.get(1).getGameId());
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                assertEquals(boards.get(0).getBoard()[r][c].getValue(),
                    boards.get(1).getBoard()[r][c].getValue(),
                    "both duellists must race on identical grids (" + r + "," + c + ")");
    }

    // =================================================================
    // settlement: rating may move, never appear
    // =================================================================

    /**
     * The Elo floor fix, driven through the real end-of-game entry point rather than by
     * reflection on {@code recordResult}.
     *
     * <p>Hand-derived at K=32 for winner 195 / loser 5: expected score
     * {@code 1/(1+10^((5-195)/400)) = 0.7491}, so the raw delta is
     * {@code round(32 * 0.2509) = 8}. The loser only owns 5, so a conserving exchange can
     * move at most 5: 195→200 and 5→0. Before the fix the winner took the full 8 while the
     * loser's setter clamped at zero, minting three points per duel.
     */
    @Test
    void winningAtTheRatingFloorMovesOnlyWhatTheLoserOwns() {
        player("alice").setDuelRating(195);
        player("bob").setDuelRating(5);
        duels.save(new DuelRecord("d1", "alice", "bob", "ACTIVE", null, 1));

        service.onGameEnded(solvedBoard("duel-d1:alice", "alice"), "alice");

        assertEquals(200, rating("alice"));
        assertEquals(0, rating("bob"));
        assertEquals(200, rating("alice") + rating("bob"), "a duel moves rating, it never mints it");
        assertEquals(1, users.get("alice").getDuelWins());
        assertEquals(1, users.get("bob").getDuelLosses());
    }

    /** The ordinary case through the public path: K=32, even ratings, 16 points move. */
    @Test
    void winningAnEvenDuelTransfersSixteen() {
        player("alice").setDuelRating(1000);
        player("bob").setDuelRating(1000);
        duels.save(new DuelRecord("d2", "alice", "bob", "ACTIVE", null, 1));

        service.onGameEnded(solvedBoard("duel-d2:alice", "alice"), "alice");

        assertEquals(1016, rating("alice"), "an even match at K=32 transfers 16");
        assertEquals(984, rating("bob"));
        assertEquals("FINISHED", service.duelsFor("bob").get(0).status());
        assertEquals("alice", service.duelsFor("bob").get(0).winner());
    }

    /**
     * Replay guard #1 — the duel STATUS. The slower player finishing after the duel is over
     * must settle nothing; otherwise their own defeat is paid back out as a second transfer.
     * The win claim is deliberately left untaken here so the status check is the only thing
     * standing in the way.
     */
    @Test
    void aDuelThatIsAlreadyFinishedCannotSettleAgain() {
        player("alice").setDuelRating(1016);
        player("bob").setDuelRating(984);
        duels.save(new DuelRecord("d2", "alice", "bob", "FINISHED", "alice", 1));

        service.onGameEnded(solvedBoard("duel-d2:bob", "bob"), "bob");

        assertEquals(1016, rating("alice"), "a finished duel must not settle a second time");
        assertEquals(984, rating("bob"));
        assertEquals(0, users.get("bob").getDuelWins());
    }

    /**
     * Replay guard #2 — the atomic win CLAIM. Two replicas can both see an ACTIVE duel;
     * only the first claimant settles. Here the claim is taken for alice up front and the
     * record deliberately left ACTIVE, so the SETNX claim is the only guard in play.
     */
    @Test
    void aWinAlreadyClaimedByTheOtherPlayerIsNotSettledAgain() {
        player("alice").setDuelRating(1000);
        player("bob").setDuelRating(1000);
        duels.save(new DuelRecord("d8", "alice", "bob", "ACTIVE", null, 1));
        assertTrue(duels.claimWin("d8", "alice"), "test setup: alice claims first");

        service.onGameEnded(solvedBoard("duel-d8:bob", "bob"), "bob");

        assertEquals(1000, rating("alice"));
        assertEquals(1000, rating("bob"), "the losing claimant must not be paid");
        assertEquals(0, users.get("bob").getDuelWins());
    }

    /**
     * A board whose id names a DIFFERENT player must not settle for whoever ended it.
     * Without the {@code gameId.equals(duelGameId(duelId, playerId))} guard the loser is
     * computed as "whichever duellist is not the caller", so an outsider ending alice's
     * duel board would be credited the win — rating created for an account that was never
     * in the duel.
     */
    @Test
    void endingSomebodyElsesDuelBoardWinsNothing() {
        player("alice").setDuelRating(1000);
        player("bob").setDuelRating(1000);
        player("mallory").setDuelRating(1000);
        duels.save(new DuelRecord("d3", "alice", "bob", "ACTIVE", null, 1));

        service.onGameEnded(solvedBoard("duel-d3:alice", "alice"), "mallory");

        assertEquals(1000, rating("alice"));
        assertEquals(1000, rating("bob"));
        assertEquals(1000, rating("mallory"), "an outsider must not be credited a duel win");
        assertEquals(0, users.get("mallory").getDuelWins());
        assertEquals("ACTIVE", service.duelsFor("alice").get(0).status());
    }

    /** An unsolved board settles nothing, however it reached the listener. */
    @Test
    void anUnsolvedDuelBoardSettlesNothing() {
        player("alice").setDuelRating(1000);
        player("bob").setDuelRating(1000);
        duels.save(new DuelRecord("d4", "alice", "bob", "ACTIVE", null, 1));
        SudokuBoard unfinished = new SudokuBoard(1, false, false, 0, "duel-d4:alice");
        unfinished.setPlayerId("alice");

        service.onGameEnded(unfinished, "alice");

        assertEquals(1000, rating("alice"));
        assertEquals(1000, rating("bob"));
        assertEquals("ACTIVE", service.duelsFor("alice").get(0).status());
    }

    /** A duel nobody accepted cannot be won by solving a board named after it. */
    @Test
    void aPendingDuelCannotBeWon() {
        player("alice").setDuelRating(1000);
        player("bob").setDuelRating(1000);
        duels.save(new DuelRecord("d5", "alice", "bob", "PENDING", null, 1));

        service.onGameEnded(solvedBoard("duel-d5:alice", "alice"), "alice");

        assertEquals(1000, rating("alice"));
        assertEquals(1000, rating("bob"));
        assertEquals("PENDING", service.duelsFor("bob").get(0).status());
    }

    // =================================================================
    // expiry
    // =================================================================

    /**
     * How long an unanswered challenge lives.
     *
     * <p>There is no application-level expiry transition — nothing ever moves a PENDING
     * duel to EXPIRED — so the only thing that ever retires a challenge is the store's key
     * TTL, and it has to cover the record AND both per-player index sets. If the sets
     * outlived the record, {@code duelsFor} would keep paying a round trip per dangling
     * member; if the record outlived the sets, a challenge would become invisible but still
     * acceptable by id. Redis is UP for this test — the in-memory fallback has no TTL at
     * all, which is why the degraded path is explicitly single-replica and short-lived.
     */
    @Test
    void duelRecordsAndBothPlayerIndexesExpireAfterTwentyFourHours() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
            mock(org.springframework.data.redis.core.ValueOperations.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.SetOperations<String, String> setOps =
            mock(org.springframework.data.redis.core.SetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);

        new DuelStateStore(redis).save(new DuelRecord("d9", "alice", "bob", "PENDING", null, 1));

        java.time.Duration day = java.time.Duration.ofHours(24);
        verify(valueOps).set(eq("sudokupro:duel:d9"), anyString(), eq(day));
        verify(redis).expire("sudokupro:duel:player:alice", day);
        verify(redis).expire("sudokupro:duel:player:bob", day);
    }

    // =================================================================
    // rematch
    // =================================================================

    @Test
    void onlyAFinishedDuelCanBeRematchedAndOnlyByItsParticipants() {
        player("alice");
        player("bob");
        player("mallory");
        duels.save(new DuelRecord("d6", "alice", "bob", "ACTIVE", null, 3));

        assertThrows(IllegalStateException.class, () -> service.rematch("d6", "alice"),
            "an in-progress duel is not rematchable");
        assertThrows(SecurityException.class, () -> service.rematch("d6", "mallory"));

        duels.save(new DuelRecord("d6", "alice", "bob", "FINISHED", "bob", 3));
        String rematchId = service.rematch("d6", "bob");

        DuelInfo fresh = service.duelsFor("alice").stream()
            .filter(d -> d.duelId().equals(rematchId)).findFirst().orElseThrow();
        assertEquals("bob", fresh.challenger(), "the rematch is issued BY the requester");
        assertEquals("alice", fresh.opponent());
        assertEquals("PENDING", fresh.status());
    }

    /** A rematch is an ordinary challenge and is subject to the same outstanding cap. */
    @Test
    void rematchesAreSubjectToTheChallengeCap() {
        player("alice");
        player("bob");
        duels.save(new DuelRecord("d7", "alice", "bob", "FINISHED", "alice", 2));
        for (int i = 0; i < 3; i++) service.challenge("alice", "bob", 2);

        assertThrows(IllegalStateException.class, () -> service.rematch("d7", "alice"));
        assertEquals(3, pendingCount("bob"));
    }

    // =================================================================
    // helpers — test scaffolding only; no production logic is reproduced here
    // =================================================================

    /** A solved board registered under {@code gameId} and owned by {@code owner}. */
    private static SudokuBoard solvedBoard(String gameId, String owner) {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, gameId);
        board.setPlayerId(owner);
        int[][] grid = new int[9][9];
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                grid[r][c] = board.getBoard()[r][c].getValue();
        assertTrue(fill(grid, 0), "test setup: the generated puzzle must be solvable");
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                if (board.getBoard()[r][c].getValue() == 0)
                    board.makeMove(r, c, grid[r][c], SudokuCell.MoveSource.PLAYER);
        assertTrue(board.isSolved(), "test setup: the board must be solved");
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

    private static boolean legal(int[][] g, int r, int c, int v) {
        for (int i = 0; i < 9; i++) if (g[r][i] == v || g[i][c] == v) return false;
        int br = (r / 3) * 3, bc = (c / 3) * 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (g[br + i][bc + j] == v) return false;
        return true;
    }
}
