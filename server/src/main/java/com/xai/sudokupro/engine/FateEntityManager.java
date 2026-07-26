package com.xai.sudokupro.engine;

import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.service.AISolverService;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.util.MemoryBank;
import com.xai.sudokupro.util.SecureRandomGenerator;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flavour layer: a bestiary of "fate entities" that may comment on, or nudge, a game in
 * progress. Nothing here is authoritative — but it runs inside the caller's request, so a
 * misbehaving entity must never be able to fail that request.
 */
@Component
public class FateEntityManager {
    private static final Logger logger = LoggerFactory.getLogger(FateEntityManager.class);

    // ✅ FIX: missing entropy threshold
    private static final long ENTROPY_THRESHOLD = 1024L;

    /**
     * Ceiling on the per-player tracking maps. Their keys are caller-supplied player ids
     * and nothing ever removed an entry, so the two maps grew for the lifetime of the JVM
     * with every id that had ever been seen. Same cap, and same reasoning, as
     * {@code ChaosEngine.MAX_TRACKED_PLAYERS}.
     */
    public static final int MAX_TRACKED_PLAYERS = 10_000;

    private final ChaosEngine chaosEngine;
    private final GameService gameService;
    private final SecureRandomGenerator rng;
    private final AISolverService aiSolverService;
    private final MultiplayerBroadcaster multiplayerBroadcaster;
    private final MemoryBank memoryBank;

    private final List<FateEntity> entities;
    private final Map<String, Integer> playerStreaks = new ConcurrentHashMap<>();
    private final Map<String, Integer> playerFails = new ConcurrentHashMap<>();

    @Autowired
    public FateEntityManager(ChaosEngine chaosEngine, GameService gameService, SecureRandomGenerator rng,
                             AISolverService aiSolverService, MultiplayerBroadcaster multiplayerBroadcaster,
                             MemoryBank memoryBank) {
        // Fail at wiring time rather than with an NPE from inside a player's move.
        this.chaosEngine = Objects.requireNonNull(chaosEngine, "chaosEngine");
        this.gameService = Objects.requireNonNull(gameService, "gameService");
        this.rng = Objects.requireNonNull(rng, "rng");
        this.aiSolverService = Objects.requireNonNull(aiSolverService, "aiSolverService");
        this.multiplayerBroadcaster = Objects.requireNonNull(multiplayerBroadcaster, "multiplayerBroadcaster");
        this.memoryBank = Objects.requireNonNull(memoryBank, "memoryBank");

        this.entities = Collections.unmodifiableList(registerEntities());
        logger.info("FateEntityManager initialized with {} entities", entities.size());
    }

    /**
     * Rolls every entity against the current game.
     *
     * <p>Each entity is isolated. The loop used to let any failure escape: one entity
     * throwing (AIDoubter calls into the solver, EntropyDealer into the chaos engine)
     * skipped every entity after it AND propagated out of a decorative subsystem into the
     * player's move, turning a cosmetic feature into a failed request.
     */
    public void evaluateAndTrigger(String playerId, SudokuBoard board) {
        if (board == null) {
            logger.debug("evaluateAndTrigger called with no board for {}; nothing to judge", playerId);
            return;
        }
        for (FateEntity entity : entities) {
            try {
                if (entity.shouldTrigger(playerId, board)) {
                    entity.trigger(playerId, board);
                    logger.info("Entity {} triggered for {}", entity.getName(), playerId);
                }
            } catch (RuntimeException e) {
                logger.warn("Fate entity {} failed for player {}: {}",
                    entity.getName(), playerId, e.toString());
            }
        }
    }

    private List<FateEntity> registerEntities() {
        List<FateEntity> entities = new ArrayList<>();
        entities.add(new RedJester());
        entities.add(new DivineOverflow());
        entities.add(new CrashWarden());
        entities.add(new SystemPriest());
        entities.add(new RedGlitchKing());
        entities.add(new ChaosBard());
        entities.add(new BacktrackSaint());

        entities.add(new GlitchProphet());
        entities.add(new VoidBishop());
        entities.add(new MemoryBleeder());
        entities.add(new TheMutator());
        entities.add(new CosmicTaxer());
        entities.add(new ThreadPhantom());
        entities.add(new AIDoubter());

        entities.add(new GlitchTrickster());
        entities.add(new EntropyDealer());
        entities.add(new LuckInverter());
        entities.add(new DeadlockMonk());
        return entities;
    }

    /**
     * Entity chatter goes to the log, not to {@code System.out}: this runs on a request
     * thread inside a server, where unbuffered stdout writes are both a global monitor and
     * output nobody collects.
     */
    private void speak(String message) {
        logger.info("[FATE ENTITY]: {}", message);
    }

    private abstract class FateEntity {
        protected abstract String getName();
        protected abstract boolean shouldTrigger(String playerId, SudokuBoard board);
        protected abstract void trigger(String playerId, SudokuBoard board);
    }

    // ── Entity Implementations ──────────────────────────────────────────────────

    private class RedJester extends FateEntity {
        protected String getName() { return "RedJester"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.05); }
        protected void trigger(String p, SudokuBoard b) { speak("The Red Jester laughs at your misfortune."); }
    }

    private class DivineOverflow extends FateEntity {
        protected String getName() { return "DivineOverflow"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.03); }
        protected void trigger(String p, SudokuBoard b) { speak("DivineOverflow floods the grid!"); }
    }

    private class CrashWarden extends FateEntity {
        protected String getName() { return "CrashWarden"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.04); }
        protected void trigger(String p, SudokuBoard b) { speak("CrashWarden holds the line."); }
    }

    private class SystemPriest extends FateEntity {
        protected String getName() { return "SystemPriest"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.04); }
        protected void trigger(String p, SudokuBoard b) { speak("SystemPriest blesses your path."); }
    }

    private class RedGlitchKing extends FateEntity {
        protected String getName() { return "RedGlitchKing"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.02); }
        protected void trigger(String p, SudokuBoard b) { speak("The Red Glitch King reigns!"); }
    }

    private class ChaosBard extends FateEntity {
        protected String getName() { return "ChaosBard"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.06); }
        protected void trigger(String p, SudokuBoard b) { speak("ChaosBard sings a song of disorder."); }
    }

    private class BacktrackSaint extends FateEntity {
        protected String getName() { return "BacktrackSaint"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.05); }
        protected void trigger(String p, SudokuBoard b) { speak("BacktrackSaint guides you backwards."); }
    }

    private class GlitchProphet extends FateEntity {
        protected String getName() { return "GlitchProphet"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.03); }
        protected void trigger(String p, SudokuBoard b) { speak("GlitchProphet foresees a crash."); }
    }

    private class VoidBishop extends FateEntity {
        protected String getName() { return "VoidBishop"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.04); }
        protected void trigger(String p, SudokuBoard b) { speak("VoidBishop moves through the void."); }
    }

    private class MemoryBleeder extends FateEntity {
        protected String getName() { return "MemoryBleeder"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) {
            long heapFree = Runtime.getRuntime().freeMemory();
            return heapFree < ENTROPY_THRESHOLD * 1024;
        }
        protected void trigger(String p, SudokuBoard b) { speak("MemoryBleeder drains the heap!"); }
    }

    private class TheMutator extends FateEntity {
        protected String getName() { return "TheMutator"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.04); }
        protected void trigger(String p, SudokuBoard b) { speak("The Mutator shifts reality."); }
    }

    private class CosmicTaxer extends FateEntity {
        protected String getName() { return "CosmicTaxer"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.05); }
        protected void trigger(String p, SudokuBoard b) { speak("CosmicTaxer collects the cosmic toll."); }
    }

    private class ThreadPhantom extends FateEntity {
        protected String getName() { return "ThreadPhantom"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) {
            return ManagementFactory.getThreadMXBean().getThreadCount() > 100;
        }
        protected void trigger(String p, SudokuBoard b) { speak("ThreadPhantom haunts the executor!"); }
    }

    private class AIDoubter extends FateEntity {
        protected String getName() { return "AIDoubter"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.03); }
        protected void trigger(String p, SudokuBoard b) {
            // getNextLogicalMove() BILLS the board for a hint (board.incrementHintCount()).
            // Nobody asked this entity for help: hintCount is exactly what disqualifies a
            // perfect clear (isPerfectClear()) and what the hint analytics count, so a 3%
            // flavour roll silently cost the player their flawless-solve bonus. The
            // EnhancedMove variant answers the same question without charging for it.
            EnhancedMove move = aiSolverService.getNextLogicalMoveAsEnhancedMove(b);
            speak(move == null
                ? "AIDoubter questions whether this board has an answer at all."
                : String.format("AIDoubter questions: %d at row %d, col %d",
                    move.newVal(), move.row() + 1, move.col() + 1));
        }
    }

    private class GlitchTrickster extends FateEntity {
        protected String getName() { return "GlitchTrickster"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.05); }
        protected void trigger(String p, SudokuBoard b) { speak("GlitchTrickster plays a trick!"); }
    }

    private class EntropyDealer extends FateEntity {
        protected String getName() { return "EntropyDealer"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.04); }
        protected void trigger(String p, SudokuBoard b) { chaosEngine.boostEntropy(UUID.randomUUID().toString().getBytes()); }
    }

    private class LuckInverter extends FateEntity {
        protected String getName() { return "LuckInverter"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) { return rng.chance(0.04); }
        protected void trigger(String p, SudokuBoard b) { speak("LuckInverter flips your fortune!"); }
    }

    private class DeadlockMonk extends FateEntity {
        protected String getName() { return "DeadlockMonk"; }
        protected boolean shouldTrigger(String p, SudokuBoard b) {
            return ManagementFactory.getThreadMXBean().findDeadlockedThreads() != null;
        }
        protected void trigger(String p, SudokuBoard b) { speak("DeadlockMonk meditates on your deadlock..."); }
    }

    public void recordPlayerFail(String playerId) {
        increment(playerFails, playerId);
    }

    public void recordPlayerStreak(String playerId) {
        increment(playerStreaks, playerId);
    }

    /**
     * Clears a player's streak. Removes the entry rather than storing a zero: a reset for
     * an id that was never tracked used to create a permanent entry, so this method alone
     * could grow the map without bound. Readers use {@link #getPlayerStreak}, for which
     * "absent" and "zero" are the same answer.
     */
    public void resetPlayerStreak(String playerId) {
        if (playerId == null) return;
        playerStreaks.remove(playerId);
    }

    public int getPlayerStreak(String playerId) {
        return playerId == null ? 0 : playerStreaks.getOrDefault(playerId, 0);
    }

    public int getPlayerFails(String playerId) {
        return playerId == null ? 0 : playerFails.getOrDefault(playerId, 0);
    }

    public int trackedStreakCount() { return playerStreaks.size(); }

    public int trackedFailCount()   { return playerFails.size(); }

    /**
     * Atomic +1 for {@code playerId}, bounded by {@link #MAX_TRACKED_PLAYERS}.
     *
     * <p>Both counters used to be {@code map.put(id, map.getOrDefault(id, 0) + 1)}. Read,
     * add, write is not atomic however thread-safe the map is: two concurrent losses or
     * wins on the same player interleave and one of them vanishes. These are recorded from
     * request threads, so concurrent updates for one player are the normal case, not a
     * corner case.
     */
    private static void increment(Map<String, Integer> counters, String playerId) {
        if (playerId == null || playerId.isBlank()) return;
        if (counters.size() >= MAX_TRACKED_PLAYERS && !counters.containsKey(playerId)) {
            logger.debug("Fate tracking is full ({} players); not tracking {}",
                MAX_TRACKED_PLAYERS, playerId);
            return;
        }
        counters.merge(playerId, 1, Integer::sum);
    }
}
