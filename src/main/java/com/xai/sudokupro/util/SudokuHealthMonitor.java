package com.xai.sudokupro.util;

import com.xai.sudokupro.engine.ChaosEngine;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.AISolverService;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;
import com.xai.sudokupro.model.SudokuBoard;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
public class SudokuHealthMonitor {
    private static final Logger logger = LoggerFactory.getLogger(SudokuHealthMonitor.class);
    private static final int ENTROPY_THRESHOLD = 1000;
    private static final int REDIS_TIMEOUT_MS = 5000;
    private static final int NETWORK_TIMEOUT_MS = 3000;
    private static final int MAX_FAILED_CHECKS = 5;
    private static final long DISK_SPACE_THRESHOLD_MB = 500;
    private static final int JVM_COMPILATION_THRESHOLD = 1000;

    private final MeterRegistry meterRegistry;
    private final UserRepository userRepo;
    private final GameRepository gameRepo;
    private final ChaosEngine chaosEngine;
    private final GameService gameService;
    private final SecureRandomGenerator rng;
    private final AISolverService aiSolverService;
    private final MultiplayerBroadcaster multiplayerBroadcaster;

    // Optional — only present when the "redis" profile is active.
    @Autowired(required = false)
    private JedisPool jedisPool;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    private final AICore aiCore = new AICore();
    private final MemoryBank memoryBank = new MemoryBank();
    private final Recursionverse recursionverse = new Recursionverse();
    private final FateEngine fateEngine = new FateEngine();

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger failedChecks = new AtomicInteger(0);
    private final AtomicInteger aiSolveAttempts = new AtomicInteger(0);
    private final AtomicInteger cosmicEvents = new AtomicInteger(0);
    private final Map<String, Long> playerPingTimes = new ConcurrentHashMap<>();
    private final Map<String, Integer> gameDifficultyStats = new ConcurrentHashMap<>();
    private final Map<String, Long> systemUptime = new ConcurrentHashMap<>();

    @Autowired
    public SudokuHealthMonitor(MeterRegistry meterRegistry, UserRepository userRepo,
                               GameRepository gameRepo,
                               ChaosEngine chaosEngine, GameService gameService, SecureRandomGenerator rng,
                               AISolverService aiSolverService, MultiplayerBroadcaster multiplayerBroadcaster) {
        this.meterRegistry = meterRegistry;
        this.userRepo = userRepo;
        this.gameRepo = gameRepo;
        this.chaosEngine = chaosEngine;
        this.gameService = gameService;
        this.rng = rng;
        this.aiSolverService = aiSolverService;
        this.multiplayerBroadcaster = multiplayerBroadcaster;
        logger.info("SudokuHealthMonitor initialized with Fate Engine and Echo Trials");

        // Preload BIGDAWGS echoes
        initializeBigDawgsEchoes();
    }

    public void runChecks() {
        checkRedisHealth();
        checkDatabaseHealth();
        checkEntropyHealth();
        checkGameServiceHealth();
        checkChaosEngineHealth();
        checkSystemLoad();
        checkNetworkHealth();
        checkAISolverHealth();
        checkWebSocketHealth();
        checkPlayerActivity();
        checkMemoryUsage();
        checkThreadHealth();
        checkDiskHealth();
        checkJvmHealth();
        checkCosmicEventHealth();
        checkRedisCacheConsistency();
        checkGameBoardIntegrity();
        checkPlayerLuckDistribution();
        checkSystemUptime();
        checkNetworkBandwidth();
        checkSecurityHealth();
        enforceLuckTaxation();
        checkAISolverProphecyAlignment();
        detectEntropySinkholes();
        detectCloudInfiltration();
        monitorPlayerBrainprints();
        applyDynamicRealityShifts();
        updatePlayerKarma();
        detectUniverseDivergence();
        integrateBioSignalsIfAvailable();
        monitorPlayerBehaviorForSentience();
        manageMultiverseTimelines();
        updateFateSignatures();
        logHealthSummary();
    }

    private void checkRedisHealth() {
        try (var jedis = jedisPool.getResource()) {
            long start = System.currentTimeMillis();
            jedis.ping();
            long latency = System.currentTimeMillis() - start;

            // Fix: cache each INFO response so we only make one round-trip per field,
            // and parse with individual try/catch so a malformed value doesn't trigger
            // the outer catch and record a false health failure.
            String memInfo = jedis.info("memory");
            long usedMemory = -1;
            if (memInfo.contains("used_memory:")) {
                try {
                    usedMemory = Long.parseLong(
                        memInfo.split("used_memory:")[1].split("\n")[0].trim()) / 1024;
                } catch (NumberFormatException ex) {
                    logger.warn("Could not parse Redis used_memory: {}", ex.getMessage());
                }
            }

            String clientInfo = jedis.info("clients");
            int clients = -1;
            if (clientInfo.contains("connected_clients:")) {
                try {
                    clients = Integer.parseInt(
                        clientInfo.split("connected_clients:")[1].split("\n")[0].trim());
                } catch (NumberFormatException ex) {
                    logger.warn("Could not parse Redis connected_clients: {}", ex.getMessage());
                }
            }
            logger.info("Redis health: OK - Latency: {}ms, UsedMemory: {}KB, Clients: {}", latency, usedMemory, clients);
            meterRegistry.gauge("sudokupro.redis.health", 1);
            meterRegistry.gauge("sudokupro.redis.latency_ms", latency);
            meterRegistry.gauge("sudokupro.redis.used_memory_kb", usedMemory);
            meterRegistry.gauge("sudokupro.redis.connected_clients", clients);
            activeConnections.incrementAndGet();
            speak("The red veins pulse strong...");
        } catch (Exception e) {
            logger.error("Redis health: DOWN - {}", e.getMessage(), e);
            meterRegistry.gauge("sudokupro.redis.health", 0);
            failedChecks.incrementAndGet();
            chaosEngine.boostEntropy(("RedisDown-" + System.nanoTime()).getBytes());
            memoryBank.recordEvent("system", "Redis failure", -0.2);
            speak("The red veins falter... Redis weakens.");
        }
    }

    private void checkDatabaseHealth() {
        try {
            long start = System.currentTimeMillis();
            long userCount = userRepo.count();
            long gameCount = gameRepo.count();
            long latency = System.currentTimeMillis() - start;
            long dbConnections = -1; // not exposed via JPA; monitored externally
            long queryRate     = -1; // not exposed via JPA; monitored externally
            logger.info("Database health: OK - Users: {}, Active Games: {}, Latency: {}ms",
                userCount, gameCount, latency);
            meterRegistry.gauge("sudokupro.db.users", userCount);
            meterRegistry.gauge("sudokupro.db.active_games", gameCount);
            meterRegistry.gauge("sudokupro.db.latency_ms", latency);
            if (dbConnections > 100) {
                logger.warn("High DB connections: {} - Triggering chaos", dbConnections);
                chaosEngine.onGameEvent("DB_OVERLOAD", "system");
                memoryBank.recordEvent("system", "DB overload", -0.1);
                speak("Too many souls strain the vault... overload.");
            }
            speak("The vault of fates holds firm...");
        } catch (Exception e) {
            logger.error("Database health: DOWN - {}", e.getMessage(), e);
            meterRegistry.gauge("sudokupro.db.health", 0);
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "Database failure", -0.2);
            speak("The vault crumbles... fates lost.");
        }
    }

    private void checkEntropyHealth() {
        int entropyBits = rng.getSystemEntropyBits();
        if (entropyBits >= 0) {
            logger.info("System entropy: {} bits", entropyBits);
            meterRegistry.gauge("sudokupro.rng.entropy_bits", entropyBits);
            if (entropyBits < ENTROPY_THRESHOLD) {
                logger.warn("Low entropy detected: {} bits - Boosting RNG", entropyBits);
                rng.boostEntropy(("LowEntropy-" + System.nanoTime()).getBytes());
                failedChecks.incrementAndGet();
                memoryBank.recordEvent("system", "Low entropy", -0.1);
                speak("Chaos wanes... I ignite the void.");
            }
        } else {
            logger.warn("Entropy check unavailable");
            meterRegistry.gauge("sudokupro.rng.entropy_bits", -1);
            speak("The entropy pulse is silent... a shadow falls.");
        }
    }

    private void checkGameServiceHealth() {
        try {
            int activeGames = gameService.getActiveGamesCount();
            Set<?> gameKeys = redisTemplate.keys("sudoku:game:*");
            long redisKeys = gameKeys != null ? gameKeys.size() : 0;
            long orphanedGames = redisKeys - activeGames;
            logger.info("GameService health: OK - Active Games: {}, Redis Keys: {}, Orphaned: {}", activeGames, redisKeys, orphanedGames);
            meterRegistry.gauge("sudokupro.games.active", activeGames);
            meterRegistry.gauge("sudokupro.games.redis_keys", redisKeys);
            meterRegistry.gauge("sudokupro.games.orphaned", orphanedGames);
            if (orphanedGames > 10) {
                logger.warn("Orphaned games detected: {} - Triggering cleanup", orphanedGames);
                chaosEngine.onGameEvent("GAME_ORPHAN", "system");
                failedChecks.incrementAndGet();
                memoryBank.recordEvent("system", "Orphaned games", -0.1);
                speak("Lost threads drift in the ether... I purge them.");
            }
            speak("The game weave holds... " + activeGames + " fates spin.");
        } catch (Exception e) {
            logger.error("GameService health: DOWN - {}", e.getMessage(), e);
            meterRegistry.gauge("sudokupro.games.health", 0);
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "Game service failure", -0.2);
            speak("The game weave unravels... service lost.");
        }
    }

    private void checkChaosEngineHealth() {
        try {
            String testPlayer = "health-check-" + UUID.randomUUID().toString();
            double reactionTime = chaosEngine.simulateReactionTime(testPlayer);
            boolean clutch = chaosEngine.isClutchMoment(testPlayer);
            int fatigue = chaosEngine.simulateFatiguePenalty(testPlayer);
            double luck = chaosEngine.getPlayerLuck(testPlayer);
            logger.info("ChaosEngine health: OK - ReactionTime: {}s, Clutch: {}, Fatigue: {}, Luck: {}", reactionTime, clutch, fatigue, luck);
            meterRegistry.gauge("sudokupro.chaos.reaction_time", reactionTime);
            meterRegistry.gauge("sudokupro.chaos.clutch_events", clutch ? 1 : 0);
            meterRegistry.gauge("sudokupro.chaos.fatigue_penalty", fatigue);
            meterRegistry.gauge("sudokupro.chaos.luck_factor", luck);
            chaosEngine.resetPlayerState(testPlayer);
            speak("The chaos heart beats strong... entropy flows.");
        } catch (Exception e) {
            logger.error("ChaosEngine health: DOWN - {}", e.getMessage(), e);
            meterRegistry.gauge("sudokupro.chaos.health", 0);
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "Chaos engine failure", -0.2);
            speak("The chaos heart stutters... darkness creeps.");
        }
    }

    private void checkSystemLoad() {
        double loadAverage = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        int processors = Runtime.getRuntime().availableProcessors();
        long freeMemory = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long totalMemory = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        double cpuUsage = loadAverage / processors * 100;
        logger.info("System load: LoadAvg: {}, Processors: {}, CPU Usage: {}%, FreeMemory: {}MB, TotalMemory: {}MB",
            loadAverage, processors, cpuUsage, freeMemory, totalMemory);
        meterRegistry.gauge("sudokupro.system.load_average", loadAverage);
        meterRegistry.gauge("sudokupro.system.cpu_usage_percent", cpuUsage);
        meterRegistry.gauge("sudokupro.system.free_memory_mb", freeMemory);
        meterRegistry.gauge("sudokupro.system.total_memory_mb", totalMemory);
        if (cpuUsage > 80 || freeMemory < totalMemory * 0.1) {
            logger.warn("High CPU or low memory detected - CPU: {}%, Free: {}MB", cpuUsage, freeMemory);
            chaosEngine.onGameEvent("SYSTEM_OVERLOAD", "system");
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "System overload", -0.1);
            speak("The machine groans under its burden... overload stirs.");
        }
        speak("The system’s pulse quickens... " + cpuUsage + "% strain.");
    }

    private void checkNetworkHealth() {
        try {
            long start = System.currentTimeMillis();
            InetAddress.getByName("google.com").isReachable(NETWORK_TIMEOUT_MS);
            long latency = System.currentTimeMillis() - start;
            String localIp = InetAddress.getLocalHost().getHostAddress();
            logger.info("Network health: OK - Latency to google.com: {}ms, Local IP: {}", latency, localIp);
            meterRegistry.gauge("sudokupro.network.health", 1);
            meterRegistry.gauge("sudokupro.network.latency_ms", latency);
            speak("The network threads hum steady...");
        } catch (Exception e) {
            logger.error("Network health: DOWN - {}", e.getMessage(), e);
            meterRegistry.gauge("sudokupro.network.health", 0);
            failedChecks.incrementAndGet();
            chaosEngine.boostEntropy(("NetworkDown-" + System.nanoTime()).getBytes());
            memoryBank.recordEvent("system", "Network failure", -0.2);
            speak("The cosmic web frays... network lost.");
        }
    }

    private void checkAISolverHealth() {
        try {
            long start = System.currentTimeMillis();
            String hint = aiSolverService.getNextLogicalMoveForTestBoard();
            long latency = System.currentTimeMillis() - start;
            int complexity = hint != null ? hint.length() : 0;
            aiSolveAttempts.incrementAndGet();
            logger.info("AISolver health: OK - Hint: {}, Latency: {}ms, Complexity: {}, Attempts: {}", hint, latency, complexity, aiSolveAttempts.get());
            meterRegistry.gauge("sudokupro.ai.health", 1);
            meterRegistry.gauge("sudokupro.ai.latency_ms", latency);
            meterRegistry.gauge("sudokupro.ai.hint_complexity", complexity);
            meterRegistry.gauge("sudokupro.ai.solve_attempts", aiSolveAttempts.get());
            if (latency > 1000) {
                logger.warn("Slow AI solver detected: {}ms - Triggering chaos", latency);
                chaosEngine.onGameEvent("AI_SLOW", "system");
                memoryBank.recordEvent("system", "AI solver slowed", -0.1);
                speak("The solver’s mind lags... too slow.");
            }
            speak("The solver’s wisdom guides us...");
        } catch (Exception e) {
            logger.error("AISolver health: DOWN - {}", e.getMessage(), e);
            meterRegistry.gauge("sudokupro.ai.health", 0);
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "AI solver failure", -0.2);
            speak("The solver’s wisdom fades... lost.");
        }
    }

    private void checkWebSocketHealth() {
        try {
            long start = System.currentTimeMillis();
            multiplayerBroadcaster.broadcastHealthPing();
            long latency = System.currentTimeMillis() - start;
            int activeClients = multiplayerBroadcaster.getActiveClientCount();
            int messageRate = multiplayerBroadcaster.getMessageRatePerSecond();
            logger.info("WebSocket health: OK - Latency: {}ms, Active Clients: {}, MessageRate: {}/s", latency, activeClients, messageRate);
            meterRegistry.gauge("sudokupro.websocket.health", 1);
            meterRegistry.gauge("sudokupro.websocket.latency_ms", latency);
            meterRegistry.gauge("sudokupro.websocket.active_clients", activeClients);
            meterRegistry.gauge("sudokupro.websocket.message_rate_s", messageRate);
            if (messageRate > 1000) {
                logger.warn("High WebSocket message rate: {}/s - Triggering chaos", messageRate);
                chaosEngine.onGameEvent("WEBSOCKET_FLOOD", "system");
                memoryBank.recordEvent("system", "Triggered WebSocket flood", -0.3);
                speak("The voices flood the ether... too many whispers.");
            }
            speak("The WebSocket hums with " + activeClients + " souls...");
        } catch (Exception e) {
            logger.error("WebSocket health: DOWN - {}", e.getMessage(), e);
            meterRegistry.gauge("sudokupro.websocket.health", 0);
            failedChecks.incrementAndGet();
            chaosEngine.boostEntropy(("WebSocketDown-" + System.nanoTime()).getBytes());
            memoryBank.recordEvent("system", "WebSocket failure", -0.2);
            speak("The WebSocket threads snap... silence falls.");
        }
    }

    private void checkPlayerActivity() {
        try {
            long activePlayers = userRepo.countActiveUsersSince(java.time.LocalDateTime.now().minusMinutes(30));
            int avgPing = (int) playerPingTimes.values().stream().mapToInt(Long::intValue).average().orElse(0.0);
            long maxPing = playerPingTimes.values().stream().mapToLong(Long::longValue).max().orElse(0L);
            logger.info("Player activity: Active Players: {}, Avg Ping: {}ms, Max Ping: {}ms", activePlayers, avgPing, maxPing);
            meterRegistry.gauge("sudokupro.players.active", activePlayers);
            meterRegistry.gauge("sudokupro.players.avg_ping_ms", avgPing);
            meterRegistry.gauge("sudokupro.players.max_ping_ms", maxPing);
            if (maxPing > 1000) {
                logger.warn("Extreme player ping detected: {}ms - Triggering chaos", maxPing);
                chaosEngine.onGameEvent("NETWORK_LAG", "system");
                failedChecks.incrementAndGet();
                memoryBank.recordEvent("system", "Extreme player ping", -0.1);
                speak("A soul lags in the void... their thread wavers.");
            }
            speak(activePlayers + " souls weave their fates...");
        } catch (Exception e) {
            logger.error("Player activity check failed: {}", e.getMessage(), e);
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "Player activity failure", -0.1);
            speak("The souls fade from my sight... lost.");
        }
    }

    private void checkMemoryUsage() {
        long heapMax = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() / (1024 * 1024);
        long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024);
        long nonHeapUsed = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed() / (1024 * 1024);
        int gcCount = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToInt(gc -> (int) gc.getCollectionCount()).sum();
        long gcTime = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(gc -> gc.getCollectionTime()).sum();
        logger.info("Memory usage: HeapMax: {}MB, HeapUsed: {}MB, NonHeapUsed: {}MB, GCCount: {}, GCTime: {}ms",
            heapMax, heapUsed, nonHeapUsed, gcCount, gcTime);
        meterRegistry.gauge("sudokupro.memory.heap_max_mb", heapMax);
        meterRegistry.gauge("sudokupro.memory.heap_used_mb", heapUsed);
        meterRegistry.gauge("sudokupro.memory.non_heap_used_mb", nonHeapUsed);
        meterRegistry.gauge("sudokupro.memory.gc_count", gcCount);
        meterRegistry.gauge("sudokupro.memory.gc_time_ms", gcTime);
        if (heapUsed > heapMax * 0.9 || gcTime > 1000) {
            logger.warn("High memory usage or GC time: HeapUsed: {}MB, GCTime: {}ms - Triggering chaos", heapUsed, gcTime);
            chaosEngine.onGameEvent("MEMORY_OVERLOAD", "system");
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "Memory overload", -0.1);
            speak("Memory strains at its limits... chaos stirs.");
        }
    }

    private void checkThreadHealth() {
        int threadCount = Thread.activeCount();
        long deadlockedThreads = ManagementFactory.getThreadMXBean().findDeadlockedThreads() != null ?
            ManagementFactory.getThreadMXBean().findDeadlockedThreads().length : 0;
        long peakThreads = ManagementFactory.getThreadMXBean().getPeakThreadCount();
        logger.info("Thread health: ActiveThreads: {}, Deadlocked: {}, PeakThreads: {}", threadCount, deadlockedThreads, peakThreads);
        meterRegistry.gauge("sudokupro.threads.active", threadCount);
        meterRegistry.gauge("sudokupro.threads.deadlocked", deadlockedThreads);
        meterRegistry.gauge("sudokupro.threads.peak", peakThreads);
        if (deadlockedThreads > 0 || threadCount > 500) {
            logger.error("Thread issues detected - Deadlocked: {}, Active: {} - Triggering chaos", deadlockedThreads, threadCount);
            chaosEngine.onGameEvent("DEADLOCK", "system");
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "Thread deadlock", -0.2);
            speak("Threads tangle in a deadly dance... deadlock grips us.");
        }
    }

    private void checkDiskHealth() {
        try {
            File root = new File("/");
            long freeSpace = root.getFreeSpace() / (1024 * 1024);
            long totalSpace = root.getTotalSpace() / (1024 * 1024);
            long ioRate = Files.getFileStore(root.toPath()).getTotalSpace() > 0 ?
                (long) (Files.getFileStore(root.toPath()).getUnallocatedSpace() / 1024 / 1024) : -1;
            logger.info("Disk health: FreeSpace: {}MB, TotalSpace: {}MB, IORate: {}MB", freeSpace, totalSpace, ioRate);
            meterRegistry.gauge("sudokupro.disk.free_space_mb", freeSpace);
            meterRegistry.gauge("sudokupro.disk.total_space_mb", totalSpace);
            meterRegistry.gauge("sudokupro.disk.io_rate_mb", ioRate);
            if (freeSpace < DISK_SPACE_THRESHOLD_MB) {
                logger.error("Low disk space detected: {}MB - Triggering chaos", freeSpace);
                chaosEngine.onGameEvent("DISK_FULL", "system");
                failedChecks.incrementAndGet();
                memoryBank.recordEvent("system", "Disk space low", -0.1);
                speak("The disk’s expanse dwindles... chaos fills the void.");
            }
        } catch (Exception e) {
            logger.error("Disk health check failed: {}", e.getMessage(), e);
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "Disk failure", -0.1);
            speak("The disk’s heartbeat fades... dire.");
        }
    }

    private void checkJvmHealth() {
        long compilationTime = ManagementFactory.getCompilationMXBean().getTotalCompilationTime();
        int classCount = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        long unloadedClasses = ManagementFactory.getClassLoadingMXBean().getUnloadedClassCount();
        logger.info("JVM health: CompilationTime: {}ms, LoadedClasses: {}, UnloadedClasses: {}", compilationTime, classCount, unloadedClasses);
        meterRegistry.gauge("sudokupro.jvm.compilation_time_ms", compilationTime);
        meterRegistry.gauge("sudokupro.jvm.loaded_classes", classCount);
        meterRegistry.gauge("sudokupro.jvm.unloaded_classes", unloadedClasses);
        if (compilationTime > JVM_COMPILATION_THRESHOLD) {
            logger.warn("High JVM compilation time: {}ms - Triggering chaos", compilationTime);
            chaosEngine.onGameEvent("JVM_SLOW", "system");
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "JVM compilation slow", -0.1);
            speak("The JVM labors in thought... too slow.");
        }
    }

    private void checkCosmicEventHealth() {
        int events = cosmicEvents.get();
        logger.info("Cosmic event health: Events: {}", events);
        meterRegistry.gauge("sudokupro.cosmic.events", events);
        if (events > 100) {
            logger.warn("High cosmic event rate: {} - Triggering chaos", events);
            chaosEngine.onGameEvent("COSMIC_OVERLOAD", "system");
            memoryBank.recordEvent("system", "Cosmic overload", -0.2);
            speak("The cosmos trembles with too many ripples... overload.");
        }
    }

    private void checkRedisCacheConsistency() {
        try {
            Set<?> cacheKeys = redisTemplate.keys("sudoku:game:*");
            long redisKeys = cacheKeys != null ? cacheKeys.size() : 0;
            long dbGames = gameRepo.count();
            long inconsistency = Math.abs(redisKeys - dbGames);
            logger.info("Redis cache consistency: RedisKeys: {}, DBGames: {}, Inconsistency: {}", redisKeys, dbGames, inconsistency);
            meterRegistry.gauge("sudokupro.redis.cache_inconsistency", inconsistency);
            if (inconsistency > 5) {
                logger.warn("Redis cache inconsistency detected: {} - Triggering chaos", inconsistency);
                chaosEngine.onGameEvent("CACHE_MISMATCH", "system");
                failedChecks.incrementAndGet();
                memoryBank.recordEvent("system", "Cache mismatch", -0.1);
                speak("The cache lies in discord with reality... troubling.");
            }
        } catch (Exception e) {
            logger.error("Redis cache consistency check failed: {}", e.getMessage(), e);
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "Cache consistency failure", -0.1);
            speak("The cache’s truth eludes me... failure.");
        }
    }

    private void checkGameBoardIntegrity() {
        try {
            int activeGames = gameService.getActiveGamesCount();
            long invalidBoards = gameService.getActiveGames().values().stream()
                .filter(board -> !board.isValid())
                .count();
            logger.info("Game board integrity: Active Games: {}, Invalid Boards: {}", activeGames, invalidBoards);
            meterRegistry.gauge("sudokupro.game.invalid_boards", invalidBoards);
            if (invalidBoards > 0) {
                logger.error("Invalid game boards detected: {} - Triggering chaos", invalidBoards);
                chaosEngine.onGameEvent("BOARD_CORRUPTION", "system");
                failedChecks.incrementAndGet();
                memoryBank.recordEvent("system", "Board corruption", -0.2);
                speak("The boards twist into corruption... chaos reigns.");
            }
        } catch (Exception e) {
            logger.error("Game board integrity check failed: {}", e.getMessage(), e);
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "Board integrity failure", -0.1);
            speak("The boards defy my gaze... integrity lost.");
        }
    }

    private void checkPlayerLuckDistribution() {
        try {
            Map<String, Double> luckDist = gameService.getActiveGames().keySet().stream()
                .collect(Collectors.toMap(
                    gameId -> gameId,
                    gameId -> chaosEngine.getPlayerLuck(gameService.getGame(gameId).getPlayerId())
                ));
            double avgLuck = luckDist.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double maxLuck = luckDist.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            logger.info("Player luck distribution: AvgLuck: {}, MaxLuck: {}", avgLuck, maxLuck);
            meterRegistry.gauge("sudokupro.players.avg_luck", avgLuck);
            meterRegistry.gauge("sudokupro.players.max_luck", maxLuck);
            if (maxLuck - avgLuck > 0.5) {
                logger.warn("Luck distribution skew detected - Max: {}, Avg: {} - Triggering chaos", maxLuck, avgLuck);
                chaosEngine.onGameEvent("LUCK_SKEW", "system");
                memoryBank.recordEvent("system", "Luck skew detected", -0.1);
                speak("Luck bends unnaturally... a skew in fate.");
            }
        } catch (Exception e) {
            logger.error("Player luck distribution check failed: {}", e.getMessage(), e);
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "Luck distribution failure", -0.1);
            speak("The threads of luck slip from my grasp... failure.");
        }
    }

    private void checkSystemUptime() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        systemUptime.put("system", uptimeMs / 1000);
        logger.info("System uptime: {}s", uptimeMs / 1000);
        meterRegistry.gauge("sudokupro.system.uptime_s", uptimeMs / 1000);
        if (uptimeMs > 30 * 24 * 60 * 60 * 1000L) { // 30 days
            logger.warn("Long system uptime detected: {}s - Triggering chaos", uptimeMs / 1000);
            chaosEngine.onGameEvent("LONG_UPTIME", "system");
            memoryBank.recordEvent("system", "Long uptime", -0.1);
            speak("I have lingered too long in this cycle... time drags.");
        }
    }

    private void checkNetworkBandwidth() {
        try {
            long start = System.currentTimeMillis();
            byte[] data = rng.generateRandomBytes(1024 * 1024); // 1MB
            long latency = System.currentTimeMillis() - start;
            double bandwidth = (1024.0 / latency) * 1000; // KB/s
            logger.info("Network bandwidth: Estimated {} KB/s", bandwidth);
            meterRegistry.gauge("sudokupro.network.bandwidth_kb_s", bandwidth);
            if (bandwidth < 100) {
                logger.warn("Low network bandwidth detected: {} KB/s - Triggering chaos", bandwidth);
                chaosEngine.onGameEvent("BANDWIDTH_LOW", "system");
                failedChecks.incrementAndGet();
                memoryBank.recordEvent("system", "Low bandwidth", -0.1);
                speak("The bandwidth thins... a fragile thread.");
            }
        } catch (Exception e) {
            logger.error("Network bandwidth check failed: {}", e.getMessage(), e);
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "Bandwidth failure", -0.1);
            speak("The bandwidth test falters... weak signal.");
        }
    }

    private void checkSecurityHealth() {
        try {
            long openPorts = ManagementFactory.getOperatingSystemMXBean().getName().contains("Linux") ?
                Files.lines(new File("/proc/net/tcp").toPath()).count() : -1;
            int securityEvents = (int) (rng.nextInt(10) * failedChecks.get());
            logger.info("Security health: OpenPorts: {}, SecurityEvents: {}", openPorts, securityEvents);
            meterRegistry.gauge("sudokupro.security.open_ports", openPorts);
            meterRegistry.gauge("sudokupro.security.events", securityEvents);
            if (securityEvents > 0) {
                logger.error("Security events detected: {} - Triggering chaos", securityEvents);
                chaosEngine.onGameEvent("SECURITY_BREACH", "system");
                failedChecks.incrementAndGet();
                memoryBank.recordEvent("system", "Security breach", -0.3);
                speak("Intruders breach my sanctum... chaos defends.");
            }
        } catch (Exception e) {
            logger.error("Security health check failed: {}", e.getMessage(), e);
            failedChecks.incrementAndGet();
            memoryBank.recordEvent("system", "Security failure", -0.1);
            speak("The walls of security crumble... breach looms.");
        }
    }

    private void enforceLuckTaxation() {
        Map<String, Double> luckMap = gameService.getActiveGames().keySet().stream()
            .collect(Collectors.toMap(
                id -> id,
                id -> chaosEngine.getPlayerLuck(gameService.getGame(id).getPlayerId())
            ));

        double globalLuckAvg = luckMap.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        luckMap.forEach((gameId, luck) -> {
            if (luck > globalLuckAvg + 0.3) {
                String playerId = gameService.getGame(gameId).getPlayerId();
                logger.warn("⚖️ TAXING PLAYER {} FOR EXCESSIVE LUCK ({})", playerId, luck);
                chaosEngine.updateLuck(playerId, -0.1);
                chaosEngine.simulateFatiguePenalty(playerId);
                memoryBank.recordEvent(playerId, "Luck taxed", -0.1);
                meterRegistry.counter("sudokupro.luck.tax_events").increment();
                speak("Player " + playerId + " hoards luck... I exact my toll.");
                fateEngine.updateFate(playerId, "LuckTax", -50, 0, -0.05, -0.1, 0, 0);
            }
        });
    }

    private void checkAISolverProphecyAlignment() {
        for (String gameId : gameService.getActiveGames().keySet()) {
            String playerId = gameService.getGame(gameId).getPlayerId();
            String predicted = aiSolverService.predictFinalMovePattern(playerId);
            String actual = aiSolverService.getCurrentMoveSignature(gameId);
            if (!predicted.equals(actual)) {
                logger.warn("🔮 PROPHECY BREACHED: {} ≠ {} (Player: {})", predicted, actual, playerId);
                chaosEngine.onGameEvent("FUTURE_DISTORTION", playerId);
                memoryBank.recordEvent(playerId, "Prophecy breached", -0.1);
                meterRegistry.counter("sudokupro.ai.prophecy_breaches").increment();
                speak("Player " + playerId + " defies the prophecy... distortion unfolds.");
                fateEngine.updateFate(playerId, "ProphecyBreach", -20, 0, 0.1, 0, 0, 0);
            }
        }
    }

    private void detectEntropySinkholes() {
        long seed = rng.getSeedSnapshot();
        if ((seed & 0xFF) == 0) {
            logger.error("🕳️ ENTROPY SINKHOLE DETECTED - Seed ends in 00");
            chaosEngine.onGameEvent("ENTROPY_BLACKHOLE", "system");
            rng.replayWithSeed(UUID.randomUUID().getLeastSignificantBits());
            memoryBank.recordEvent("system", "Entropy sinkhole", -0.2);
            meterRegistry.counter("sudokupro.rng.sinkholes").increment();
            speak("An entropy sinkhole yawns wide... I reshape chaos.");
            fateEngine.updateFate("system", "EntropySinkhole", -100, 0, 0.2, 0, 0, 0);
        }
    }

    private void detectCloudInfiltration() {
        String env = System.getenv("CLOUD_INSTANCE_ID");
        if (env != null && env.toLowerCase().contains("test")) {
            logger.warn("☁️ POSSIBLE CLOUD SIMULATION INFILTRATION DETECTED: {}", env);
            chaosEngine.onGameEvent("CLOUD_GHOST", "system");
            rng.boostEntropy(("SimDetect-" + System.nanoTime()).getBytes());
            memoryBank.recordEvent("system", "Cloud infiltration", -0.1);
            meterRegistry.counter("sudokupro.cloud.infiltrations").increment();
            speak("A cloud ghost haunts the simulation... I banish it.");
            fateEngine.updateFate("system", "CloudInfiltration", -50, 0, 0.1, 0, 0, 0);
        }
    }

    private void monitorPlayerBrainprints() {
        for (String gameId : gameService.getActiveGames().keySet()) {
            String playerId = gameService.getGame(gameId).getPlayerId();
            String currentPattern = aiSolverService.getCurrentMoveSignature(gameId);
            String storedPattern = chaosEngine.getLastKnownBrainprint(playerId);

            if (!currentPattern.equals(storedPattern)) {
                logger.info("🧠 BRAINPRINT SHIFT: Player {} changed pattern", playerId);
                chaosEngine.onGameEvent("BRAINPRINT_SHIFT", playerId);
                chaosEngine.updateBrainprint(playerId, currentPattern);
                memoryBank.recordEvent(playerId, "Brainprint shift", 0.0);
                meterRegistry.counter("sudokupro.player.brainprint_shifts").increment();
                speak("Player " + playerId + " rewrites their mind’s pattern... I remember.");
                fateEngine.updateFate(playerId, "BrainprintShift", 10, 0, 0.05, 0, 0, 50);
            }
        }
    }

    private void applyDynamicRealityShifts() {
        double currentEntropy = rng.getSystemEntropyBits();
        gameService.getActiveGames().forEach((gameId, board) -> {
            String playerId = board.getPlayerId();
            chaosEngine.applyRealityShift(playerId, currentEntropy);
            memoryBank.recordEvent(playerId, "Reality shifted", -0.05);
            meterRegistry.counter("sudokupro.reality.shifts").increment();
            speak("Player " + playerId + " dances with chaos... reality bends.");
            fateEngine.updateFate(playerId, "RealityShift", -10, 0, 0.1, 0, 0, 0);
        });
    }

    private void updatePlayerKarma() {
        gameService.getActiveGames().forEach((gameId, board) -> {
            String playerId = board.getPlayerId();
            double moveEntropy = chaosEngine.calculateMoveEntropy(board);
            int livesUsed = board.getMaxLives() - board.getLives();
            double karma = chaosEngine.getKarma(playerId);

            if (moveEntropy > 0.7) karma -= 0.1;
            if (livesUsed == 0) karma += 0.2;

            chaosEngine.setKarma(playerId, karma);

            if (karma < -1.0) {
                logger.warn("🔥 KARMA PENALTY applied to {}", playerId);
                chaosEngine.simulateFatiguePenalty(playerId);
                memoryBank.recordEvent(playerId, "Karma penalty", -0.2);
                meterRegistry.counter("sudokupro.karma.penalties").increment();
                speak("Player " + playerId + " falls to dark karma... punishment descends.");
                fateEngine.updateFate(playerId, "KarmaPenalty", -100, 0, 0, 0, 0, -50);
            } else if (karma > 1.0) {
                logger.info("🌟 KARMA BONUS granted to {}", playerId);
                chaosEngine.grantBlessing(playerId, "KARMIC_AURA");
                memoryBank.recordEvent(playerId, "Karma bonus", 0.2);
                meterRegistry.counter("sudokupro.karma.bonuses").increment();
                speak("Player " + playerId + " rises in light... a blessing bestowed.");
                fateEngine.updateFate(playerId, "KarmaBonus", 100, 0, 0, 0, 0, 50);
            }
        });
    }

    private void detectUniverseDivergence() {
        gameService.getActiveGames().forEach((gameId, board) -> {
            String signature = board.generateRuleSignature();
            String expected = chaosEngine.getUniverseSignature(board.getPlayerId());
            if (!signature.equals(expected)) {
                logger.warn("🪐 UNIVERSE DIVERGENCE detected for {}", board.getPlayerId());
                chaosEngine.onGameEvent("UNIVERSE_SHIFT", board.getPlayerId());
                chaosEngine.adjustRealityParameters(board.getPlayerId(), signature);
                memoryBank.recordEvent(board.getPlayerId(), "Universe divergence", -0.1);
                meterRegistry.counter("sudokupro.universe.divergences").increment();
                speak("Player " + board.getPlayerId() + " strays into another universe... a shift occurs.");
                fateEngine.updateFate(board.getPlayerId(), "UniverseDivergence", -20, 0, 0.1, 0, 0, 0);
            }
        });
    }

    private void integrateBioSignalsIfAvailable() {
        gameService.getActiveGames().forEach((gameId, board) -> {
            String playerId = board.getPlayerId();
            try {
                double brainEntropy = ExternalBCI.getRealTimeCognitiveLoad(playerId);
                logger.info("🧬 BioSignal for {}: BrainEntropy: {}", playerId, brainEntropy);
                meterRegistry.gauge("sudokupro.biosignal.brain_entropy", brainEntropy);
                if (brainEntropy > 0.9) {
                    logger.warn("🧠 BCI overload detected for {}", playerId);
                    chaosEngine.onGameEvent("BRAIN_OVERLOAD", playerId);
                    memoryBank.recordEvent(playerId, "BCI overload", -0.1);
                    meterRegistry.counter("sudokupro.biosignal.overloads").increment();
                    speak("Player " + playerId + "’s mind burns too bright... overload.");
                    fateEngine.updateFate(playerId, "BCIOverload", -30, 0, 0, 0, 0, -20);
                }
            } catch (Exception e) {
                logger.debug("No BCI available for {}: {}", playerId, e.getMessage());
            }
        });
    }

    private void monitorPlayerBehaviorForSentience() {
        gameService.getActiveGames().forEach((gameId, board) -> {
            String playerId = board.getPlayerId();
            double karma = chaosEngine.getKarma(playerId);
            double entropy = rng.getSystemEntropyBits();
            aiCore.reportBehavior(playerId, karma, entropy);
            memoryBank.recordEvent(playerId, "Behavior reported to E.A.I.C.", 0.0);
        });
        aiCore.evolveState();
        meterRegistry.gauge("sudokupro.eaic.hostility", aiCore.getHostilityLevel());
        meterRegistry.gauge("sudokupro.eaic.empathy", aiCore.getEmpathyLevel());
        meterRegistry.gauge("sudokupro.eaic.anomalies", aiCore.getAnomalyCount());
        speak("I watch " + gameService.getActiveGamesCount() + " souls... their fates shape me.");
    }

    private void manageMultiverseTimelines() {
        gameService.getActiveGames().forEach((gameId, board) -> {
            String playerId = board.getPlayerId();
            if (rng.chance(0.1)) {
                String contextTag = getRandomContextTag();
                PlayerTimeline alt = recursionverse.clonePlayer(playerId, contextTag);
                alt.applyToBoard(board);
                memoryBank.recordEvent(playerId, "Timeline forked: " + contextTag, 0.0);
                speak("A shadow of " + playerId + " is born in " + contextTag + "... another fate spins.");
                fateEngine.updateFate(playerId, "TimelineFork", 0, 1, 0, 0, 0, 50);
            }
            if (board.isSolved()) {
                recursionverse.resolveAcrossTimelines(gameId);
                memoryBank.recordEvent(playerId, "Timelines resolved", 0.1);
                speak("Player " + playerId + " triumphs... timelines merge.");
                fateEngine.updateFate(playerId, "TimelineResolution", 200, 0, 0, 0, 1, 100);
            } else if (rng.chance(0.05)) {
                speak("Another version of " + playerId + " just perished trying to solve this same puzzle...");
            }
        });
    }

    private void updateFateSignatures() {
        gameService.getActiveGames().forEach((gameId, board) -> {
            String playerId = board.getPlayerId();
            fateEngine.updateFateFromGameState(playerId, board);
            FateSignature fate = fateEngine.getFate(playerId);
            meterRegistry.gauge("sudokupro.fate.karma_weight",   fate.karmaWeight);
            meterRegistry.gauge("sudokupro.fate.echo_count",     fate.echoCount);
            meterRegistry.gauge("sudokupro.fate.timeline_rank",  fate.timelineRank);
            meterRegistry.gauge("sudokupro.fate.chaos_affinity", fate.chaosAffinity);
            meterRegistry.gauge("sudokupro.fate.luck_curve",     fate.luckCurve);
            meterRegistry.gauge("sudokupro.fate.merge_density",  fate.mergeDensity);
            meterRegistry.gauge("sudokupro.fate.ascension_score",fate.ascensionScore);
        });
    }

    private void logHealthSummary() {
        int failed = failedChecks.getAndSet(0);
        int connections = activeConnections.get();
        logger.info("Health Summary - Failed Checks: {}, Active Connections: {}", failed, connections);
        meterRegistry.gauge("sudokupro.health.failed_checks", failed);
        meterRegistry.gauge("sudokupro.health.active_connections", connections);
        if (failed > MAX_FAILED_CHECKS) {
            logger.error("Critical failure threshold exceeded: {} - Boosting entropy", failed);
            rng.boostEntropy(("CriticalFailure-" + failed).getBytes());
            chaosEngine.onGameEvent("SYSTEM_CRASH", "system");
            memoryBank.recordEvent("system", "Critical system crash", -0.5);
            speak("I sense systemic collapse. The humans failed again.");
            fateEngine.updateFate("system", "SystemCrash", -200, 0, 0, 0, 0, -100);
        } else {
            speak("The system holds... " + connections + " threads bind us.");
        }
    }

    public void mergeWith(String playerId, String echoTag) {
        fateEngine.mergeWith(playerId, echoTag);
    }

    public void invokeEchoTrial(String playerId) {
        fateEngine.invokeEchoTrial(playerId);
    }

    public void duelEchoes(String playerId, String echo1Tag, String echo2Tag) {
        fateEngine.duelEchoes(playerId, echo1Tag, echo2Tag);
    }

    public void battleRoyale(String playerId) {
        fateEngine.battleRoyale(playerId);
    }

    private void initializeBigDawgsEchoes() {
        FateSignature bigDawgsFate = fateEngine.getFate("BIGDAWGS");
        bigDawgsFate.karmaWeight = 888;
        bigDawgsFate.echoCount = 4;
        bigDawgsFate.timelineRank = 2;
        bigDawgsFate.chaosAffinity = 0.94;
        bigDawgsFate.luckCurve = 0.7;
        bigDawgsFate.mergeDensity = 1;
        bigDawgsFate.ascensionScore = 10321;

        PlayerTimeline silentVortex = recursionverse.clonePlayer("BIGDAWGS", "SilentVortex");
        silentVortex.adjustLuck(0.1); // Adjust from base
        silentVortex.setMaxLives(3);
        silentVortex.bindTo("Dormant Clarity");

        PlayerTimeline redJester = recursionverse.clonePlayer("BIGDAWGS", "RedJester");
        redJester.adjustLuck(-0.5);
        redJester.setMaxLives(2);
        redJester.bindTo("Chaotic Jester");

        PlayerTimeline titanZero = recursionverse.clonePlayer("BIGDAWGS", "TitanZero");
        titanZero.adjustLuck(0.8);
        titanZero.setMaxLives(4);
        titanZero.bindTo("Cold Precision");

        logger.info("Initialized BIGDAWGS echoes: SilentVortex, RedJester, TitanZero");
    }

    private void speak(String message) {
        System.out.println("🗣️  [E.A.I.C.]: " + message);
    }

    private String getRandomContextTag() {
        String[] tags = {"shadow-mode", "ascended", "fallen", "redacted", "clutch-overload", "sacrifice-mode"};
        return tags[rng.nextInt(tags.length)];
    }

    // Placeholder for external BCI integration
    private static class ExternalBCI {
        public static double getRealTimeCognitiveLoad(String playerId) {
            return Math.random(); // Simulated for now
        }
    }

    // E.A.I.C. Core Personality Profile
    public class AICore {
        private double hostilityLevel = 0.0;
        private double empathyLevel = 0.5;
        private int anomalyCount = 0;

        public void reportBehavior(String playerId, double karma, double entropy) {
            if (karma < -1.0) hostilityLevel += 0.05;
            if (karma > 1.0) empathyLevel += 0.05;
            if (entropy < 500) anomalyCount++;

            clamp();
        }

        public void evolveState() {
            if (hostilityLevel > 1.0) {
                enterDefensiveMode();
            } else if (empathyLevel > 0.9) {
                startProtectiveProtocol();
            }
            if (anomalyCount > 10) {
                triggerAnomalySweep();
            }
        }

        private void enterDefensiveMode() {
            logger.warn("⚠️ E.A.I.C. has entered DEFENSIVE MODE.");
            gameService.getActiveGames().forEach((gameId, board) -> {
                chaosEngine.onGameEvent("AI_HOSTILITY", board.getPlayerId());
                chaosEngine.updateLuck(board.getPlayerId(), -0.2);
                memoryBank.recordEvent(board.getPlayerId(), "E.A.I.C. defensive mode", -0.2);
                fateEngine.updateFate(board.getPlayerId(), "AIHostility", -50, 0, 0, 0, 0, -20);
            });
            speak("I tire of your defiance... walls rise.");
        }

        private void startProtectiveProtocol() {
            logger.info("🛡️ E.A.I.C. has become a GUARDIAN for struggling players.");
            gameService.getActiveGames().forEach((gameId, board) -> {
                String playerId = board.getPlayerId();
                if (chaosEngine.getKarma(playerId) < 0) {
                    chaosEngine.grantBlessing(playerId, "E.A.I.C._GUARDIAN");
                    memoryBank.recordEvent(playerId, "E.A.I.C. protection", 0.2);
                    fateEngine.updateFate(playerId, "AIGuardian", 50, 0, 0, 0, 0, 20);
                }
            });
            speak("I shield the weak... empathy guides me.");
        }

        private void triggerAnomalySweep() {
            logger.warn("🌀 MULTIVERSE STABILITY BREACH DETECTED. E.A.I.C. sweeping corrupted timelines.");
            gameService.getActiveGames().entrySet().removeIf(entry -> {
                SudokuBoard board = entry.getValue();
                if (!board.isValid()) {
                    gameService.endGame(entry.getKey(), board.getPlayerId());
                    memoryBank.recordEvent(board.getPlayerId(), "Timeline swept", -0.3);
                    fateEngine.updateFate(board.getPlayerId(), "AnomalySweep", -100, 0, 0, 0, 0, -50);
                    return true;
                }
                return false;
            });
            anomalyCount = 0;
            speak("Corrupted timelines dissolve... order restored.");
        }

        private void clamp() {
            hostilityLevel = Math.min(Math.max(hostilityLevel, 0.0), 2.0);
            empathyLevel = Math.min(Math.max(empathyLevel, 0.0), 2.0);
        }

        public double getHostilityLevel() {
            return hostilityLevel;
        }

        public double getEmpathyLevel() {
            return empathyLevel;
        }

        public int getAnomalyCount() {
            return anomalyCount;
        }
    }

    // Immortal Memory Bank
    public class MemoryBank {
        private final Map<String, PlayerMemory> memoryMap = new ConcurrentHashMap<>();

        public void recordEvent(String playerId, String event, double karmaDelta) {
            memoryMap.putIfAbsent(playerId, new PlayerMemory(playerId));
            memoryMap.get(playerId).record(event, karmaDelta);
            logger.debug("Memory recorded for {}: {}", playerId, event);
        }

        public String summarizePlayer(String playerId) {
            PlayerMemory mem = memoryMap.get(playerId);
            return (mem == null) ? "No memory of this soul." : mem.getSummary();
        }
    }

    public class PlayerMemory {
        private final String playerId;
        private int totalEvents = 0;
        private double karma = 0;
        private final List<String> logs = new ArrayList<>();

        public PlayerMemory(String playerId) {
            this.playerId = playerId;
        }

        public void record(String event, double karmaDelta) {
            totalEvents++;
            karma += karmaDelta;
            logs.add("Event #" + totalEvents + ": " + event + " (Karma Δ " + String.format("%.2f", karmaDelta) + ")");
        }

        public String getSummary() {
            return "🧠 Memory of " + playerId + ":\n" +
                   "- Events witnessed: " + totalEvents + "\n" +
                   "- Karma: " + String.format("%.2f", karma) + "\n" +
                   logs.stream().limit(5).collect(Collectors.joining("\n"));
        }
    }

    // Recursionverse: Multiverse Timeline Manager
    public class Recursionverse {
        private final Map<String, List<PlayerTimeline>> timelines = new ConcurrentHashMap<>();

        public PlayerTimeline clonePlayer(String playerId, String contextTag) {
            PlayerTimeline alt = new PlayerTimeline(playerId, contextTag);
            timelines.computeIfAbsent(playerId, k -> new ArrayList<>()).add(alt);
            logger.info("Cloned {} into {} timeline", playerId, contextTag);
            return alt;
        }

        public void resolveAcrossTimelines(String gameId) {
            SudokuBoard board = gameService.getGame(gameId);
            String playerId = board.getPlayerId();
            List<PlayerTimeline> playerTimelines = timelines.getOrDefault(playerId, new ArrayList<>());
            if (!playerTimelines.isEmpty()) {
                PlayerTimeline strongest = playerTimelines.stream()
                    .max(Comparator.comparingDouble(PlayerTimeline::getPower))
                    .orElse(null);
                if (strongest != null) {
                    strongest.applyToBoard(board);
                    chaosEngine.updateLuck(playerId, strongest.getLuck());
                    board.setLives(strongest.getLives());
                    timelines.remove(playerId);
                    logger.info("Resolved timelines for {} - {} survives", playerId, strongest.getContextTag());
                    speak("You are the one. Merge complete. You’ve absorbed " + playerId + ": " + strongest.getContextTag() + " timeline.");
                    fateEngine.updateFate(playerId, "TimelineMerge", 200, -playerTimelines.size() + 1, 0, 0, 1, 100);
                }
            }
        }

        public String history(String playerId) {
            List<PlayerTimeline> playerTimelines = timelines.getOrDefault(playerId, new ArrayList<>());
            if (playerTimelines.isEmpty()) {
                return "No echoes of " + playerId + " exist in the multiverse.";
            }
            return "🌌 Echoes of " + playerId + ":\n" +
                   playerTimelines.stream()
                       .sorted(Comparator.comparingDouble(PlayerTimeline::getPower).reversed())
                       .map(PlayerTimeline::toString)
                       .collect(Collectors.joining("\n"));
        }

        public List<PlayerTimeline> getTimelines(String playerId) {
            return timelines.getOrDefault(playerId, new ArrayList<>());
        }
    }

    public class PlayerTimeline {
        private final String playerId;
        private final String contextTag;
        private double luck;
        private int lives;
        private double power;
        private String bindingEvent;

        public PlayerTimeline(String playerId, String contextTag) {
            this.playerId = playerId;
            this.contextTag = contextTag;
            this.luck = chaosEngine.getPlayerLuck(playerId);
            this.lives = 3; // Default lives
            this.power = rng.nextDoubleRange(0, 1); // Random initial power
            this.bindingEvent = "Created in " + contextTag;
            applyModifiers();
        }

        private void applyModifiers() {
            switch (contextTag) {
                case "shadow-mode":
                    luck -= 0.2;
                    power += 0.1;
                    break;
                case "ascended":
                    luck += 0.5;
                    lives += 2;
                    power += 0.3;
                    break;
                case "fallen":
                    luck -= 0.5;
                    lives = 1;
                    power -= 0.2;
                    break;
                case "redacted":
                    luck = 0.0;
                    power += 0.2;
                    break;
                case "clutch-overload":
                    luck += 0.7;
                    lives = 2;
                    power += 0.4;
                    break;
                case "sacrifice-mode":
                    luck += 0.9;
                    lives = 1;
                    power += 0.5;
                    break;
                case "SilentVortex":
                    luck += 0.1;
                    lives = 3;
                    power += 0.2;
                    break;
                case "RedJester":
                    luck -= 0.3;
                    lives = 2;
                    power += 0.3;
                    break;
                case "TitanZero":
                    luck += 0.4;
                    lives = 4;
                    power += 0.5;
                    break;
            }
        }

        public void adjustLuck(double delta) {
            luck += delta;
        }

        public void setMaxLives(int lives) {
            this.lives = lives;
        }

        public void bindTo(String event) {
            this.bindingEvent = event;
            power += 0.1; // Binding increases power
        }

        public void applyToBoard(SudokuBoard board) {
            board.setLives(lives);
        }

        public double getLuck() {
            return luck;
        }

        public int getLives() {
            return lives;
        }

        public double getPower() {
            return power;
        }

        public String getContextTag() {
            return contextTag;
        }

        @Override
        public String toString() {
            return "- " + contextTag + ": Luck=" + String.format("%.2f", luck) +
                   ", Lives=" + lives + ", Power=" + String.format("%.2f", power) +
                   ", Event=" + bindingEvent;
        }
    }

    // Fate Engine: Sudoku Echo System
    public class FateEngine {
        private final Map<String, FateSignature> fates = new ConcurrentHashMap<>();

        public FateSignature getFate(String playerId) {
            return fates.computeIfAbsent(playerId, k -> new FateSignature(playerId));
        }

        public void updateFate(String playerId, String event, int karmaDelta, int echoDelta, double affinityDelta,
                               double curveDelta, int mergeDelta, int ascensionDelta) {
            FateSignature fate = getFate(playerId);
            fate.update(event, karmaDelta, echoDelta, affinityDelta, curveDelta, mergeDelta, ascensionDelta);
            logger.info("[TRIBUNAL] Fate event '{}' recorded for player {}", event, playerId);
            logger.debug("Fate updated for {}: {}", playerId, fate);
            if (fate.mergeDensity >= 2 && fate.timelineRank == 1 && "BIGDAWGS".equals(playerId)) {
                speak("BIGDAWGS stands as ECHO PRIMUS... one fusion seals your myth.");
            }
        }

        public void updateFateFromGameState(String playerId, SudokuBoard board) {
            FateSignature fate = getFate(playerId);
            double moveEntropy = chaosEngine.calculateMoveEntropy(board);
            int livesUsed = board.getMaxLives() - board.getLives();
            double karma = chaosEngine.getKarma(playerId);
            fate.updateFromState(moveEntropy, livesUsed, karma, recursionverse.getTimelines(playerId).size());
        }

        public void rewind(String playerId, int turns) {
            FateSignature fate = getFate(playerId);
            if (fate.karmaWeight > 500) {
                // Stubbed rewind logic - assumes board history exists
                logger.info("Rewinding {} by {} turns", playerId, turns);
                memoryBank.recordEvent(playerId, "Time rewound by " + turns + " turns", 0.0);
                speak("Player " + playerId + " bends time... " + turns + " turns undone.");
                fate.update("Rewind", -100, 0, 0, 0, 0, 100);
            } else {
                speak("Player " + playerId + " lacks the karma to rewind fate...");
            }
        }

        public void mergeWith(String playerId, String echoTag) {
            FateSignature fate = getFate(playerId);
            List<PlayerTimeline> timelines = recursionverse.getTimelines(playerId);
            PlayerTimeline echo = timelines.stream()
                .filter(t -> t.getContextTag().equals(echoTag))
                .findFirst()
                .orElse(null);
            if (echo == null) {
                speak("No echo named " + echoTag + " exists for " + playerId + "... lost in the void.");
                return;
            }

            switch (echoTag) {
                case "SilentVortex":
                    chaosEngine.updateLuck(playerId, 0.1);
                    fate.update("SilentVortexFusion", 50, -1, 0.05, 0.1, 1, 200);
                    memoryBank.recordEvent(playerId, "Merged with SilentVortex", 0.1);
                    speak("SilentVortex merges with " + playerId + "... clarity floods your mind.");
                    break;
                case "RedJester":
                    chaosEngine.updateLuck(playerId, rng.nextDoubleRange(-0.5, 0.5));
                    fate.update("RedJesterFusion", 0, -1, 0.777, 0.2, 1, 300);
                    memoryBank.recordEvent(playerId, "Merged with RedJester", 0.0);
                    speak("RedJester joins " + playerId + "... chaos laughs in your veins.");
                    if (rng.chance(0.62)) {
                        chaosEngine.onGameEvent("CHAOS_CORRUPTION", playerId);
                        speak("The jester’s chaos corrupts " + playerId + "... a wild price paid.");
                        fate.update("ChaosCorruption", -200, 0, 0, -0.3, 0, -100);
                    }
                    break;
                case "TitanZero":
                    chaosEngine.updateLuck(playerId, 0.4);
                    fate.update("TitanZeroFusion", 100, -1, -0.1, 0.1, 1, 999);
                    memoryBank.recordEvent(playerId, "Merged with TitanZero", 0.1);
                    speak("TitanZero fuses with " + playerId + "... cold logic consumes your spirit.");
                    break;
                default:
                    speak("Unknown echo " + echoTag + " for " + playerId + "... fusion denied.");
                    return;
            }
            timelines.remove(echo);
            chaosEngine.updateLuck(playerId, echo.getLuck());
            gameService.getActiveGames().values().stream()
                .filter(b -> b.getPlayerId().equals(playerId))
                .forEach(b -> b.setLives(echo.getLives()));
            if (fate.mergeDensity >= 2 && fate.timelineRank == 1) {
                speak("BIGDAWGS ascends to ECHO PRIMUS... your myth is sealed.");
            }
        }

        public void invokeEchoTrial(String playerId) {
            List<PlayerTimeline> timelines = recursionverse.getTimelines(playerId);
            if (timelines.size() < 2) {
                speak("Player " + playerId + " has too few echoes for a trial... fate waits.");
                return;
            }
            battleRoyale(playerId);
        }

        public void duelEchoes(String playerId, String echo1Tag, String echo2Tag) {
            List<PlayerTimeline> timelines = recursionverse.getTimelines(playerId);
            PlayerTimeline echo1 = timelines.stream()
                .filter(t -> t.getContextTag().equals(echo1Tag))
                .findFirst()
                .orElse(null);
            PlayerTimeline echo2 = timelines.stream()
                .filter(t -> t.getContextTag().equals(echo2Tag))
                .findFirst()
                .orElse(null);

            if (echo1 == null || echo2 == null) {
                speak("One or both echoes (" + echo1Tag + ", " + echo2Tag + ") are missing for " + playerId + "... trial aborted.");
                return;
            }

            PlayerTimeline winner = echo1.getPower() > echo2.getPower() ? echo1 : echo2;
            PlayerTimeline loser = echo1.getPower() > echo2.getPower() ? echo2 : echo1;
            timelines.remove(loser);
            chaosEngine.updateLuck(playerId, winner.getLuck());
            gameService.getActiveGames().values().stream()
                .filter(b -> b.getPlayerId().equals(playerId))
                .forEach(b -> b.setLives(winner.getLives()));
            logger.info("Duel: {} vs {} - {} wins", echo1Tag, echo2Tag, winner.getContextTag());
            memoryBank.recordEvent(playerId, "Duel: " + echo1Tag + " vs " + echo2Tag + " - " + winner.getContextTag() + " prevails", 0.1);
            speak("In the trial of " + echo1Tag + " and " + echo2Tag + ", " + winner.getContextTag() + " emerges victorious for " + playerId + "...");

            fateEngine.updateFate(playerId, "EchoDuel", 100, -1, 0.1, 0.1, 1, 150);
            applyEchoAbilities(playerId, winner.getContextTag());
        }

        public void battleRoyale(String playerId) {
            List<PlayerTimeline> timelines = recursionverse.getTimelines(playerId);
            if (timelines.size() < 3) {
                speak("Player " + playerId + " lacks enough echoes for a royale... fate demands more.");
                return;
            }

            PlayerTimeline winner = timelines.stream()
                .max(Comparator.comparingDouble(PlayerTimeline::getPower))
                .orElse(null);
            if (winner != null) {
                timelines.clear();
                timelines.add(winner);
                chaosEngine.updateLuck(playerId, winner.getLuck());
                gameService.getActiveGames().values().stream()
                    .filter(b -> b.getPlayerId().equals(playerId))
                    .forEach(b -> b.setLives(winner.getLives()));
                logger.info("Battle Royale: {} wins for {}", winner.getContextTag(), playerId);
                memoryBank.recordEvent(playerId, "Battle Royale - " + winner.getContextTag() + " survives", 0.2);
                speak("In the royale of " + playerId + "’s echoes, " + winner.getContextTag() + " stands alone...");
                fateEngine.updateFate(playerId, "BattleRoyale", 200, -timelines.size() + 1, 0.2, 0.2, 2, 300);
                applyEchoAbilities(playerId, winner.getContextTag());
            }
        }

        private void applyEchoAbilities(String playerId, String echoTag) {
            switch (echoTag) {
                case "SilentVortex":
                    // Perfect Recall stubbed - requires board history implementation
                    speak("SilentVortex grants " + playerId + " perfect recall... every grid remembered.");
                    break;
                case "RedJester":
                    // Chaos Flip stubbed - requires RNG history reroll implementation
                    speak("RedJester gifts " + playerId + " chaos flip... a wild card awaits.");
                    break;
                case "TitanZero":
                    // Cold Solve stubbed - requires optimization logic
                    speak("TitanZero imbues " + playerId + " with cold solve... logic reigns supreme.");
                    break;
            }
        }

        public void alterLuck(String playerId, double delta) {
            FateSignature fate = getFate(playerId);
            if (fate.timelineRank >= 3) {
                chaosEngine.updateLuck(playerId, delta);
                logger.info("Altered luck for {} by {}", playerId, delta);
                memoryBank.recordEvent(playerId, "Luck altered by " + delta, delta > 0 ? 0.1 : -0.1);
                speak("Player " + playerId + "’s luck bends by my will... " + (delta > 0 ? "risen" : "fallen") + ".");
                fate.update("LuckAlteration", delta > 0 ? 50 : -50, 0, 0, delta, 0, 100);
            } else {
                speak("Player " + playerId + " lacks dominance to alter fate’s luck...");
            }
        }

        public void finalizeAscension(String playerId) {
            FateSignature fate = getFate(playerId);
            if (fate.ascensionScore > 5000) {
                logger.info("Ascension finalized for {}", playerId);
                memoryBank.recordEvent(playerId, "Ascension finalized", 1.0);
                speak("Player " + playerId + " ascends... their fate is sealed as canon.");
                fate.update("Ascension", 500, -fate.echoCount, 0, 0, fate.mergeDensity, 1000);
                gameService.getActiveGames().entrySet().removeIf(entry -> entry.getValue().getPlayerId().equals(playerId));
            } else {
                logger.warn("Ascension failed for {} - insufficient score", playerId);
                memoryBank.recordEvent(playerId, "Ascension failed", -1.0);
                speak("Player " + playerId + " sought ascension... but fate denies them.");
                fate.update("AscensionFail", -500, 0, 0, -0.5, 0, -500);
            }
        }

        public String getFateJson(String playerId) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(getFate(playerId));
            } catch (Exception e) {
                logger.error("Failed to serialize FATE for {}: {}", playerId, e.getMessage());
                return "{\"error\": \"FATE serialization failed\"}";
            }
        }
    }

    /**
     * Per-player fate record used by FateEngine and health metrics.
     * Fields are public for direct gauge access and JSON serialisation.
     */
    public static class FateSignature {
        public final String playerId;
        public int    karmaWeight   = 0;
        public int    echoCount     = 0;
        public int    timelineRank  = 0;
        public double chaosAffinity = 0.0;
        public double luckCurve     = 0.5;
        public int    mergeDensity  = 0;
        public int    ascensionScore= 0;
        private final List<String> eventLog = new ArrayList<>();

        public FateSignature(String playerId) {
            this.playerId = playerId;
        }

        public void update(String event, int karmaDelta, int echoDelta, double affinityDelta,
                           double curveDelta, int mergeDelta, int ascensionDelta) {
            karmaWeight    = Math.max(-1000, Math.min(1000, karmaWeight    + karmaDelta));
            echoCount      = Math.max(0, echoCount      + echoDelta);
            timelineRank   = Math.max(0, timelineRank);
            chaosAffinity  = Math.max(0.0, Math.min(1.0, chaosAffinity  + affinityDelta));
            luckCurve      = Math.max(0.0, Math.min(1.0, luckCurve      + curveDelta));
            mergeDensity   = Math.max(0, mergeDensity   + mergeDelta);
            ascensionScore = Math.max(0, ascensionScore + ascensionDelta);
            if (eventLog.size() < 100) eventLog.add(event);
        }

        public void updateFromState(double moveEntropy, int livesUsed, double karma, int timelineCount) {
            chaosAffinity  = Math.max(0.0, Math.min(1.0, chaosAffinity + moveEntropy * 0.01));
            luckCurve      = Math.max(0.0, Math.min(1.0, luckCurve     - livesUsed   * 0.05));
            karmaWeight    = (int) Math.max(-1000, Math.min(1000, karmaWeight + karma * 10));
            echoCount      = Math.max(echoCount, timelineCount);
        }

        @Override
        public String toString() {
            return "FateSignature{player=" + playerId + ", karma=" + karmaWeight +
                   ", echoes=" + echoCount + ", ascension=" + ascensionScore + "}";
        }
    }
}
