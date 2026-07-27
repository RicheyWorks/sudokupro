package com.xai.sudokupro.service.economy;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.GameEndListener;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.service.daily.DailyPuzzleService;
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
 * (CleanSolver, SpeedDemon). Each unlock notifies the player once and pays the
 * standard reward ({@code User.ACHIEVEMENT_GEMS} etc.) — the same payout
 * {@code User.checkAchievement} makes on the entity path, so whichever of the
 * two unlock paths fires first, the player gets exactly one reward.
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
        if (!playerId.equals(board.getPlayerId())) return; // owner only

        try {
            User user = economyService.walletFor(playerId);
            Map<String, Boolean> unlocked = new LinkedHashMap<>();

            check(user, unlocked, "CleanSolver", board.getHintCount() == 0);
            check(user, unlocked, "SpeedDemon",
                board.getSolveTime().toSeconds() > 0 && board.getSolveTime().toSeconds() < SPEED_DEMON_SECONDS);
            // Exact match, not a prefix test. `startsWith("daily-")` also matched the
            // archive copy (`daily-<date>:archive:<player>`) and any stale-day board, so
            // replaying an old puzzle from the archive unlocked "DailyPlayer" — an award
            // for keeping up with the daily, handed out for doing the opposite.
            // DailyPuzzleService already draws this line correctly for streaks; this is
            // the same rule, from the same helper, so the two cannot drift apart.
            check(user, unlocked, "DailyPlayer",
                DailyPuzzleService.puzzleDateOf(board.getGameId(), playerId) != null);
            check(user, unlocked, "StreakMaster",
                dailyState.getStreak(playerId, LocalDate.now(clock)) >= 10);
            check(user, unlocked, "DuelChampion", user.getDuelWins() >= 5);
            check(user, unlocked, "LevelUp", user.getLevel() >= 5);

            if (unlocked.isEmpty()) return;

            Map<String, Boolean> all = user.getAchievements();
            all.putAll(unlocked);
            user.setAchievements(all);
            userRepository.save(user);

            // Pay the unlock reward this path always owed. User.checkAchievement pays
            // 20 gems / 100 xp / 30 hype / 10 drip per unlock, and both paths suppress
            // names the other already set — so when this service unlocked first (which
            // in practice was always: the entity mutators that reach checkAchievement
            // have no production callers outside EventEngine's dormant score path), the
            // player received the achievement flag and none of the reward. Credited
            // atomically in SQL, not via the entity: this listener runs alongside
            // EconomyService's solve payout and any in-flight hint charge, and a
            // full-row save computed from an earlier read silently reverts whichever
            // credit committed in between (the exact lost-update creditGemsAndXp was
            // introduced to kill).
            int n = unlocked.size();
            int rows = userRepository.creditAchievementReward(playerId,
                User.ACHIEVEMENT_GEMS * n, User.ACHIEVEMENT_XP * n,
                User.ACHIEVEMENT_HYPE * n, User.ACHIEVEMENT_DRIP * n);
            if (rows > 0) {
                // level is a pure function of xp (1 + xp/100); recompute from a fresh
                // read, same self-healing shape as EconomyService.onGameEnded.
                userRepository.findByUsername(playerId).ifPresent(fresh -> {
                    int derived = 1 + (fresh.getXp() / 100);
                    if (derived > fresh.getLevel()) userRepository.updateLevel(playerId, derived);
                });
            } else {
                logger.warn("Achievement reward for {} matched no wallet row", playerId);
            }

            for (String name : unlocked.keySet()) {
                logger.info("Achievement unlocked: {} for {} (+{} gems, +{} xp)",
                    name, playerId, User.ACHIEVEMENT_GEMS, User.ACHIEVEMENT_XP);
                notify(playerId, "Achievement unlocked: " + name + "! +"
                    + User.ACHIEVEMENT_GEMS + " gems, +" + User.ACHIEVEMENT_XP + " XP");
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
