package com.xai.sudokupro.engine;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FateEntityManager {
    private static final Logger logger = LoggerFactory.getLogger(FateEntityManager.class);

    // ✅ FIX: missing entropy threshold
    private static final long ENTROPY_THRESHOLD = 1024L;

    private final ChaosEngine chaosEngine;
    private final GameService gameService;
    private final SecureRandomGenerator rng;
    private final AISolverService aiSolverService;
    private final MultiplayerBroadcaster multiplayerBroadcaster;
    private final MemoryBank memoryBank;

    private final List<FateEntity> entities = new ArrayList<>();
    private final Map<String, Integer> playerStreaks = new ConcurrentHashMap<>();
    private final Map<String, Integer> playerFails = new ConcurrentHashMap<>();

    @Autowired
    public FateEntityManager(ChaosEngine chaosEngine, GameService gameService, SecureRandomGenerator rng,
                             AISolverService aiSolverService, MultiplayerBroadcaster multiplayerBroadcaster,
                             MemoryBank memoryBank) {
        this.chaosEngine = chaosEngine;
        this.gameService = gameService;
        this.rng = rng;
        this.aiSolverService = aiSolverService;
        this.multiplayerBroadcaster = multiplayerBroadcaster;
        this.memoryBank = memoryBank;

        registerEntities();
        logger.info("FateEntityManager initialized with {} entities", entities.size());
    }

    public void evaluateAndTrigger(String playerId, SudokuBoard board) {
        for (FateEntity entity : entities) {
            if (entity.shouldTrigger(playerId, board)) {
                entity.trigger(playerId, board);
                logger.info("Entity {} triggered for {}", entity.getName(), playerId);
            }
        }
    }

    private void registerEntities() {
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
    }

    private void speak(String message) {
        System.out.println("🗣️  [FATE ENTITY]: " + message);
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
            String move = aiSolverService.getNextLogicalMove(b);
            speak("AIDoubter questions: " + move);
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
        playerFails.put(playerId, playerFails.getOrDefault(playerId, 0) + 1);
    }

    public void recordPlayerStreak(String playerId) {
        playerStreaks.put(playerId, playerStreaks.getOrDefault(playerId, 0) + 1);
    }

    public void resetPlayerStreak(String playerId) {
        playerStreaks.put(playerId, 0);
    }
}
