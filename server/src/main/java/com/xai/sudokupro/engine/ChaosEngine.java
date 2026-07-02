package com.xai.sudokupro.engine;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.util.SecureRandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChaosEngine {
    private static final Logger log = LoggerFactory.getLogger(ChaosEngine.class);
    private final SecureRandomGenerator rng;
    private final GameService gameService;
    private final Map<String, Double> playerLuck = new ConcurrentHashMap<>();
    private final Map<String, LuckProfile> playerProfiles = new ConcurrentHashMap<>();
    private final Map<String, Double> playerKarma = new ConcurrentHashMap<>();
    private final Map<String, String> playerBrainprints = new ConcurrentHashMap<>();
    private final Map<String, String> universeSignatures = new ConcurrentHashMap<>();

    @Autowired
    public ChaosEngine(SecureRandomGenerator rng, @Lazy GameService gameService) {
        this.rng = rng;
        this.gameService = gameService;
    }

    public double simulateReactionTime(String playerId) {
        double base = 0.25;
        double delay = rng.gaussianDouble(base, 0.05);
        double reaction = Math.max(0.1, delay);
        updateProfile(playerId, p -> p.averageReactionTime =
            (p.averageReactionTime * p.fatigueEvents + reaction) / (p.fatigueEvents + 1));
        return reaction;
    }

    public int simulateFatiguePenalty(String playerId) {
        int penalty = rng.nextBinomial(10, getPlayerFatigueLevel(playerId));
        updateProfile(playerId, p -> p.fatigueEvents += 1);
        return penalty;
    }

    public void reduceFatigue(String playerId, double amount) {
        double clampedAmount = Math.max(0.0, amount);
        if (clampedAmount == 0.0) {
            return;
        }

        updateLuck(playerId, clampedAmount);

        updateProfile(playerId, p -> {
            if (p.fatigueEvents > 0) {
                int reduction = Math.max(1, (int) Math.round(clampedAmount * 10));
                p.fatigueEvents = Math.max(0, p.fatigueEvents - reduction);
            }
        });

        log.debug("Reduced fatigue for player {} by {}", playerId, clampedAmount);
    }

    public boolean isClutchMoment(String playerId) {
        double chance = 0.2 + getPlayerLuck(playerId) * 0.5;
        boolean clutch = rng.chance(chance);
        if (clutch) updateProfile(playerId, p -> p.clutchMoments += 1);
        return clutch;
    }

    public void updateLuck(String playerId, double delta) {
        playerLuck.merge(playerId, delta, Double::sum);
        updateProfile(playerId, p -> p.luckFactor = getPlayerLuck(playerId));
    }

    public double getPlayerLuck(String playerId) {
        return playerLuck.getOrDefault(playerId, 0.0);
    }

    public double getPlayerFatigueLevel(String playerId) {
        return Math.max(0.05, 1.0 - getPlayerLuck(playerId));
    }

    public void resetPlayerState(String playerId) {
        playerLuck.remove(playerId);
        playerProfiles.remove(playerId);
        playerKarma.remove(playerId);
        playerBrainprints.remove(playerId);
        universeSignatures.remove(playerId);
    }

    public void onGameEvent(String type, String playerId) {
        switch (type) {
            case "RAGE":
                rng.boostEntropy(UUID.randomUUID().toString().getBytes());
                updateLuck(playerId, -0.1);
                setKarma(playerId, getKarma(playerId) - 0.2);
                log.info("Rage quit detected for {}, entropy boosted", playerId);
                break;
            case "STREAK":
                rng.setSeed(System.nanoTime());
                updateLuck(playerId, 0.05);
                setKarma(playerId, getKarma(playerId) + 0.1);
                log.info("Winning streak for {}, RNG reseeded", playerId);
                break;
            case "RESET":
                rng.setSeed(System.currentTimeMillis());
                resetPlayerState(playerId);
                log.info("Server reset, RNG reseeded and {} state cleared", playerId);
                break;
            case "MOVE":
                updateLuck(playerId, rng.nextDoubleRange(-0.01, 0.01));
                break;
            default:
                log.debug("Unhandled chaos event '{}' for player {}", type, playerId);
                break;
        }
    }

    public LuckProfile getLuckProfile(String playerId) {
        return playerProfiles.computeIfAbsent(playerId, LuckProfile::new);
    }

    private void updateProfile(String playerId, java.util.function.Consumer<LuckProfile> updater) {
        LuckProfile profile = getLuckProfile(playerId);
        updater.accept(profile);
        profile.seedSnapshot = rng.getSeedSnapshot();
    }

    public String exportLuckProfileJson(String playerId) {
        LuckProfile profile = getLuckProfile(playerId);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(profile);
        } catch (Exception e) {
            log.error("Failed to export luck profile for {}: {}", playerId, e.getMessage());
            return "{\"error\": \"Export failed\"}";
        }
    }

    public void applyRealityShift(String playerId, double entropyLevel) {
        double shiftChance = Math.min(entropyLevel / 2048.0, 1.0);
        if (rng.chance(shiftChance)) {
            log.warn("REALITY SHIFT triggered for player {}!", playerId);
            gameService.alterGameRulesTemporarily(playerId);
            updateLuck(playerId, -0.05);
        }
    }

    public double getKarma(String playerId) {
        return playerKarma.getOrDefault(playerId, 0.0);
    }

    public void setKarma(String playerId, double karma) {
        playerKarma.put(playerId, karma);
    }

    public double calculateMoveEntropy(SudokuBoard board) {
        int emptyCells = (int) Arrays.stream(board.getBoardCopy())
            .flatMap(Arrays::stream)
            .filter(cell -> cell.getValue() == 0)
            .count();
        return 1.0 - (emptyCells / 81.0);
    }

    public void grantBlessing(String playerId, String type) {
        updateLuck(playerId, 0.1);
        log.info("Blessing {} granted to player {}", type, playerId);
    }

    public String getLastKnownBrainprint(String playerId) {
        return playerBrainprints.getOrDefault(playerId, "");
    }

    public void updateBrainprint(String playerId, String pattern) {
        playerBrainprints.put(playerId, pattern);
    }

    public String getUniverseSignature(String playerId) {
        return universeSignatures.getOrDefault(playerId, "default");
    }

    public void adjustRealityParameters(String playerId, String newSignature) {
        universeSignatures.put(playerId, newSignature);
        updateLuck(playerId, -0.02);
    }

    public void boostEntropy(byte[] seed) {
        rng.boostEntropy(seed);
    }
}
