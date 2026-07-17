package com.xai.sudokupro.service.economy;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.GameEndListener;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.service.daily.DailyStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Makes the long-dormant {@code User.achievements} map real: unlock checks run
 * on every finished game via the {@link GameEndListener} hook. Uses the
 * canonical keys User.initializeAchievements has always declared (StreakMaster,
 * DuelChampion, DailyPlayer, LevelUp) plus two new play-quality ones
 * (CleanSolver, SpeedDemon). Each unlock notifies the player once.
 */
@Service
public class AchievementService implements GameEndListener {

    private static final Logger logger = LoggerFactory.getLogger(AchievementService.class);
    private static final long SPEED_DEMON_SECONDS = 120;

    private final EconomyService economyService;
    private final UserRepository userRepository;
    private final DailyStateStore dailyState;
    private final NotificationService notificationService;
    private final Clock clock;

    public AchievementService(EconomyService economyService, UserRepository userRepository,
                              DailyStateStore dailyState, NotificationService notificationService,
                              Clock clock) {
        this.economyService = economyService;
        this.userRepository = userRepository;
        this.dailyState = dailyState;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onGameEnded(SudokuBoard board, String playerId) {
        if (board == null || playerId == null || !board.isSolved()) return;
        if (playerId.startsWith("__")) return; // template pseudo-players

        try {
            User user = economyService.walletFor(playerId);
            Map<String, Boolean> unlocked = new LinkedHashMap<>();

            check(user, unlocked, "CleanSolver", board.getHintCount() == 0);
            check(user, unlocked, "SpeedDemon",
                board.getSolveTime().toSeconds() > 0 && board.getSolveTime().toSeconds() < SPEED_DEMON_SECONDS);
            check(user, unlocked, "DailyPlayer", board.getGameId().startsWith("daily-"));
            check(user, unlocked, "StreakMaster",
                dailyState.getStreak(playerId, LocalDate.now(clock)) >= 10);
            check(user, unlocked, "DuelChampion", user.getDuelWins() >= 5);
            check(user, unlocked, "LevelUp", user.getLevel() >= 5);

            if (unlocked.isEmpty()) return;

            Map<String, Boolean> all = user.getAchievements();
            all.putAll(unlocked);
            user.setAchievements(all);
            userRepository.save(user);
            for (String name : unlocked.keySet()) {
                logger.info("Achievement unlocked: {} for {}", name, playerId);
                notify(playerId, "Achievement unlocked: " + name + "!");
            }
        } catch (Exception e) {
            logger.warn("Achievement check failed for {}: {}", playerId, e.getMessage());
        }
    }

    /** Records a pending unlock when the condition holds and it wasn't unlocked before. */
    private void check(User user, Map<String, Boolean> unlocked, String name, boolean condition) {
        if (condition && !Boolean.TRUE.equals(user.getAchievements().get(name))) {
            unlocked.put(name, true);
        }
    }

    private void notify(String playerId, String message) {
        try {
            notificationService.sendTypedNotification(playerId, "ACHIEVEMENT", message);
        } catch (Exception e) {
            logger.debug("Achievement notification failed: {}", e.getMessage());
        }
    }
}
