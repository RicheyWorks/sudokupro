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

    // (ALL ENTITY CLASSES REMAIN UNCHANGED — preserved exactly)

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
