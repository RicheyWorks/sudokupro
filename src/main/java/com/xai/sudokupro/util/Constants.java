package com.xai.sudokupro.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import com.xai.sudokupro.service.TelemetryService;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.stream.Stream;

/**
 * Centralized constants for SudokuPro, powering game rules, rewards, and system settings.
 */
@Component
@ConfigurationProperties(prefix = "game")
@Getter
@Setter
public class Constants {

    // 🔧 SAFE ResourceBundle (no crash if missing file)
    private static final ResourceBundle i18n;
    static {
        ResourceBundle temp;
        try {
            temp = ResourceBundle.getBundle("sudokupro");
        } catch (MissingResourceException e) {
            temp = null;
        }
        i18n = temp;
    }

    private static final ObjectMapper mapper = new ObjectMapper();

    // Board Configuration
    public static final int BOARD_SIZE = 9;
    public static final int BOX_SIZE = 3;

    // Difficulty tier range (1–5, used by the REST API and GameService validation)
    public static final int MIN_DIFFICULTY_TIER = 1;
    public static final int MAX_DIFFICULTY_TIER = 5;
    public static final int DEFAULT_DIFFICULTY_TIER = 3;

    // Cells-removed range (28–56, used by board generation — mapped from tiers via Difficulty enum)
    public static final int MIN_CELLS_REMOVED = 28;
    public static final int MAX_CELLS_REMOVED = 56;

    public static int TIME_ATTACK_SECONDS = 300;
    public static int INFINITE_MODE_LIVES = 3;
    public static int COSMIC_MODE_EVENTS = 3;

    // Difficulty Presets
    public enum Difficulty {
        EASY(40), MEDIUM(50), HARD(60), EXTREME(70), NIGHTMARE(80);
        public final int cellsRemoved;
        Difficulty(int cells) { this.cellsRemoved = cells; }
    }

    // Streak Tiers
    public enum StreakTier {
        INITIATE(5, i18n("streak.tier.initiate", "Initiate")),
        MASTER(20, i18n("streak.tier.master", "Master")),
        EMPEROR(50, i18n("streak.tier.emperor", "Grid Emperor")),
        LEGEND(100, i18n("streak.tier.legend", "Legend")),
        GOD(250, i18n("streak.tier.god", "God"));

        public final int solves;
        public final String title;

        StreakTier(int solves, String title) {
            this.solves = solves;
            this.title = title;
        }

        public int getSolves() { return solves; }
        public String getTitle() { return title; }
    }

    // Titles
    public enum Title {
        SUDOKU_SLAYER(25, i18n("title.sudoku.slayer", "Sudoku Slayer")),
        PUZZLE_PHANTOM(50, i18n("title.puzzle.phantom", "Puzzle Phantom")),
        GRID_EMPEROR(100, i18n("title.grid.emperor", "Grid Emperor"));

        public final int requiredStreak;
        public final String name;

        Title(int streak, String name) {
            this.requiredStreak = streak;
            this.name = name;
        }

        public int getRequiredStreak() { return requiredStreak; }
        public String getName() { return name; }
    }

    // Event Rules
    public enum EventType {
        MIRROR_MODE(1, 150),
        SPEED_PUZZLE(2, 200);

        public final int id;
        public final int bonusXP;

        EventType(int id, int bonusXP) {
            this.id = id;
            this.bonusXP = bonusXP;
        }
    }

    // Configurable Fields
    private int xpPerLevel = 1000;
    private int xpPerSolveEasy = 50;
    private int xpPerSolveMedium = 100;
    private int xpPerSolveHard = 150;
    private int xpPerDuelWin = 200;
    private int pointsPerSolveEasy = 10;
    private int pointsPerSolveMedium = 20;
    private int pointsPerSolveHard = 30;
    private int pointsPerSolveExtreme = 50;
    private int pointsPerSolveNightmare = 100;
    private int pointsPerDuelWin = 50;
    private int streakBonusMultiplier = 2;
    private int dailyLoginBonus = 25;
    private int streakProtectorCost = 100;
    private int gemsPerDailyLogin = 5;
    private int gemsPerDuelWin = 10;
    private int gemCostPowerupHint = 20;
    private int gemCostSkinUnlock = 50;
    private int gemCostStreakBoost = 30;
    private int timeAttackSeconds = 300;
    private boolean noPencilMode = true;
    private int mirrorModeSymmetry = 1;
    private int blitzModeSeconds = 60;
    private int chaosModeSwaps = 5;
    private int cosmicModeEvents = 3;
    private int infiniteModeLives = 3;

    // Static accessor fields
    public static int POINTS_PER_SOLVE_EASY = 10;
    public static int GEMS_PER_DUEL_WIN = 10;

    @Autowired
    public Constants(TelemetryService telemetry) {
        int adjust = telemetry != null ? telemetry.getDifficultyAdjustmentFactor() : 0;
        xpPerSolveEasy += adjust;
        pointsPerSolveEasy += adjust / 2;
    }

    public Constants() {}

    @PostConstruct
    public void validate() {
        if (xpPerLevel <= 0 || xpPerSolveEasy <= 0 || pointsPerSolveEasy <= 0) {
            throw new IllegalStateException("Critical game config values are invalid");
        }

        // Sync statics
        POINTS_PER_SOLVE_EASY = pointsPerSolveEasy;
        GEMS_PER_DUEL_WIN = gemsPerDuelWin;

        TIME_ATTACK_SECONDS = timeAttackSeconds;
        INFINITE_MODE_LIVES = infiniteModeLives;
        COSMIC_MODE_EVENTS = cosmicModeEvents;
    }

    private static String i18n(String key, String fallback) {
        if (i18n == null) return fallback;
        try {
            return i18n.getString(key);
        } catch (Exception e) {
            return fallback;
        }
    }

    public String getIntegrityHash() {
        return DigestUtils.sha256Hex(
            BOARD_SIZE + ":" +
            BOX_SIZE + ":" +
            xpPerLevel + ":" +
            xpPerSolveEasy + ":" +
            gemsPerDuelWin + ":" +
            streakBonusMultiplier + ":" +
            EventType.MIRROR_MODE.bonusXP
        );
    }

    // WebSocket Endpoints
    public static final String WEBSOCKET_ENDPOINT_DUEL = "/duel";
    public static final String WEBSOCKET_ENDPOINT_SPECTATE = "/spectate";
    public static final String WEBSOCKET_ENDPOINT_TOURNAMENT = "/tournament";
    public static final String WEBSOCKET_ENDPOINT_TAUNT = "/taunt";
    public static final String WEBSOCKET_ENDPOINT_GUILD = "/guild";
    public static final String WEBSOCKET_ENDPOINT_GLOBAL_CHAT = "/chat";
}
