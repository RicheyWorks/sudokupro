package com.xai.sudokupro.service.duel;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuGenerator;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.AISolverService;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.util.SecureRandomGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DuelServiceTest {

    @Mock private GameService gameService;
    @Mock private NotificationService notificationService;

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private UserRepository userRepository;
    private DuelStateStore store;
    private DuelService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        lenient().when(userRepository.findByUsername(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(users.get(inv.<String>getArgument(0))));
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });

        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        store = new DuelStateStore(downRedis);

        service = new DuelService(gameService,
            new SudokuGenerator(new SecureRandomGenerator(new SimpleMeterRegistry())),
            store, userRepository, notificationService);
    }

    @Test
    void challengeCreatesPendingDuelAndNotifiesOpponent() {
        String duelId = service.challenge("richmond", "rival", 2);

        assertNotNull(store.find(duelId));
        assertEquals("PENDING", store.find(duelId).status());
        verify(notificationService).sendTypedNotification(eq("rival"), eq("DUEL"), contains(duelId));
    }

    @Test
    void selfChallengeIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> service.challenge("richmond", "richmond", 2));
    }

    @Test
    void acceptStampsIdenticalBoardsForBothPlayers() {
        String duelId = service.challenge("richmond", "rival", 2);

        ArgumentCaptor<SudokuBoard> adopted = ArgumentCaptor.forClass(SudokuBoard.class);
        when(gameService.adoptGame(adopted.capture())).thenAnswer(inv -> inv.getArgument(0));

        SudokuBoard mine = service.accept(duelId, "rival");

        assertEquals("ACTIVE", store.find(duelId).status());
        assertEquals(2, adopted.getAllValues().size(), "both players must get a board");
        SudokuBoard a = adopted.getAllValues().get(0);
        SudokuBoard b = adopted.getAllValues().get(1);
        assertNotEquals(a.getGameId(), b.getGameId(), "each player owns their copy");
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++)
                assertEquals(a.getBoard()[r][c].getValue(), b.getBoard()[r][c].getValue(),
                    "both copies must be the identical puzzle");
        assertEquals("rival", mine.getPlayerId(), "accept returns the acceptor's board");
    }

    @Test
    void onlyTheChallengedPlayerCanAcceptOrDecline() {
        String duelId = service.challenge("richmond", "rival", 2);

        assertThrows(SecurityException.class, () -> service.accept(duelId, "eavesdropper"));
        assertThrows(SecurityException.class, () -> service.decline(duelId, "richmond"),
            "even the challenger cannot decline on the opponent's behalf");

        service.decline(duelId, "rival");
        assertEquals("DECLINED", store.find(duelId).status());
        assertThrows(IllegalStateException.class, () -> service.accept(duelId, "rival"),
            "declined duels cannot be accepted afterwards");
    }

    @Test
    void firstSolveWinsAndUpdatesBothRecords() {
        String duelId = activeDuel();

        SudokuBoard winning = solvedBoard(DuelService.duelGameId(duelId, "rival"), "rival");
        service.onGameEnded(winning, "rival");

        var duel = store.find(duelId);
        assertEquals("FINISHED", duel.status());
        assertEquals("rival", duel.winner());
        assertEquals(1, users.get("rival").getDuelWins());
        assertEquals(1, users.get("richmond").getDuelLosses());
        verify(notificationService).sendTypedNotification(eq("rival"), eq("DUEL"), contains("WON"));
        verify(notificationService).sendTypedNotification(eq("richmond"), eq("DUEL"), contains("lost"));
    }

    @Test
    void secondSolverGetsNothing() {
        String duelId = activeDuel();

        service.onGameEnded(solvedBoard(DuelService.duelGameId(duelId, "rival"), "rival"), "rival");
        service.onGameEnded(solvedBoard(DuelService.duelGameId(duelId, "richmond"), "richmond"), "richmond");

        assertEquals("rival", store.find(duelId).winner(), "first claim stands");
        assertEquals(0, users.get("richmond").getDuelWins(), "late solver must not also win");
        assertEquals(0, users.get("rival").getDuelLosses());
    }

    @Test
    void nonDuelAndUnsolvedGamesAreIgnored() {
        String duelId = activeDuel();

        SudokuBoard ordinary = solvedBoard("just-a-game", "rival");
        service.onGameEnded(ordinary, "rival");

        SudokuBoard unsolved = new SudokuBoard(1, false, false, 0,
            DuelService.duelGameId(duelId, "rival"));
        unsolved.setPlayerId("rival");
        service.onGameEnded(unsolved, "rival");

        assertEquals("ACTIVE", store.find(duelId).status(), "no winner without a solved duel board");
    }

    @Test
    void winnerGainsRatingLoserLosesIt() {
        String duelId = activeDuel();

        service.onGameEnded(solvedBoard(DuelService.duelGameId(duelId, "rival"), "rival"), "rival");

        // Equal 1000-ratings: expected score 0.5, K=32 → ±16
        assertEquals(1016, users.get("rival").getDuelRating());
        assertEquals(984, users.get("richmond").getDuelRating());
    }

    @Test
    void rematchSwapsNothingButRequiresAFinishedDuel() {
        String duelId = activeDuel();
        assertThrows(IllegalStateException.class, () -> service.rematch(duelId, "richmond"),
            "active duels cannot be rematched");

        service.onGameEnded(solvedBoard(DuelService.duelGameId(duelId, "rival"), "rival"), "rival");

        assertThrows(SecurityException.class, () -> service.rematch(duelId, "bystander"));
        String newId = service.rematch(duelId, "richmond");
        var fresh = store.find(newId);
        assertEquals("PENDING", fresh.status());
        assertEquals("richmond", fresh.challenger());
        assertEquals("rival", fresh.opponent());
        assertEquals(1, fresh.difficulty(), "rematch keeps the original difficulty");
    }

    // ---- helpers ----

    private String activeDuel() {
        String duelId = service.challenge("richmond", "rival", 1);
        lenient().when(gameService.adoptGame(any())).thenAnswer(inv -> inv.getArgument(0));
        service.accept(duelId, "rival");
        clearInvocations(notificationService);
        return duelId;
    }

    private SudokuBoard solvedBoard(String gameId, String playerId) {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, gameId);
        board.setPlayerId(playerId);
        new AISolverService(new SecureRandomGenerator(new SimpleMeterRegistry())).solveSudoku(board);
        assertTrue(board.isSolved(), "test setup: board must be solved");
        return board;
    }
}
