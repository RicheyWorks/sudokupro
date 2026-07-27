package com.xai.sudokupro.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents a player in SudokuPro, a cosmic warrior of the grid.
 * Tracks identity, progress, and galactic flair—fueling leaderboards, duels, and fan hype with divine precision.
 */
@Entity
@Table(name = "users")
public class User implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(User.class);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The player's real identity — every lookup is {@code findByUsername}, a single-result
     * query. Nothing enforced uniqueness here or in the schema, so two concurrent
     * check-then-insert callers (see {@code EconomyService.walletFor}) could create two
     * rows for one player, after which every read of that player threw
     * {@code IncorrectResultSizeDataAccessException} forever — including login. Flyway V9
     * dedupes and adds the index; this makes Hibernate agree with the database.
     */
    @NotBlank(message = "Username cannot be blank")
    @Column(unique = true)
    private String username;

    @Min(value = 0, message = "Points cannot be negative")
    private int points;

    @Min(value = 0, message = "Streak cannot be negative")
    private int streak;

    /**
     * BCrypt hash for self-registered players; null for legacy/auto-provisioned
     * wallet rows (claimable by the first registration). Never serialized.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String passwordHash;

    /** ELO-style duel rating; everyone starts at 1000. */
    @Min(value = 0, message = "Rating cannot be negative")
    private int duelRating = 1000;

    @Min(value = 0, message = "Duel wins cannot be negative")
    private int duelWins;

    @Min(value = 0, message = "Duel losses cannot be negative")
    private int duelLosses;

    @Min(value = 0, message = "Gems cannot be negative")
    private int gems;

    @NotBlank(message = "Theme preference cannot be blank")
    private String themePreference;

    @NotNull(message = "Last login cannot be null")
    private LocalDateTime lastLogin;

    private String lastLoginIp;
    private String platform;

    @Min(value = 1, message = "Level must be at least 1")
    private int level;

    @Min(value = 0, message = "XP cannot be negative")
    private int xp;

    @ElementCollection
    private Map<String, Boolean> achievements;

    @ElementCollection
    private Map<String, Integer> powerUps;

    @ElementCollection
    private Set<Long> friends;

    @Min(value = 0, message = "Fan count cannot be negative")
    private int fanCount;

    private String avatarUrl;

    @ElementCollection
    private List<MatchRecord> matchHistory;

    @Min(value = 0, message = "Hype meter cannot be negative")
    private int hypeMeter;

    @Min(value = 0, message = "Cosmic drip cannot be negative")
    private int cosmicDrip;

    /**
     * How many times anti-cheat has flagged this account, and when it first and last
     * happened.
     *
     * <p>{@code AntiCheatEngine.flagPlayer} used to halve {@link #cosmicDrip} and return.
     * Nothing recorded that a moderation decision had been taken: the only "flag" was an
     * entry in {@code AntiCheatScheduler.flaggedPlayers}, a plain map inside one bean on one
     * pod, erased by any restart and invisible to every other replica. The penalty was real
     * and recurring (the scheduler re-flags every 60 seconds) while the reason for it was
     * not recorded anywhere a human could read.
     *
     * <p>{@code firstFlaggedAt} is written once and never overwritten — "how long has this
     * been going on" is the question a moderator asks, and a field rewritten on every
     * scheduler pass always answers "about a minute".
     */
    /*
     * The column definition is spelled out rather than left to Hibernate's default mapping,
     * and the reason is a failure the migration tests caught the first time they ran for
     * real. A primitive int maps to `integer not null` with NO database default. On the dev
     * profile, which disables Flyway and lets ddl-auto own the schema, that means: any
     * INSERT not naming this column fails with `null value in column "cheat_flag_count"
     * violates not-null constraint`, and — worse — `ddl-auto=update` cannot add the column
     * to an already-populated dev database at all, because PostgreSQL will not add a NOT
     * NULL column with no default to a table that has rows. Hibernate always names the
     * column on insert, which is exactly why this stays invisible until something else
     * writes a row. The explicit default makes the Hibernate-generated schema agree with
     * what V10 produces.
     */
    @Min(value = 0, message = "Cheat flag count cannot be negative")
    @Column(name = "cheat_flag_count", nullable = false, columnDefinition = "integer not null default 0")
    private int cheatFlagCount;

    private LocalDateTime firstFlaggedAt;

    private LocalDateTime lastFlaggedAt;

    // ObjectMapper is thread-safe after construction; a single shared instance is correct here.
    // It must NOT be injected via @Autowired because JPA creates User instances via its own
    // no-arg reflection path, completely bypassing Spring's constructor injection.
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules(); // picks up JavaTimeModule etc. from the classpath

    /** Required by JPA — do not call directly from application code. */
    protected User() {
        this.achievements = new HashMap<>();
        this.powerUps = new HashMap<>();
        this.friends = Collections.synchronizedSet(new HashSet<>());
        this.matchHistory = Collections.synchronizedList(new ArrayList<>());
        this.themePreference = "default";
        this.lastLogin = LocalDateTime.now();
        this.cosmicDrip = 0;
        initializeAchievements();
        initializePowerUps();
    }

    public User(Long id, String username) {
        this.id = id;
        this.username = Objects.requireNonNull(username, "Username cannot be null");
        this.points = 0;
        this.streak = 0;
        this.duelWins = 0;
        this.duelLosses = 0;
        this.gems = 0;
        this.themePreference = "default";
        this.lastLogin = LocalDateTime.now();
        this.lastLoginIp = "unknown";
        this.platform = "unknown";
        this.level = 1;
        this.xp = 0;
        this.achievements = new HashMap<>();
        this.powerUps = new HashMap<>();
        this.friends = Collections.synchronizedSet(new HashSet<>());
        this.fanCount = 0;
        this.avatarUrl = "default-avatar.png";
        this.matchHistory = Collections.synchronizedList(new ArrayList<>());
        this.hypeMeter = 0;
        this.cosmicDrip = 0;
        initializeAchievements();
        initializePowerUps();
        logger.info("User initialized: id={}, username={}", id, username);
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = Objects.requireNonNull(username, "Username cannot be null"); }
    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = Math.max(0, points); }
    public int getStreak() { return streak; }
    public void setStreak(int streak) { this.streak = Math.max(0, streak); }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public int getDuelRating() { return duelRating; }
    public void setDuelRating(int duelRating) { this.duelRating = Math.max(0, duelRating); }
    public int getDuelWins() { return duelWins; }
    public void setDuelWins(int duelWins) { this.duelWins = Math.max(0, duelWins); }
    public int getDuelLosses() { return duelLosses; }
    public void setDuelLosses(int duelLosses) { this.duelLosses = Math.max(0, duelLosses); }
    public int getGems() { return gems; }
    public void setGems(int gems) { this.gems = Math.max(0, gems); }
    public String getThemePreference() { return themePreference; }
    public void setThemePreference(String themePreference) { this.themePreference = themePreference != null ? themePreference : "default"; }
    public LocalDateTime getLastLogin() { return lastLogin; }
    public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin != null ? lastLogin : LocalDateTime.now(); }
    public String getLastLoginIp() { return lastLoginIp; }
    public void setLastLoginIp(String lastLoginIp) { this.lastLoginIp = lastLoginIp != null ? lastLoginIp : "unknown"; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform != null ? platform : "unknown"; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = Math.max(1, level); }
    public int getXp() { return xp; }
    public void setXp(int xp) { this.xp = Math.max(0, xp); updateLevel(); }
    public Map<String, Boolean> getAchievements() { return new HashMap<>(achievements); }
    public void setAchievements(Map<String, Boolean> achievements) { this.achievements = achievements != null ? new HashMap<>(achievements) : new HashMap<>(); }
    public Map<String, Integer> getPowerUps() { return new HashMap<>(powerUps); }
    public void setPowerUps(Map<String, Integer> powerUps) { this.powerUps = powerUps != null ? new HashMap<>(powerUps) : new HashMap<>(); }
    public Set<Long> getFriends() { return new HashSet<>(friends); }
    public void setFriends(Set<Long> friends) { this.friends = friends != null ? Collections.synchronizedSet(new HashSet<>(friends)) : Collections.synchronizedSet(new HashSet<>()); }
    public int getFanCount() { return fanCount; }
    public void setFanCount(int fanCount) { this.fanCount = Math.max(0, fanCount); }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl != null ? avatarUrl : "default-avatar.png"; }
    public List<MatchRecord> getMatchHistory() { return new ArrayList<>(matchHistory); }
    public void setMatchHistory(List<MatchRecord> matchHistory) { this.matchHistory = matchHistory != null ? Collections.synchronizedList(new ArrayList<>(matchHistory)) : Collections.synchronizedList(new ArrayList<>()); }
    public int getHypeMeter() { return hypeMeter; }
    public void setHypeMeter(int hypeMeter) { this.hypeMeter = Math.max(0, hypeMeter); }
    public int getCosmicDrip() { return cosmicDrip; }
    public void setCosmicDrip(int cosmicDrip) { this.cosmicDrip = Math.max(0, cosmicDrip); }

    public int getCheatFlagCount() { return cheatFlagCount; }
    public void setCheatFlagCount(int cheatFlagCount) { this.cheatFlagCount = Math.max(0, cheatFlagCount); }
    public LocalDateTime getFirstFlaggedAt() { return firstFlaggedAt; }
    public void setFirstFlaggedAt(LocalDateTime firstFlaggedAt) { this.firstFlaggedAt = firstFlaggedAt; }
    public LocalDateTime getLastFlaggedAt() { return lastFlaggedAt; }
    public void setLastFlaggedAt(LocalDateTime lastFlaggedAt) { this.lastFlaggedAt = lastFlaggedAt; }

    /** True when anti-cheat currently holds a flag against this account. */
    public boolean isCheatFlagged() { return cheatFlagCount > 0; }

    /**
     * Records one anti-cheat flag. Keeps the first sighting, advances the last.
     *
     * <p>Lives on the entity rather than in the service so every writer produces the same
     * shape — a count that only accumulates and a first-seen timestamp that never moves.
     */
    public void recordCheatFlag(LocalDateTime when) {
        cheatFlagCount++;
        if (firstFlaggedAt == null) firstFlaggedAt = when;
        lastFlaggedAt = when;
    }

    /** Moderation "this was a false positive": wipes the flag record entirely. */
    public void clearCheatFlags() {
        cheatFlagCount = 0;
        firstFlaggedAt = null;
        lastFlaggedAt = null;
    }

    // Helper Methods
    public void addPoints(int amount) {
        int multiplier = 1 + (streak / 5);
        int newPoints = Math.max(0, this.points + (amount * multiplier));
        logger.debug("Adding {} points (x{} multiplier) to {}: {} -> {}", amount, multiplier, username, points, newPoints);
        this.points = newPoints;
        addXp(amount * multiplier);
        addHype(amount / 10);
    }

    public void addXp(int amount) {
        int newXp = Math.max(0, this.xp + amount);
        logger.debug("Adding {} XP to {}: {} -> {}", amount, username, xp, newXp);
        this.xp = newXp;
        updateLevel();
    }

    public void incrementStreak() {
        this.streak++;
        logger.info("Streak incremented for {}: {}", username, streak);
        addHype(5);
        checkAchievement("StreakMaster", streak >= 10);
    }

    public void resetStreak() {
        logger.info("Streak reset for {}: {} -> 0", username, streak);
        this.streak = 0;
    }

    public void incrementDuelWins() {
        this.duelWins++;
        logger.info("Duel win recorded for {}: {}", username, duelWins);
        addHype(20);
        checkAchievement("DuelChampion", duelWins >= 5);
    }

    public void incrementDuelLosses() {
        this.duelLosses++;
        logger.info("Duel loss recorded for {}: {}", username, duelLosses);
        matchHistory.add(new MatchRecord(LocalDateTime.now(), false));
    }

    public double getDuelWinRate() {
        int totalDuels = duelWins + duelLosses;
        double winRate = totalDuels > 0 ? (double) duelWins / totalDuels : 0.0;
        logger.trace("Duel win rate for {}: {}%", username, String.format("%.2f", winRate * 100));
        return winRate;
    }

    public void addGems(int amount) {
        int newGems = Math.max(0, this.gems + amount);
        logger.debug("Adding {} gems to {}: {} -> {}", amount, username, gems, newGems);
        this.gems = newGems;
    }

    public boolean spendGems(int amount) {
        if (this.gems >= amount) {
            this.gems -= amount;
            logger.info("Spent {} gems by {}: remaining {}", amount, username, gems);
            checkAchievement("GemSpender", gems >= 100);
            return true;
        }
        logger.warn("Insufficient gems for {} to spend {}: current {}", username, amount, gems);
        return false;
    }

    public void updateLastLogin(String ip, String platform) {
        LocalDateTime now = LocalDateTime.now();
        logger.info("Updating last login for {}: IP={}, Platform={}", username, ip, platform);
        this.lastLogin = now;
        this.lastLoginIp = ip != null ? ip : "unknown";
        this.platform = platform != null ? platform : "unknown";
        applyDailyBonus();
    }

    public boolean isEligibleForDailyBonus() {
        boolean eligible = lastLogin.isBefore(LocalDateTime.now().minusDays(1));
        logger.trace("Daily bonus eligibility for {}: {}", username, eligible);
        return eligible;
    }

    public void applyDailyBonus() {
        if (isEligibleForDailyBonus()) {
            int bonusGems = 10 + (level / 5);
            int bonusXp = 50 + (level * 5);
            int bonusDrip = level / 10;
            addGems(bonusGems);
            addXp(bonusXp);
            addHype(10);
            addCosmicDrip(bonusDrip);
            logger.info("Applied daily bonus for {}: Gems={}, XP={}, Drip={}", username, bonusGems, bonusXp, bonusDrip);
            checkAchievement("DailyPlayer", true);
        }
    }

    private void updateLevel() {
        int newLevel = 1 + (xp / 100);
        if (newLevel > level) {
            logger.info("Level up for {}: {} -> {}", username, level, newLevel);
            this.level = newLevel;
            addHype(50);
            addCosmicDrip(5);
            checkAchievement("LevelUp", level >= 5);
        }
    }

    private void initializeAchievements() {
        achievements.put("StreakMaster", false); // 10+ streak
        achievements.put("DuelChampion", false); // 5+ duel wins
        achievements.put("GemSpender", false);  // 100+ gems spent
        achievements.put("DailyPlayer", false); // First daily bonus
        achievements.put("LevelUp", false);     // Reach level 5
        achievements.put("FanFavorite", false); // 50+ fans
        achievements.put("HypeLord", false);    // 100+ hype
        achievements.put("CosmicDripper", false); // 50+ cosmic drip
    }

    private void initializePowerUps() {
        powerUps.put("hint", 0);
        powerUps.put("undo", 0);
        powerUps.put("timeBoost", 0);
        powerUps.put("cosmicReveal", 0);
    }

    /** The reward every achievement unlock pays, whichever path unlocks it. */
    public static final int ACHIEVEMENT_GEMS = 20;
    public static final int ACHIEVEMENT_XP   = 100;
    public static final int ACHIEVEMENT_HYPE = 30;
    public static final int ACHIEVEMENT_DRIP = 10;

    public void checkAchievement(String name, boolean condition) {
        // Guard on "not already TRUE" rather than containsKey: the containsKey form
        // silently refused any name initializeAchievements didn't seed, which made
        // CleanSolver, SpeedDemon and PowerUpCollector permanently un-unlockable through
        // this path (addPowerUp has called checkAchievement("PowerUpCollector", ...)
        // forever, against a map that never contains that key). It also disagreed with
        // AchievementService.check, which uses the TRUE-equals form — the two paths must
        // share one suppression rule, because each one skipping names the other already
        // unlocked is what keeps the reward from ever being paid twice.
        if (condition && !Boolean.TRUE.equals(achievements.get(name))) {
            achievements.put(name, true);
            addGems(ACHIEVEMENT_GEMS);
            addXp(ACHIEVEMENT_XP);
            addHype(ACHIEVEMENT_HYPE);
            addCosmicDrip(ACHIEVEMENT_DRIP);
            logger.info("Achievement unlocked for {}: {}", username, name);
        }
    }

    public Map<String, Boolean> getAchievementProgress() {
        return new HashMap<>(achievements);
    }

    public boolean isEligibleForRankedMode() {
        boolean eligible = level >= 3 && duelWins >= 3;
        logger.trace("Ranked mode eligibility for {}: {}", username, eligible);
        return eligible;
    }

    public void addPowerUp(String name, int quantity) {
        powerUps.merge(name, quantity, Integer::sum);
        logger.debug("Added {} {} power-ups to {}: total {}", quantity, name, username, powerUps.get(name));
        checkAchievement("PowerUpCollector", powerUps.values().stream().mapToInt(Integer::intValue).sum() >= 10);
    }

    public boolean usePowerUp(String name) {
        Integer count = powerUps.getOrDefault(name, 0);
        if (count > 0) {
            powerUps.put(name, count - 1);
            if ("cosmicReveal".equals(name)) addCosmicDrip(5);
            logger.info("Used power-up {} by {}: remaining {}", name, username, powerUps.get(name));
            return true;
        }
        logger.warn("No {} power-ups available for {}", name, username);
        return false;
    }

    public void addFriend(Long friendId) {
        if (friendId != null && friends.add(friendId)) {
            logger.debug("Added friend {} to {}", friendId, username);
        }
    }

    public void removeFriend(Long friendId) {
        if (friendId != null && friends.remove(friendId)) {
            logger.debug("Removed friend {} from {}", friendId, username);
        }
    }

    public void addFan() {
        this.fanCount++;
        addHype(5);
        logger.info("Fan added for {}: total {}", username, fanCount);
        checkAchievement("FanFavorite", fanCount >= 50);
    }

    public void removeFan() {
        this.fanCount = Math.max(0, fanCount - 1);
        logger.debug("Fan removed for {}: total {}", username, fanCount);
    }

    public void recordMatch(boolean won) {
        MatchRecord record = new MatchRecord(LocalDateTime.now(), won);
        matchHistory.add(record);
        if (won) incrementDuelWins();
        else incrementDuelLosses();
        logger.info("Match recorded for {}: {}", username, won ? "Win" : "Loss");
        trimMatchHistory(100); // Cap history to prevent bloat
    }

    private void trimMatchHistory(int maxSize) {
        if (matchHistory.size() > maxSize) {
            matchHistory.subList(0, matchHistory.size() - maxSize).clear();
            logger.debug("Trimmed match history for {} to {} entries", username, maxSize);
        }
    }

    public void addHype(int amount) {
        this.hypeMeter = Math.max(0, this.hypeMeter + amount);
        logger.debug("Added {} hype to {}: total {}", amount, username, hypeMeter);
        checkAchievement("HypeLord", hypeMeter >= 100);
    }

    public void addCosmicDrip(int amount) {
        this.cosmicDrip = Math.max(0, this.cosmicDrip + amount);
        logger.debug("Added {} cosmic drip to {}: total {}", amount, username, cosmicDrip);
        checkAchievement("CosmicDripper", cosmicDrip >= 50);
    }

    public String getPlayerStatsSummary() {
        String summary = String.format("Player: %s | Level: %d | Points: %d | Streak: %d | Duel W/L: %d/%d (%.2f%%) | Gems: %d | Fans: %d | Hype: %d | Drip: %d | Achievements: %d/%d | Power-Ups: %d",
            username, level, points, streak, duelWins, duelLosses, getDuelWinRate() * 100,
            gems, fanCount, hypeMeter, cosmicDrip, achievements.values().stream().filter(Boolean::booleanValue).count(), achievements.size(),
            powerUps.values().stream().mapToInt(Integer::intValue).sum());
        logger.trace("Generated stats summary for {}: {}", username, summary);
        return summary;
    }

    public synchronized String exportPlayerProfile() {
        try {
            Map<String, Object> profile = new HashMap<>();
            profile.put("id", id);
            profile.put("username", username);
            profile.put("points", points);
            profile.put("streak", streak);
            profile.put("duelWins", duelWins);
            profile.put("duelLosses", duelLosses);
            profile.put("gems", gems);
            profile.put("themePreference", themePreference);
            profile.put("lastLogin", lastLogin.toString());
            // lastLoginIp intentionally omitted — PII, must not be exposed in profile exports
            profile.put("platform", platform);
            profile.put("level", level);
            profile.put("xp", xp);
            profile.put("achievements", achievements);
            profile.put("powerUps", powerUps);
            profile.put("friends", friends);
            profile.put("fanCount", fanCount);
            profile.put("avatarUrl", avatarUrl);
            profile.put("matchHistory", matchHistory.stream().map(MatchRecord::toMap).collect(Collectors.toList()));
            profile.put("hypeMeter", hypeMeter);
            profile.put("cosmicDrip", cosmicDrip);
            profile.put("version", "1.0"); // Add versioning
            String json = MAPPER.writeValueAsString(profile);
            logger.debug("Exported player profile for {}: {} bytes", username, json.length());
            return json;
        } catch (Exception e) {
            logger.error("Failed to export profile for {}: {}", username, e.getMessage());
            throw new RuntimeException("Profile export failed", e);
        }
    }

    @Override
    public String toString() {
        return String.format("User[id=%d, username=%s, points=%d, streak=%d, duelWins=%d, duelLosses=%d, gems=%d, level=%d, xp=%d, fans=%d, hype=%d, drip=%d]", 
            id, username, points, streak, duelWins, duelLosses, gems, level, xp, fanCount, hypeMeter, cosmicDrip);
    }
}
