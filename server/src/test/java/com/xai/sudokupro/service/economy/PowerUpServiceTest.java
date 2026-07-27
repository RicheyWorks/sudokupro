package com.xai.sudokupro.service.economy;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.AISolverService;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.util.SecureRandomGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PowerUpServiceTest {

    @Mock private GameService gameService;
    @Mock private com.xai.sudokupro.service.duel.DuelStateStore duels;

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private PowerUpService service;

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
        // EconomyService.walletFor provisions with saveAndFlush so that a
        // users.username unique-constraint violation surfaces inside its own catch.
        lenient().when(repo.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });
        // buy() now charges through the same atomic conditional UPDATE a hint uses, instead
        // of read-modify-write on the whole row (which let two concurrent buys both see the
        // same balance, and reverted any concurrent hint charge wholesale).
        lenient().when(repo.deductGemsIfAffordable(anyString(), anyInt())).thenAnswer(inv -> {
            synchronized (users) {
                User u = users.get(inv.<String>getArgument(0));
                int cost = inv.getArgument(1);
                if (u == null || u.getGems() < cost) return 0;
                u.setGems(u.getGems() - cost);
                return 1;
            }
        });
        // 100 starting gems so purchases fit
        service = new PowerUpService(new EconomyService(repo, 5, 100, 5), repo, gameService, duels);
    }

    @Test
    void buyingChargesGemsAndStacksInventory() {
        assertEquals(1, service.buy("richmond", "EXTRA_LIFE"));
        assertEquals(2, service.buy("richmond", "EXTRA_LIFE"));
        assertEquals(100 - 30, users.get("richmond").getGems());
        assertThrows(IllegalArgumentException.class, () -> service.buy("richmond", "MEGA_NUKE"));
    }

    @Test
    void buyingBeyondTheWalletFails() {
        users.put("poor", new User(null, "poor")); // 0 gems
        assertThrows(InsufficientGemsException.class, () -> service.buy("poor", "FREEZE"));
    }

    /**
     * Board effects are delegated to GameService, which is the only place that holds the
     * game lock and writes through to Redis and the database. What this class still owns
     * is the inventory: spend on success, and — critically — do NOT spend when the board
     * operation refuses. The board-mutation and persistence assertions live in
     * {@code GameServicePowerUpEffectTest}, next to the code that performs them.
     */
    @Test
    void extraLifeGoesThroughGameServiceAndOnlyForYourOwnGame() {
        service.buy("richmond", "EXTRA_LIFE");

        service.use("richmond", "EXTRA_LIFE", "g-mine", null);
        verify(gameService).grantExtraLife("g-mine", "richmond");
        assertEquals(0, service.inventory("richmond").getOrDefault("EXTRA_LIFE", 0));

        // Someone else's board is off limits — and the unit is NOT consumed.
        doThrow(new SecurityException("Game g-theirs does not belong to player richmond"))
            .when(gameService).grantExtraLife("g-theirs", "richmond");
        service.buy("richmond", "EXTRA_LIFE");
        assertThrows(SecurityException.class,
            () -> service.use("richmond", "EXTRA_LIFE", "g-theirs", null));
        assertEquals(1, service.inventory("richmond").get("EXTRA_LIFE"),
            "a refused power-up must not be consumed");
    }

    @Test
    void revealCellGoesThroughGameService() {
        service.buy("richmond", "REVEAL_CELL");

        service.use("richmond", "REVEAL_CELL", "g-solve", null);

        verify(gameService).revealCell("g-solve", "richmond");
        assertEquals(0, service.inventory("richmond").getOrDefault("REVEAL_CELL", 0));
    }

    /** A reveal with no derivable cell costs nothing — the throw precedes the decrement. */
    @Test
    void anImpossibleRevealDoesNotConsumeTheUnit() {
        doThrow(new IllegalStateException("No empty cell to reveal"))
            .when(gameService).revealCell("g-full", "richmond");
        service.buy("richmond", "REVEAL_CELL");

        assertThrows(IllegalStateException.class,
            () -> service.use("richmond", "REVEAL_CELL", "g-full", null));

        assertEquals(1, service.inventory("richmond").get("REVEAL_CELL"));
    }

    /** Board-effect power-ups still require a gameId before anything else happens. */
    @Test
    void boardEffectsNeedAGameId() {
        service.buy("richmond", "REVEAL_CELL");
        assertThrows(IllegalArgumentException.class,
            () -> service.use("richmond", "REVEAL_CELL", "  ", null));
        assertEquals(1, service.inventory("richmond").get("REVEAL_CELL"));
        verify(gameService, never()).revealCell(anyString(), anyString());
    }

    @Test
    void freezeLocksTheTargetAndNeedsOne() {
        when(duels.hasActiveDuelBetween("richmond", "ada")).thenReturn(true);
        service.buy("richmond", "FREEZE");
        service.use("richmond", "FREEZE", null, "ada");
        verify(gameService).lockPlayerInput("ada", 10_000);

        service.buy("richmond", "FREEZE");
        assertThrows(IllegalArgumentException.class,
            () -> service.use("richmond", "FREEZE", null, "richmond"),
            "self-freeze is nonsense");
    }

    /**
     * Regression: FREEZE accepted ANY username. It checked only "not blank, not me", so
     * an attacker could read a name off the public leaderboard and lock that player's
     * input for ten seconds — with no duel between them and no relationship at all. The
     * class javadoc says FREEZE "locks a duel opponent's input", and its two siblings both
     * go through requireOwnGame; this one had nothing. It is silent to the victim too:
     * GameService.applyMove drops a locked player's move with a log line and no error,
     * while the WebSocket layer has already broadcast the move envelope, so their client
     * shows a move the authoritative board never recorded. Verified live before the fix.
     */
    @Test
    void freezeCannotTargetSomeoneYouAreNotDuelling() {
        when(duels.hasActiveDuelBetween("richmond", "stranger")).thenReturn(false);
        service.buy("richmond", "FREEZE");

        assertThrows(SecurityException.class,
            () -> service.use("richmond", "FREEZE", null, "stranger"));

        verify(gameService, never()).lockPlayerInput(eq("stranger"), anyLong());
        assertEquals(1, service.inventory("richmond").get("FREEZE"),
            "a refused FREEZE must not consume the item");
    }

    @Test
    void usingWhatYouDontHoldFails() {
        assertThrows(IllegalStateException.class,
            () -> service.use("richmond", "FREEZE", null, "ada"));
    }

    private static long countEmpty(SudokuBoard b) {
        long n = 0;
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++)
            if (b.getBoard()[r][c].getValue() == 0) n++;
        return n;
    }
}
