package com.xai.sudokupro.service.duel;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Quarterly duel seasons. Rollover is LAZY: the first season query after a
 * quarter boundary performs it (guarded by SETNX so exactly one replica does) —
 * no scheduler to babysit. Rollover crowns the top three of the ladder with a
 * {@code SeasonChampion-<season>} achievement and soft-resets every rated
 * player toward 1000 ((r+1000)/2), keeping skill signal while giving each
 * season a fresh race.
 */
@Service
public class SeasonService {

    private static final Logger logger = LoggerFactory.getLogger(SeasonService.class);
    private static final String ROLLED_KEY = "sudokupro:season:rolled:"; // + seasonId (SETNX)

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);
    private final Map<String, Boolean> localRolled = new ConcurrentHashMap<>();

    public SeasonService(UserRepository userRepository, NotificationService notificationService,
                         StringRedisTemplate redis, Clock clock) {
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.redis = redis;
        this.clock = clock;
    }

    /** Season id, e.g. {@code 2026-Q3}. */
    public String seasonId() {
        LocalDate now = LocalDate.now(clock);
        return now.getYear() + "-Q" + ((now.getMonthValue() - 1) / 3 + 1);
    }

    /**
     * The season that just ENDED — the one whose ladder a rollover scores.
     *
     * <p>Rollover used to label the podium with {@link #seasonId()}, the season now
     * STARTING, while scoring the outgoing ladder. So the Q3 winners were crowned
     * "SeasonChampion-2026-Q4": nobody ever held a badge for the season they actually won,
     * and the real Q4 champion could never receive that badge because the key was already
     * taken.
     */
    public String previousSeasonId() {
        LocalDate firstDayOfThisSeason = seasonEnds().minusMonths(3);
        LocalDate inPreviousSeason = firstDayOfThisSeason.minusDays(1);
        return inPreviousSeason.getYear() + "-Q" + ((inPreviousSeason.getMonthValue() - 1) / 3 + 1);
    }

    /** First day of the next season (when the current one ends). */
    public LocalDate seasonEnds() {
        LocalDate now = LocalDate.now(clock);
        int quarter = (now.getMonthValue() - 1) / 3;
        LocalDate start = LocalDate.of(now.getYear(), quarter * 3 + 1, 1);
        return start.plusMonths(3);
    }

    /** Current season info; triggers the previous season's rollover if due. */
    @Transactional
    public Map<String, Object> current() {
        rolloverIfDue();
        return Map.of("seasonId", seasonId(), "endsOn", seasonEnds().toString());
    }

    /**
     * Exactly-once (per season, across replicas) rollover: crowns last season's
     * podium and soft-resets ratings. Runs lazily on the first query of a new
     * season; before any season has ever been marked, it simply marks the
     * current one so a fresh install doesn't "roll over" nothing.
     */
    void rolloverIfDue() {
        String startingSeason = seasonId();
        if (!claimRollover(startingSeason)) return; // already handled (or another replica won)

        // The ladder being scored is LAST season's result, so the badge must carry last
        // season's id — not the one just starting.
        String endedSeason = previousSeasonId();

        // If this is the very first season this install has seen, there is no
        // previous season to score — claiming the marker is all that's needed.
        List<User> podiumPlaces = userRepository.findDuelLadder(PageRequest.of(0, 3));
        if (podiumPlaces.isEmpty()) return;

        int place = 0;
        for (User user : podiumPlaces) {
            Map<String, Boolean> achievements = user.getAchievements();
            achievements.put("SeasonChampion-" + endedSeason, true);
            user.setAchievements(achievements);
            userRepository.save(user);
            notify(user.getUsername(), "Season " + endedSeason + " has ended — you finished top-"
                + (++place) + " on the duel ladder. Champion badge unlocked!");
        }

        // Soft-reset EVERY rated player, in one statement. This used to iterate the same
        // top-100 page as the podium query, so from player 101 down nobody was reset: the
        // un-compressed tail kept its rating and, season after season, permanently
        // outranked the compressed head — inverting the ladder the reset exists to refresh.
        int reset = userRepository.softResetDuelRatings();

        logger.info("Season {} rollover complete: {} rated players reset, podium of {} crowned for {}",
            startingSeason, reset, podiumPlaces.size(), endedSeason);
    }

    private boolean claimRollover(String season) {
        try {
            return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(ROLLED_KEY + season, "1"));
        } catch (Exception e) {
            if (degradedLogged.compareAndSet(false, true)) {
                logger.warn("SeasonService: Redis unavailable — rollover marker in-memory only. Cause: {}",
                    e.getMessage());
            }
            return localRolled.putIfAbsent(season, true) == null;
        }
    }

    private void notify(String playerId, String message) {
        try {
            notificationService.sendTypedNotification(playerId, "SEASON", message);
        } catch (Exception e) {
            logger.debug("Season notification failed: {}", e.getMessage());
        }
    }
}
