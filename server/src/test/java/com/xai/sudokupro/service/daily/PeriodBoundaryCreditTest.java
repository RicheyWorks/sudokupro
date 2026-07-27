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
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The defect class these cover: <b>a completion scored against the wall clock at solve
 * time instead of against the puzzle the board actually is.</b>
 *
 * <p>Both `DailyPuzzleService` and `WeeklyTournamentService` recomputed "today" / "this
 * week" inside `onGameEnded` and required it to match the id the board was stamped with
 * at join time. A puzzle carried across a period boundary — joined at 23:58, finished at
 * 00:01 — therefore matched nothing and was discarded with no record and no log: no
 * completion, no streak, no leaderboard entry, no notification. For the tournament it is
 * worse than losing one result, because standings require all five puzzles, so a single
 * boundary crossing disqualifies the player for the whole week — and a Sunday-to-Monday
 * boundary is inside every tournament by construction.
 *
 * <p><b>Why the existing suite could not see it.</b> Every daily and weekly test pins a
 * `Clock.fixed`, which is the one arrangement in which join-time and solve-time can never
 * disagree. The bug lived precisely in the gap those tests defined away. So these tests
 * use a <em>movable</em> clock: join under one instant, solve under another. That is the
 * whole point of the class, and a fixed clock here would silently stop testing anything.
 */
class PeriodBoundaryCreditTest {

    /** A clock whose instant the test moves, so join-time and solve-time can differ. */
    private static final class MovableClock extends Clock {
        private Instant now;
        MovableClock(String iso) { this.now = Instant.parse(iso); }
        void set(String iso) { this.now = Instant.parse(iso); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final Map<String, SudokuBoard> savedRows = new ConcurrentHashMap<>();

    private StringRedisTemplate downRedis() {
        return mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
    }

    private GameRepository repoFake() {
        GameRepository repo = mock(GameRepository.class);
        lenient().when(repo.save(any())).thenAnswer(inv -> {
            SudokuBoard b = inv.getArgument(0);
            savedRows.put(b.getGameId(), b);
            return b;
        });
        lenient().when(repo.findByGameId(anyString()))
            .thenAnswer(inv -> savedRows.get(inv.<String>getArgument(0)));
        return repo;
    }

    /** A genuinely solved board carrying an arbitrary gameId. */
    private SudokuBoard solvedBoard(String gameId, String playerId) {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, gameId);
        board.setPlayerId(playerId);
        new AISolverService(new SecureRandomGenerator(new SimpleMeterRegistry())).solveSudoku(board);
        assertTrue(board.isSolved(), "test setup: board must be solved");
        return board;
    }

    // ─────────────────────────────── daily ───────────────────────────────

    private record DailyFixture(DailyPuzzleService service, DailyStateStore state, MovableClock clock,
                                NotificationService notifications) {}

    private DailyFixture dailyFixture(String startingInstant) {
        MovableClock clock = new MovableClock(startingInstant);
        StringRedisTemplate redis = downRedis();
        DailyStateStore state = new DailyStateStore(redis);
        NotificationService notifications = mock(NotificationService.class);
        DailyPuzzleService service = new DailyPuzzleService(
            mock(GameService.class), repoFake(), new GameLockManager(redis),
            new SudokuGenerator(new SecureRandomGenerator(new SimpleMeterRegistry())),
            state, notifications, clock);
        return new DailyFixture(service, state, clock, notifications);
    }

    /**
     * The bug itself. Joined 23:58 on the 16th, solved 00:01 on the 17th: before the fix
     * the completion was compared against `daily-2026-07-17:richmond`, matched nothing,
     * and the player lost the solve entirely.
     */
    @Test
    void aDailyCarriedAcrossUtcMidnightStillCounts() {
        DailyFixture f = dailyFixture("2026-07-16T23:58:00Z");
        SudokuBoard board = solvedBoard("daily-2026-07-16:richmond", "richmond");

        f.clock().set("2026-07-17T00:01:00Z");
        f.service().onGameEnded(board, "richmond");

        assertTrue(f.state().isCompleted(LocalDate.of(2026, 7, 16), "richmond"),
            "the completion belongs to the puzzle that was solved, not to the day it was finished on");
        assertEquals(1, f.state().getStreak("richmond", LocalDate.of(2026, 7, 17)),
            "a streak earned at 00:01 is still alive on the day it was earned into");
        verify(f.notifications()).sendTypedNotification(eq("richmond"), eq("DAILY"), anyString());
    }

    /**
     * The credit window has an edge, and it is where the streak model's own "alive"
     * definition puts it. Two days stale earns gems but no streak or leaderboard slot.
     */
    @Test
    void aDailyLeftForDaysEarnsNoStreakCredit() {
        DailyFixture f = dailyFixture("2026-07-16T10:00:00Z");
        SudokuBoard board = solvedBoard("daily-2026-07-16:richmond", "richmond");

        f.clock().set("2026-07-19T10:00:00Z");
        f.service().onGameEnded(board, "richmond");

        assertFalse(f.state().isCompleted(LocalDate.of(2026, 7, 16), "richmond"),
            "a three-day-old board must not write into a settled day");
        verify(f.notifications(), never()).sendTypedNotification(anyString(), anyString(), anyString());
    }

    /**
     * Back-dating must never cost a player a streak they already hold. Finishing
     * yesterday's leftover after today's is done used to fall through `advanceStreak`'s
     * final `else`, resetting a live streak to 1 and moving `lastDate` backwards so the
     * next genuine completion could not extend it either.
     */
    @Test
    void fillingInYesterdayDoesNotDestroyALiveStreak() {
        DailyFixture f = dailyFixture("2026-07-16T09:00:00Z");

        // Two days running, then today's is already done: streak 3, last = the 17th.
        f.state().recordCompletion(LocalDate.of(2026, 7, 15), "richmond", 100);
        f.state().recordCompletion(LocalDate.of(2026, 7, 16), "richmond", 100);
        f.state().recordCompletion(LocalDate.of(2026, 7, 17), "richmond", 100);
        assertEquals(3, f.state().getStreak("richmond", LocalDate.of(2026, 7, 17)),
            "test setup: a live three-day streak");

        // Now a leftover board for the 16th arrives late. It is already completed, so
        // recordCompletion is a no-op — drive advanceStreak directly via a fresh day
        // that is older than the streak's last date.
        f.state().recordCompletion(LocalDate.of(2026, 7, 14), "richmond", 100);

        assertEquals(3, f.state().getStreak("richmond", LocalDate.of(2026, 7, 17)),
            "an out-of-order older completion must not reset a live streak");
    }

    /** The archive copy shares the daily prefix and must still earn no credit. */
    @Test
    void archiveCopiesAreNotDailyCompletions() {
        assertNull(DailyPuzzleService.puzzleDateOf("daily-2026-07-16:archive:richmond", "richmond"),
            "an archive replay is not a completion of that day's puzzle");
        assertNull(DailyPuzzleService.puzzleDateOf("daily-2026-07-16:alice", "richmond"),
            "another player's copy is not this player's completion");
        assertNull(DailyPuzzleService.puzzleDateOf("daily-2026-07-16", "richmond"),
            "the shared template is nobody's completion");
        assertNull(DailyPuzzleService.puzzleDateOf("week-2026-W29-p1:richmond", "richmond"),
            "a tournament board is not a daily");
        assertNull(DailyPuzzleService.puzzleDateOf("daily-not-a-date:richmond", "richmond"),
            "an unparseable date must not throw");
        assertEquals(LocalDate.of(2026, 7, 16),
            DailyPuzzleService.puzzleDateOf("daily-2026-07-16:richmond", "richmond"));
    }

    // ───────────────────────────── weekly ─────────────────────────────

    private record WeeklyFixture(WeeklyTournamentService service, MovableClock clock,
                                 NotificationService notifications) {}

    private WeeklyFixture weeklyFixture(String startingInstant) {
        MovableClock clock = new MovableClock(startingInstant);
        StringRedisTemplate redis = downRedis();
        NotificationService notifications = mock(NotificationService.class);
        WeeklyTournamentService service = new WeeklyTournamentService(
            mock(GameService.class), repoFake(), new GameLockManager(redis),
            new SudokuGenerator(new SecureRandomGenerator(new SimpleMeterRegistry())),
            notifications, redis, clock);
        return new WeeklyFixture(service, clock, notifications);
    }

    /**
     * 2026-07-19 is a Sunday (ISO week 29); 2026-07-20 is the Monday that starts week 30.
     * Before the fix the Monday-morning finish was compared only against `week-2026-W30-p*`
     * and vanished — and because standings need all five, that one loss disqualified the
     * player for the week.
     */
    @Test
    void aTournamentPuzzleCarriedAcrossTheWeekBoundaryStillCounts() {
        WeeklyFixture f = weeklyFixture("2026-07-19T23:55:00Z");
        assertEquals("2026-W29", f.service().weekId(), "test setup: joined during week 29");
        SudokuBoard board = solvedBoard("week-2026-W29-p3:richmond", "richmond");

        f.clock().set("2026-07-20T00:05:00Z");
        assertEquals("2026-W30", f.service().weekId(), "test setup: solved during week 30");

        f.service().onGameEnded(board, "richmond");

        // The notification is the observable proof the result was recorded: it fires
        // only on the recordTime path, which the boundary crossing used to skip.
        verify(f.notifications()).sendTypedNotification(eq("richmond"), eq("TOURNAMENT"), anyString());
        assertEquals("2026-W30", f.service().status("richmond").get("weekId"),
            "status still reports the current week — the credit landed on week 29, where it belongs");
    }

    /** Parsing is exact: another player's copy, the template, and junk all yield null. */
    @Test
    void tournamentPuzzleRefIsParsedExactly() {
        assertEquals(new WeeklyTournamentService.PuzzleRef("2026-W29", 3),
            WeeklyTournamentService.puzzleRefOf("week-2026-W29-p3:richmond", "richmond"));
        assertNull(WeeklyTournamentService.puzzleRefOf("week-2026-W29-p3:alice", "richmond"));
        assertNull(WeeklyTournamentService.puzzleRefOf("week-2026-W29-p3", "richmond"),
            "the shared template earns nobody credit");
        assertNull(WeeklyTournamentService.puzzleRefOf("week-2026-W29-p9:richmond", "richmond"),
            "a sixth puzzle would inflate times.size() past the all-five standings gate");
        assertNull(WeeklyTournamentService.puzzleRefOf("week-2026-W29-px:richmond", "richmond"),
            "a non-numeric index must not throw");
        assertNull(WeeklyTournamentService.puzzleRefOf("daily-2026-07-16:richmond", "richmond"),
            "a daily board is not a tournament puzzle");
    }

    /**
     * The week id of the preceding week is computed by date arithmetic, not by
     * decrementing the number — 2027-W01's predecessor is 2026-W53.
     */
    @Test
    void previousWeekIsCorrectAcrossAWeekBasedYearBoundary() {
        assertEquals("2026-W53", WeeklyTournamentService.weekIdOf(LocalDate.of(2027, 1, 4).minusWeeks(1)));
        assertEquals("2027-W01", WeeklyTournamentService.weekIdOf(LocalDate.of(2027, 1, 4)));
    }
}
