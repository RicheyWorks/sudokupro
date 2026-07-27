package com.xai.sudokupro.service.economy;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.AISolverService;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.service.daily.DailyStateStore;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AchievementServiceTest {

    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC);

    @Mock private NotificationService notificationService;

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private DailyStateStore dailyState;
    private AchievementService service;
    private UserRepository repo;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
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
        dailyState = new DailyStateStore(downRedis);
        service = new AchievementService(new EconomyService(repo, 5, 15, 5), repo,
            dailyState, notificationService, FIXED_CLOCK);
    }

    @Test
    void cleanSolveUnlocksCleanSolverOnceAndNotifiesOnce() {
        SudokuBoard board = solved("g1", "richmond");

        service.onGameEnded(board, "richmond");
        service.onGameEnded(solved("g2", "richmond"), "richmond");

        assertTrue(users.get("richmond").getAchievements().get("CleanSolver"));
        verify(notificationService, times(1))
            .sendTypedNotification(eq("richmond"), eq("ACHIEVEMENT"), contains("CleanSolver"));
    }

    @Test
    void dailyGameUnlocksDailyPlayer() {
        service.onGameEnded(solved("daily-2026-07-16:richmond", "richmond"), "richmond");
        assertTrue(users.get("richmond").getAchievements().get("DailyPlayer"));
    }

    @Test
    void tenDayStreakUnlocksStreakMaster() {
        LocalDate day = LocalDate.of(2026, 7, 7);
        for (int i = 0; i < 10; i++) {
            dailyState.recordCompletion(day.plusDays(i), "richmond", 100);
        }

        service.onGameEnded(solved("g-streak", "richmond"), "richmond");

        assertTrue(users.get("richmond").getAchievements().get("StreakMaster"));
    }

    @Test
    void duelChampionNeedsFiveWins() {
        User user = new User(null, "richmond");
        user.setDuelWins(5);
        users.put("richmond", user);

        service.onGameEnded(solved("g-duel", "richmond"), "richmond");

        assertTrue(users.get("richmond").getAchievements().get("DuelChampion"));
    }

    @Test
    void unsolvedGamesAndPseudoPlayersUnlockNothing() {
        service.onGameEnded(new SudokuBoard(1, false, false, 0, "g-quit"), "richmond");
        service.onGameEnded(solved("daily-2026-07-16", "__daily__"), "__daily__");

        assertFalse(users.containsKey("__daily__"));
        // richmond's wallet may exist but nothing unlocked
        if (users.containsKey("richmond")) {
            assertFalse(users.get("richmond").getAchievements().containsValue(true));
        }
        verify(notificationService, never()).sendTypedNotification(anyString(), anyString(), anyString());
    }

    /**
     * The reward half of the double-unlock reconciliation: an unlock through this
     * service pays the same 20/100/30/10 that User.checkAchievement pays, exactly
     * once, and atomically (via the SQL credit, never a full-row entity save).
     */
    @Test
    void unlockPaysTheStandardRewardExactlyOnce() {
        lenient().when(repo.creditAchievementReward(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenReturn(1);

        service.onGameEnded(solved("g1", "richmond"), "richmond");
        // Same achievement again: already TRUE, so no second unlock and no second payout.
        service.onGameEnded(solved("g2", "richmond"), "richmond");

        verify(repo, times(1)).creditAchievementReward("richmond",
            User.ACHIEVEMENT_GEMS, User.ACHIEVEMENT_XP,
            User.ACHIEVEMENT_HYPE, User.ACHIEVEMENT_DRIP);
    }

    /**
     * The suppression half: whichever path unlocks first, the other pays nothing.
     * Here the ENTITY path (User.checkAchievement) unlocks CleanSolver first — which
     * also proves the guard fix, since CleanSolver is not one of the seeded keys and
     * the old containsKey guard refused it — and the service run must then credit
     * no reward at all.
     */
    @Test
    void entityPathUnlockSuppressesTheServicePayout() {
        User user = new User(null, "richmond");
        int before = user.getGems();
        user.checkAchievement("CleanSolver", true);
        assertEquals(before + User.ACHIEVEMENT_GEMS, user.getGems(),
            "the entity path must pay the unlock it made");
        users.put("richmond", user);

        service.onGameEnded(solved("g1", "richmond"), "richmond");

        assertTrue(users.get("richmond").getAchievements().get("CleanSolver"));
        verify(repo, never()).creditAchievementReward(anyString(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    private SudokuBoard solved(String gameId, String playerId) {
        SudokuBoard board = new SudokuBoard(1, false, false, 0, gameId);
        board.setPlayerId(playerId);
        new AISolverService(new SecureRandomGenerator(new SimpleMeterRegistry())).solveSudoku(board);
        return board;
    }
}
