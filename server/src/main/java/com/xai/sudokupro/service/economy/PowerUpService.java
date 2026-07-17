package com.xai.sudokupro.service.economy;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.AISolverService;
import com.xai.sudokupro.service.GameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gem-priced power-ups, stored in the (previously dormant) {@code User.powerUps}
 * map. Buy with gems, hold in inventory, spend on a live game:
 *
 * <ul>
 *   <li>{@code EXTRA_LIFE} — +1 life on your current game</li>
 *   <li>{@code REVEAL_CELL} — the solver fills one correct cell for you</li>
 *   <li>{@code FREEZE} — locks a duel opponent's input for 10 seconds</li>
 * </ul>
 */
@Service
public class PowerUpService {

    private static final Logger logger = LoggerFactory.getLogger(PowerUpService.class);
    private static final long FREEZE_MS = 10_000;

    /** type → gem price. LinkedHashMap so the catalog has a stable order. */
    public static final Map<String, Integer> CATALOG;
    static {
        Map<String, Integer> c = new LinkedHashMap<>();
        c.put("EXTRA_LIFE", 15);
        c.put("REVEAL_CELL", 20);
        c.put("FREEZE", 25);
        CATALOG = java.util.Collections.unmodifiableMap(c);
    }

    private final EconomyService economyService;
    private final UserRepository userRepository;
    private final GameService gameService;
    private final AISolverService solver;

    public PowerUpService(EconomyService economyService, UserRepository userRepository,
                          GameService gameService, AISolverService solver) {
        this.economyService = economyService;
        this.userRepository = userRepository;
        this.gameService = gameService;
        this.solver = solver;
    }

    /** Buys one unit of {@code type} with gems. Returns the new inventory count. */
    @Transactional
    public int buy(String playerId, String type) {
        Integer price = CATALOG.get(type);
        if (price == null) throw new IllegalArgumentException("Unknown power-up: " + type);
        User wallet = economyService.walletFor(playerId);
        if (wallet.getGems() < price) {
            throw new InsufficientGemsException(playerId, wallet.getGems(), price);
        }
        wallet.setGems(wallet.getGems() - price);
        Map<String, Integer> inventory = wallet.getPowerUps();
        int count = inventory.getOrDefault(type, 0) + 1;
        inventory.put(type, count);
        wallet.setPowerUps(inventory);
        userRepository.save(wallet);
        logger.info("{} bought {} for {} gems ({} held)", playerId, type, price, count);
        return count;
    }

    /**
     * Spends one held unit of {@code type}.
     *
     * @param gameId the caller's game (EXTRA_LIFE / REVEAL_CELL)
     * @param target the opposing player (FREEZE)
     */
    @Transactional
    public void use(String playerId, String type, String gameId, String target) {
        if (!CATALOG.containsKey(type)) throw new IllegalArgumentException("Unknown power-up: " + type);
        User wallet = economyService.walletFor(playerId);
        Map<String, Integer> inventory = wallet.getPowerUps();
        int held = inventory.getOrDefault(type, 0);
        if (held <= 0) throw new IllegalStateException("You do not hold a " + type);

        switch (type) {
            case "EXTRA_LIFE" -> {
                SudokuBoard board = requireOwnGame(gameId, playerId);
                board.setLives(board.getLives() + 1);
            }
            case "REVEAL_CELL" -> {
                SudokuBoard board = requireOwnGame(gameId, playerId);
                var move = solver.getNextLogicalMoveAsEnhancedMove(board);
                if (move == null) throw new IllegalStateException("No empty cell to reveal");
                board.applyExternalMove(move);
                // A reveal is assistance stronger than a hint: it must forfeit
                // the clean-solve bonus the same way hints do.
                board.incrementHintCount();
            }
            case "FREEZE" -> {
                if (target == null || target.isBlank() || target.equals(playerId)) {
                    throw new IllegalArgumentException("FREEZE needs an opposing player");
                }
                gameService.lockPlayerInput(target, FREEZE_MS);
            }
            default -> throw new IllegalArgumentException("Unknown power-up: " + type);
        }

        inventory.put(type, held - 1);
        wallet.setPowerUps(inventory);
        userRepository.save(wallet);
        logger.info("{} used {} (game={}, target={})", playerId, type, gameId, target);
    }

    public Map<String, Integer> inventory(String playerId) {
        return economyService.walletFor(playerId).getPowerUps();
    }

    private SudokuBoard requireOwnGame(String gameId, String playerId) {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("This power-up needs a gameId");
        }
        SudokuBoard board = gameService.getGame(gameId);
        if (!playerId.equals(board.getPlayerId())) {
            throw new SecurityException("Game " + gameId + " is not yours");
        }
        return board;
    }
}
