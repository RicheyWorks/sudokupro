package com.xai.sudokupro.service.economy;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
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
    private final com.xai.sudokupro.service.duel.DuelStateStore duels;

    // The solver dependency is gone: the reveal now runs inside GameService, which
    // already holds one. Keeping an injected collaborator this class no longer calls
    // would imply a relationship that isn't there.
    public PowerUpService(EconomyService economyService, UserRepository userRepository,
                          GameService gameService,
                          com.xai.sudokupro.service.duel.DuelStateStore duels) {
        this.economyService = economyService;
        this.userRepository = userRepository;
        this.gameService = gameService;
        this.duels = duels;
    }

    /** Buys one unit of {@code type} with gems. Returns the new inventory count. */
    @Transactional
    public int buy(String playerId, String type) {
        Integer price = CATALOG.get(type);
        if (price == null) throw new IllegalArgumentException("Unknown power-up: " + type);
        User wallet = economyService.walletFor(playerId);
        // Charge atomically and conditionally, the same way a hint is charged. This read
        // the balance, subtracted in Java and saved the whole row, so two concurrent buys
        // both saw the same starting balance and the loser's charge vanished — and a
        // concurrent hint charge was reverted wholesale by the full-row write.
        if (userRepository.deductGemsIfAffordable(playerId, price) == 0) {
            throw new InsufficientGemsException(playerId, wallet.getGems(), price);
        }
        User fresh = economyService.walletFor(playerId);
        Map<String, Integer> inventory = fresh.getPowerUps();
        int count = inventory.getOrDefault(type, 0) + 1;
        inventory.put(type, count);
        fresh.setPowerUps(inventory);
        userRepository.save(fresh);
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

        // Board effects run through GameService, which is the single place that holds
        // the game lock, broadcasts, and writes through to Redis and the database. Doing
        // them here meant a paid change lived in one pod's cache and nowhere else — see
        // GameService.revealCell for the full shape. Both throw before the inventory
        // decrement below, so a reveal with no derivable cell still costs nothing.
        switch (type) {
            case "EXTRA_LIFE" -> {
                requireGameId(gameId);
                gameService.grantExtraLife(gameId, playerId);
            }
            case "REVEAL_CELL" -> {
                requireGameId(gameId);
                gameService.revealCell(gameId, playerId);
            }
            case "FREEZE" -> {
                if (target == null || target.isBlank() || target.equals(playerId)) {
                    throw new IllegalArgumentException("FREEZE needs an opposing player");
                }
                // This checked only "not blank, not me", so it froze ANY player on the
                // platform — pick a name off the public leaderboard and lock their input
                // for ten seconds. The class javadoc says FREEZE "locks a duel opponent's
                // input"; its two siblings both go through requireOwnGame, and this had
                // nothing. It is also silent to the victim: GameService.applyMove drops a
                // locked player's move with a log line and no error, while the WebSocket
                // layer has already broadcast the move envelope, so their client shows a
                // move the authoritative board never recorded. Verified live before the
                // fix against an unrelated account.
                if (!duels.hasActiveDuelBetween(playerId, target)) {
                    throw new SecurityException("FREEZE can only target an opponent in an active duel");
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

    /**
     * The gameId precondition for board-effect power-ups. Ownership itself is enforced
     * by the GameService operation (it throws SecurityException before mutating), so it
     * is checked in one place rather than two that could drift apart.
     */
    private void requireGameId(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("This power-up needs a gameId");
        }
    }
}
