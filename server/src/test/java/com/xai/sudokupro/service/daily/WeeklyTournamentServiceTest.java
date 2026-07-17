package com.xai.sudokupro.service.daily;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuGenerator;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.service.AISolverService;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WeeklyTournamentServiceTest {

    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC); // 2026-W29

    @Mock private GameService gameService;
    @Mock private NotificationService notificationService;

    private final Map<String, SudokuBoard> savedRows = new ConcurrentHashMap<>();
    private WeeklyTournamentService service;

    @BeforeEach
    void setUp() {
        GameRepository repo = mock(GameRepository.class);
        lenient().when(repo.save(any())).thenAnswer(inv -> {
            SudokuBoard b = inv.getArgument(0);
            savedRows.put(b.getGameId(), b);
            return b;
        });
        lenient().when(repo.findByGameId(anyString()))
            .thenAnswer(inv -> savedRows.get(inv.<String>getArgument(0)));

        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        service = new WeeklyTournamentService(gameService, repo, new GameLockManager(downRedis),
            new SudokuGenerator(new SecureRandomGenerator(new SimpleMeterRegistry())),
            notificationService, downRedis, FIXED_CLOCK);
    }

    @Test
    void weekIdIsIsoBased() {
        assertEquals("2026-W29", service.weekId());
    }

    @Test
    void joiningCreatesRampedTemplatesAndPlayerCopies() {
        when(gameService.getGame(anyString())).thenThrow(new IllegalArgumentException("nf"));
        when(gameService.adoptGame(any())).thenAnswer(inv -> inv.getArgument(0));

        SudokuBoard p1 = service.join("richmond", 1);
        SudokuBoard p5 = service.join("richmond", 5);

        assertEquals("week-2026-W29-p1:richmond", p1.getGameId());
        assertNotNull(savedRows.get("week-2026-W29-p1"), "template persisted");
        assertNotNull(savedRows.get("week-2026-W29-p5"));
        assertEquals(1, p1.getDifficulty());
        assertEquals(4, p5.getDifficulty(), "difficulty ramps across the week");
        assertThrows(IllegalArgumentException.class, () -> service.join("richmond", 6));
    }

    @Test
    void standingsRankOnlyFullFinishersByTotalTime() {
        // richmond finishes all five (total 500), ada only four
        for (int i = 1; i <= 5; i++) {
            service.onGameEnded(solved("week-2026-W29-p" + i + ":richmond", "richmond", 100), "richmond");
        }
        for (int i = 1; i <= 4; i++) {
            service.onGameEnded(solved("week-2026-W29-p" + i + ":ada", "ada", 50), "ada");
        }

        var standings = service.standings(10);

        assertEquals(1, standings.size(), "only full finishers are ranked");
        assertEquals("richmond", standings.get(0).get("playerId"));
        assertEquals(1, standings.get(0).get("rank"));

        var status = service.status("ada");
        assertEquals(4, status.get("completed"));
        assertEquals(false, status.get("ranked"));
    }

    @Test
    void repeatSolvesOfTheSamePuzzleDontOverwriteTheTime() {
        service.onGameEnded(solved("week-2026-W29-p1:richmond", "richmond", 100), "richmond");
        service.onGameEnded(solved("week-2026-W29-p1:richmond", "richmond", 5), "richmond");

        var status = service.status("richmond");
        assertEquals(100L, ((List<Map<String, Object>>) status.get("puzzles")).get(0).get("seconds"),
            "first recorded time stands");
    }

    /** A genuinely solved board whose reported solve time is pinned for the test. */
    private SudokuBoard solved(String gameId, String playerId, long fakeSeconds) {
        SudokuBoard real = new SudokuBoard(1, false, false, 0, gameId);
        real.setPlayerId(playerId);
        new AISolverService(new SecureRandomGenerator(new SimpleMeterRegistry())).solveSudoku(real);
        SudokuBoard board = spy(real);
        doReturn(java.time.Duration.ofSeconds(fakeSeconds)).when(board).getSolveTime();
        return board;
    }
}
