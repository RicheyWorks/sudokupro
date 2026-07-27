package com.xai.sudokupro.service.daily;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuGenerator;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.service.GameEndListener;
import com.xai.sudokupro.service.GameLockManager;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Weekly tournament: five puzzles per ISO week (difficulty ramps 1→4 across
 * them), cumulative solve time decides the standings, and only players who
 * finish ALL five are ranked. Built on the daily-puzzle pattern: persisted
 * template rows ({@code week-<year-Www>-p<n>}) agreed on via the cross-replica
 * lock, per-player copies through the ordinary game machinery, completion via
 * the GameEndListener hook, and per-week state in Redis with local degrade.
 */
@Service
public class WeeklyTournamentService implements GameEndListener {

    private static final Logger logger = LoggerFactory.getLogger(WeeklyTournamentService.class);
    static final String WEEK_PREFIX = "week-";
    static final int PUZZLES_PER_WEEK = 5;
    private static final String TIMES_KEY = "sudokupro:week:times:"; // + weekId:player → hash p<i>→seconds

    private final GameService gameService;
    private final GameRepository gameRepository;
    private final GameLockManager gameLocks;
    private final SudokuGenerator generator;
    private final NotificationService notificationService;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);
    private final Map<String, Map<String, Long>> localTimes = new ConcurrentHashMap<>();

    public WeeklyTournamentService(GameService gameService, GameRepository gameRepository,
                                   GameLockManager gameLocks, SudokuGenerator generator,
                                   NotificationService notificationService,
                                   StringRedisTemplate redis, Clock clock) {
        this.gameService = gameService;
        this.gameRepository = gameRepository;
        this.gameLocks = gameLocks;
        this.generator = generator;
        this.notificationService = notificationService;
        this.redis = redis;
        this.clock = clock;
    }

    /** ISO week id, e.g. {@code 2026-W29}. */
    public String weekId() {
        return weekIdOf(LocalDate.now(clock));
    }

    /** ISO week id for an arbitrary date — used to name the immediately preceding week. */
    static String weekIdOf(LocalDate date) {
        return String.format("%d-W%02d",
            date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /**
     * The week before the current one. Computed by subtracting seven days rather than
     * decrementing the week number, so it stays correct across a week-based-year
     * boundary (2027-W01 → 2026-W53, which arithmetic on the number would get wrong).
     */
    private String previousWeekId() {
        return weekIdOf(LocalDate.now(clock).minusWeeks(1));
    }

    static String templateId(String weekId, int puzzle) {
        return WEEK_PREFIX + weekId + "-p" + puzzle;
    }

    static String playerGameId(String weekId, int puzzle, String playerId) {
        return templateId(weekId, puzzle) + ":" + playerId;
    }

    /** Joins puzzle {@code n} (1-5) of this week's tournament. */
    public SudokuBoard join(String playerId, int puzzle) {
        if (puzzle < 1 || puzzle > PUZZLES_PER_WEEK) {
            throw new IllegalArgumentException("Puzzle must be 1-" + PUZZLES_PER_WEEK);
        }
        String week = weekId();
        String gameId = playerGameId(week, puzzle, playerId);
        try {
            return gameService.getGame(gameId);
        } catch (IllegalArgumentException notFound) {
            SudokuBoard copy = SudokuBoard.playerCopy(template(week, puzzle), gameId, playerId);
            gameService.adoptGame(copy);
            return copy;
        }
    }

    /** The caller's progress: which of the five are done, and their times. */
    public Map<String, Object> status(String playerId) {
        String week = weekId();
        Map<String, Long> times = timesFor(week, playerId);
        List<Map<String, Object>> puzzles = new ArrayList<>();
        long total = 0;
        for (int i = 1; i <= PUZZLES_PER_WEEK; i++) {
            Long t = times.get("p" + i);
            puzzles.add(Map.of("puzzle", i, "completed", t != null, "seconds", t == null ? 0 : t));
            if (t != null) total += t;
        }
        return Map.of("weekId", week, "puzzles", puzzles,
            "completed", times.size(), "totalSeconds", total,
            "ranked", times.size() == PUZZLES_PER_WEEK);
    }

    /** Standings: only players who finished all five, fastest cumulative time first. */
    public List<Map<String, Object>> standings(int limit) {
        String week = weekId();
        Map<String, Map<String, Long>> all = allTimes(week);
        List<Map.Entry<String, Long>> ranked = new ArrayList<>();
        for (var e : all.entrySet()) {
            if (e.getValue().size() == PUZZLES_PER_WEEK) {
                ranked.add(Map.entry(e.getKey(),
                    e.getValue().values().stream().mapToLong(Long::longValue).sum()));
            }
        }
        ranked.sort(Map.Entry.comparingByValue());
        List<Map<String, Object>> out = new ArrayList<>();
        int rank = 1;
        for (var e : ranked.subList(0, Math.min(ranked.size(), Math.max(1, Math.min(limit, 100))))) {
            out.add(Map.of("rank", rank++, "playerId", e.getKey(), "totalSeconds", e.getValue()));
        }
        return out;
    }

    /** Which tournament puzzle a board is: its week and its 1-5 index. */
    record PuzzleRef(String weekId, int puzzle) {}

    /**
     * Records a finished tournament puzzle.
     *
     * <p><b>The week comes from the board, not the clock.</b> This used to recompute
     * {@code weekId()} at solve time and compare it against the id the board was
     * stamped with at join time, so a puzzle joined on Sunday at 23:5x and solved on
     * Monday at 00:0x matched none of the five candidates and fell out of the loop
     * with no record and no log. That is worse here than for the daily: standings
     * require all five puzzles ({@code times.size() == PUZZLES_PER_WEEK}), so one lost
     * puzzle silently disqualifies the player for the entire week, and the tournament
     * has exactly one Sunday-to-Monday boundary in it by construction. Every weekly
     * test pins a {@code Clock.fixed}, so nothing could see it.
     *
     * <p>The previous week still counts — its times key lives 14 days, so recording
     * into it is well inside the data's own lifetime — and anything older is logged
     * rather than dropped in silence.
     */
    @Override
    public void onGameEnded(SudokuBoard board, String playerId) {
        if (board == null || playerId == null || !board.isSolved()) return;
        PuzzleRef ref = puzzleRefOf(board.getGameId(), playerId);
        if (ref == null) return; // not this player's tournament copy

        if (!ref.weekId().equals(weekId()) && !ref.weekId().equals(previousWeekId())) {
            logger.info("Player {} finished tournament puzzle {} of {} — older than the previous "
                + "week, gems only (no standings credit)", playerId, ref.puzzle(), ref.weekId());
            return;
        }

        String week = ref.weekId();
        int i = ref.puzzle();
        recordTime(week, playerId, i, Math.max(1, board.getSolveTime().toSeconds()));
        Map<String, Long> times = timesFor(week, playerId);
        if (times.size() == PUZZLES_PER_WEEK) {
            long total = times.values().stream().mapToLong(Long::longValue).sum();
            notify(playerId, "Tournament complete! Total " + total + "s — check the standings.");
        } else {
            notify(playerId, "Tournament puzzle " + i + " done — "
                + (PUZZLES_PER_WEEK - times.size()) + " to go.");
        }
    }

    /**
     * The week and index of the tournament puzzle this board <em>is</em>, or null when
     * the board is not {@code playerId}'s own tournament copy.
     *
     * <p>Parses {@code week-<weekId>-p<n>:<playerId>}. Requiring the segment after the
     * colon to equal the player id keeps another player's copy out, and the index is
     * range-checked so a hand-crafted id cannot write a sixth puzzle into the hash and
     * inflate {@code times.size()} past the all-five standings gate.
     */
    static PuzzleRef puzzleRefOf(String gameId, String playerId) {
        if (gameId == null || playerId == null || !gameId.startsWith(WEEK_PREFIX)) return null;
        String rest = gameId.substring(WEEK_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon < 0) return null;                                    // the shared template itself
        if (!rest.substring(colon + 1).equals(playerId)) return null;  // not this player's copy
        String left = rest.substring(0, colon);                        // "<weekId>-p<n>"
        int p = left.lastIndexOf("-p");
        if (p < 0) return null;
        try {
            int puzzle = Integer.parseInt(left.substring(p + 2));
            if (puzzle < 1 || puzzle > PUZZLES_PER_WEEK) return null;
            return new PuzzleRef(left.substring(0, p), puzzle);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- template + state plumbing --------------------------------------------

    private SudokuBoard template(String week, int puzzle) {
        String id = templateId(week, puzzle);
        SudokuBoard template = gameRepository.findByGameId(id);
        if (template != null) return template;
        try (var lock = gameLocks.lock(id)) {
            template = gameRepository.findByGameId(id);
            if (template != null) return template;
            // Difficulty ramps across the week: p1 easy … p4/p5 hard. HARD is the
            // generator's ceiling: EXTREME(70)/NIGHTMARE(80) would leave fewer than
            // 17 clues, below the mathematical minimum for a unique solution, so
            // generation can never succeed there.
            Constants.Difficulty difficulty = switch (puzzle) {
                case 1 -> Constants.Difficulty.EASY;
                case 2, 3 -> Constants.Difficulty.MEDIUM;
                default -> Constants.Difficulty.HARD;
            };
            SudokuBoard generated = generateWithFallback(difficulty);
            generated.setGameId(id);
            generated.setPlayerId(DailyPuzzleService.TEMPLATE_PLAYER);
            generated.setDifficulty(Math.min(puzzle, 4));
            gameRepository.save(generated);
            logger.info("Weekly template {} generated", id);
            return generated;
        }
    }

    /**
     * HARD (60 removals → 21 clues) sometimes exhausts the generator's retry
     * budget — uniqueness gets scarce near the 17-clue floor. Fall back to
     * MEDIUM rather than failing the whole tournament join.
     */
    private SudokuBoard generateWithFallback(Constants.Difficulty preferred) {
        try {
            return generator.generate(preferred, false, false, System.currentTimeMillis());
        } catch (RuntimeException e) {
            logger.warn("Generation at {} failed ({}); falling back to MEDIUM", preferred, e.getMessage());
            return generator.generate(Constants.Difficulty.MEDIUM, false, false, System.currentTimeMillis());
        }
    }

    private void recordTime(String week, String playerId, int puzzle, long seconds) {
        String key = TIMES_KEY + week + ":" + playerId;
        try {
            redis.opsForHash().putIfAbsent(key, "p" + puzzle, String.valueOf(seconds));
            redis.expire(key, java.time.Duration.ofDays(14));
        } catch (Exception e) {
            degraded(e);
            localTimes.computeIfAbsent(week + ":" + playerId, k -> new ConcurrentHashMap<>())
                .putIfAbsent("p" + puzzle, seconds);
        }
    }

    private Map<String, Long> timesFor(String week, String playerId) {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(TIMES_KEY + week + ":" + playerId);
            Map<String, Long> out = new HashMap<>();
            raw.forEach((k, v) -> out.put(k.toString(), Long.parseLong(v.toString())));
            return out;
        } catch (Exception e) {
            degraded(e);
            return Map.copyOf(localTimes.getOrDefault(week + ":" + playerId, Map.of()));
        }
    }

    /**
     * Every player's per-round times for {@code week}.
     *
     * <p>This used {@code redis.keys(pattern)}. {@code KEYS} is O(total keys in the
     * database) and <b>blocks single-threaded Redis for the whole scan</b> — the pattern
     * narrows the result, not the work. This is the same Redis instance that holds every
     * cached board, every {@link com.xai.sudokupro.service.GameLockManager} lock, the
     * player state store and the pub/sub relay, and {@code acquireRedis} gives up after
     * three seconds with "busy on another server instance". So an uncached endpoint any
     * authenticated user can poll — {@code GET /api/tournament/standings} — converted
     * directly into failed moves for unrelated players across the whole server, and, in
     * combination with the unguarded cache read in {@code GameService}, into 500s.
     *
     * <p>{@code SCAN} does the same work in bounded increments without blocking, and the
     * {@code HGETALL}s are unchanged (still one per participant, which is fine — that N
     * is the number of players in the week, not the size of the keyspace).
     */
    private Map<String, Map<String, Long>> allTimes(String week) {
        String prefix = TIMES_KEY + week + ":";
        try {
            Map<String, Map<String, Long>> out = new HashMap<>();
            var scanOptions = org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match(prefix + "*").count(256).build();
            try (var cursor = redis.scan(scanOptions)) {
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    String player = key.substring(prefix.length());
                    Map<String, Long> t = new HashMap<>();
                    redis.opsForHash().entries(key)
                        .forEach((k, v) -> t.put(k.toString(), Long.parseLong(v.toString())));
                    out.put(player, t);
                }
            }
            return out;
        } catch (Exception e) {
            degraded(e);
            Map<String, Map<String, Long>> out = new HashMap<>();
            String localPrefix = week + ":";
            localTimes.forEach((k, v) -> {
                if (k.startsWith(localPrefix)) out.put(k.substring(localPrefix.length()), Map.copyOf(v));
            });
            return out;
        }
    }

    private void notify(String playerId, String message) {
        try {
            notificationService.sendTypedNotification(playerId, "TOURNAMENT", message);
        } catch (Exception e) {
            logger.debug("Tournament notification failed: {}", e.getMessage());
        }
    }

    private void degraded(Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            logger.warn("WeeklyTournamentService: Redis unavailable — tournament state in-memory only. Cause: {}",
                e.getMessage());
        }
    }
}
