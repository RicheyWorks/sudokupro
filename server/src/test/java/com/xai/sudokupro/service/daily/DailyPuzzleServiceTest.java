package com.xai.sudokupro.service.daily;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuGenerator;
import com.xai.sudokupro.model.api.DailyStatus;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.service.GameLockManager;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.util.SecureRandomGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailyPuzzleServiceTest {

    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC);
    private static final String TODAY = "2026-07-16";

    @Mock private GameService gameService;
    @Mock private NotificationService notificationService;

    private final Map<String, SudokuBoard> savedRows = new ConcurrentHashMap<>();
    private GameRepository gameRepository;
    private DailyStateStore dailyState;
    private DailyPuzzleService service;

    @BeforeEach
    void setUp() {
        // Map-backed repository fake: findByGameId reads what save() wrote, which
        // is exactly the coordination the template creation path depends on.
        gameRepository = mock(GameRepository.class);
        lenient().when(gameRepository.save(any())).thenAnswer(inv -> {
            SudokuBoard b = inv.getArgument(0);
            savedRows.put(b.getGameId(), b);
            return b;
        });
        lenient().when(gameRepository.findByGameId(anyString()))
            .thenAnswer(inv -> savedRows.get(inv.<String>getArgument(0)));

        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        dailyState = new DailyStateStore(downRedis);

        SudokuGenerator generator = new SudokuGenerator(
            new SecureRandomGenerator(new SimpleMeterRegistry()));

        service = new DailyPuzzleService(gameService, gameRepository,
            new GameLockManager(downRedis), generator, dailyState,
            notificationService, FIXED_CLOCK);
    }

    @Test
    void joinCreatesTheTemplateOnceAndStampsPerPlayerCopies() {
        when(gameService.getGame(anyString()))
            .thenThrow(new IllegalArgumentException("not found"));
        when(gameService.adoptGame(any())).thenAnswer(inv -> inv.getArgument(0));

        SudokuBoard alice = service.joinDaily("alice");
        SudokuBoard bob = service.joinDaily("bob");

        // One persisted template, addressed by date
        SudokuBoard template = savedRows.get("daily-" + TODAY);
        assertNotNull(template, "template must be persisted under daily-<date>");
        assertEquals("__daily__", template.getPlayerId());

        // Copies carry their own identity but the SAME puzzle
        assertEquals("daily-" + TODAY + ":alice", alice.getGameId());
        assertEquals("bob", bob.getPlayerId());
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) {
                assertEquals(template.getBoard()[r][c].getValue(), alice.getBoard()[r][c].getValue());
                assertEquals(alice.getBoard()[r][c].getValue(), bob.getBoard()[r][c].getValue(),
                    "every player must receive the same daily puzzle");
            }
        verify(gameService, times(2)).adoptGame(any());
    }

    @Test
    void joinIsIdempotentWhenAGameAlreadyExists() {
        SudokuBoard existing = new SudokuBoard(2, false, false, 0, "daily-" + TODAY + ":richmond");
        when(gameService.getGame("daily-" + TODAY + ":richmond")).thenReturn(existing);

        assertSame(existing, service.joinDaily("richmond"));
        verify(gameService, never()).adoptGame(any());
    }

    @Test
    void solvedDailyGameAdvancesStreakAndNotifies() {
        SudokuBoard board = solvedDailyBoardFor("richmond");

        service.onGameEnded(board, "richmond");

        assertTrue(dailyState.isCompleted(service.today(), "richmond"));
        assertEquals(1, dailyState.getStreak("richmond", service.today()));
        verify(notificationService).sendTypedNotification(eq("richmond"), eq("DAILY"), anyString());
    }

    @Test
    void repeatSolveDoesNotDoubleCountOrRenotify() {
        SudokuBoard board = solvedDailyBoardFor("richmond");

        service.onGameEnded(board, "richmond");
        service.onGameEnded(board, "richmond");

        assertEquals(1, dailyState.getStreak("richmond", service.today()));
        verify(notificationService, times(1)).sendTypedNotification(anyString(), anyString(), anyString());
    }

    @Test
    void nonDailyAndUnsolvedGamesAreIgnored() {
        SudokuBoard ordinary = new SudokuBoard(1, false, false, 0, "just-a-game");
        service.onGameEnded(ordinary, "richmond");

        SudokuBoard unsolvedDaily = new SudokuBoard(1, false, false, 0, "daily-" + TODAY + ":richmond");
        service.onGameEnded(unsolvedDaily, "richmond"); // isSolved() == false

        assertFalse(dailyState.isCompleted(service.today(), "richmond"));
        verify(notificationService, never()).sendTypedNotification(anyString(), anyString(), anyString());
    }

    @Test
    void statusReflectsJoinAndCompletion() {
        DailyStatus before = service.status("richmond");
        assertEquals(TODAY, before.date());
        assertFalse(before.joined());
        assertFalse(before.completed());
        assertEquals(0, before.streakDays());

        savedRows.put("daily-" + TODAY + ":richmond",
            new SudokuBoard(2, false, false, 0, "daily-" + TODAY + ":richmond"));
        dailyState.recordCompletion(service.today(), "richmond", 240);

        DailyStatus after = service.status("richmond");
        assertTrue(after.joined());
        assertTrue(after.completed());
        assertEquals(1, after.streakDays());
    }

    @Test
    void archivedDailiesArePlayableButEarnNoStreak() {
        when(gameService.getGame(anyString())).thenThrow(new IllegalArgumentException("not found"));
        when(gameService.adoptGame(any())).thenAnswer(inv -> inv.getArgument(0));

        // Yesterday's template exists (as if created a day ago)
        SudokuBoard yesterdayTemplate = new SudokuBoard(2, false, false, 0, "daily-2026-07-15");
        yesterdayTemplate.setPlayerId("__daily__");
        savedRows.put("daily-2026-07-15", yesterdayTemplate);

        SudokuBoard archive = service.joinArchive("richmond", java.time.LocalDate.of(2026, 7, 15));
        assertEquals("daily-2026-07-15:archive:richmond", archive.getGameId());

        // Solving it must NOT touch today's streak/completion
        com.xai.sudokupro.service.AISolverService solver = new com.xai.sudokupro.service.AISolverService(
            new com.xai.sudokupro.util.SecureRandomGenerator(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        solver.solveSudoku(archive);
        service.onGameEnded(archive, "richmond");
        assertFalse(dailyState.isCompleted(service.today(), "richmond"));
        assertEquals(0, dailyState.getStreak("richmond", service.today()));

        // Future dates and missing templates are refused
        assertThrows(IllegalArgumentException.class,
            () -> service.joinArchive("richmond", java.time.LocalDate.of(2026, 7, 17)));
        assertThrows(IllegalArgumentException.class,
            () -> service.joinArchive("richmond", java.time.LocalDate.of(2026, 7, 1)));
    }

    @Test
    void archiveDatesListsTemplatesNewestFirst() {
        SudokuBoard t = new SudokuBoard(2, false, false, 0, "daily-2026-07-15");
        t.setPlayerId("__daily__");
        savedRows.put("daily-2026-07-15", t);
        when(gameRepository.findByPlayerId(eq("__daily__"), any()))
            .thenReturn(java.util.List.of(t));

        assertEquals(java.util.List.of("2026-07-15"), service.archiveDates(14));
    }

    @Test
    void leaderboardRanksFastestFirst() {
        dailyState.recordCompletion(service.today(), "slow", 500);
        dailyState.recordCompletion(service.today(), "fast", 90);

        var scores = service.leaderboard(10);

        assertEquals(2, scores.size());
        assertEquals(1, scores.get(0).rank());
        assertEquals("fast", scores.get(0).playerId());
        assertEquals(90, scores.get(0).solveTimeSeconds());
        assertEquals(2, scores.get(1).rank());
    }

    /** A genuinely solved board carrying the caller's daily gameId. */
    private SudokuBoard solvedDailyBoardFor(String playerId) {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, "daily-" + TODAY + ":" + playerId);
        com.xai.sudokupro.service.AISolverService solver = new com.xai.sudokupro.service.AISolverService(
            new SecureRandomGenerator(new SimpleMeterRegistry()));
        solver.solveSudoku(board);
        assertTrue(board.isSolved(), "test setup: board must be solved");
        return board;
    }
}
