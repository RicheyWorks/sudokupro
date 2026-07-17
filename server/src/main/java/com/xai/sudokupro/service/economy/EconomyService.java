package com.xai.sudokupro.service.economy;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.GameEndListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hint economy: hints cost gems, solving earns them. Wallets are the
 * existing {@code users} rows (gems/xp/level fields have been there all along —
 * this makes them real), auto-provisioned on first touch now that users.id is
 * database-generated (V4).
 *
 * <p>Earning: {@code difficulty * 10} gems per solved game, {@code +5} bonus
 * for a clean solve (no hints), plus XP. Detected via the {@link GameEndListener}
 * hook. Spending: {@link #chargeForHint} inside GameService.getHint. Free-hint
 * grace: players with no gems yet (fresh wallet) can still afford their first
 * hints because every wallet starts with a signing bonus.
 */
@Service
public class EconomyService implements GameEndListener {

    private static final Logger logger = LoggerFactory.getLogger(EconomyService.class);

    private final UserRepository userRepository;
    private final int hintCost;
    private final int startingGems;
    private final int cleanSolveBonus;

    public EconomyService(UserRepository userRepository,
                          @Value("${sudokupro.economy.hint-cost:5}") int hintCost,
                          @Value("${sudokupro.economy.starting-gems:15}") int startingGems,
                          @Value("${sudokupro.economy.clean-solve-bonus:5}") int cleanSolveBonus) {
        this.userRepository = userRepository;
        this.hintCost = hintCost;
        this.startingGems = startingGems;
        this.cleanSolveBonus = cleanSolveBonus;
    }

    public int hintCost() {
        return hintCost;
    }

    /** The player's wallet, provisioned with the signing bonus on first touch. */
    @Transactional
    public User walletFor(String playerId) {
        return userRepository.findByUsername(playerId).orElseGet(() -> {
            User fresh = new User(null, playerId);
            fresh.setGems(startingGems);
            User saved = userRepository.save(fresh);
            logger.info("Provisioned wallet for {} with {} starting gems", playerId, startingGems);
            return saved;
        });
    }

    /**
     * Deducts the hint cost from the player's wallet.
     *
     * @return the remaining balance
     * @throws InsufficientGemsException when the wallet can't cover it
     */
    @Transactional
    public int chargeForHint(String playerId) {
        User wallet = walletFor(playerId);
        if (wallet.getGems() < hintCost) {
            throw new InsufficientGemsException(playerId, wallet.getGems(), hintCost);
        }
        wallet.setGems(wallet.getGems() - hintCost);
        userRepository.save(wallet);
        logger.debug("Charged {} gems to {} for a hint — {} left", hintCost, playerId, wallet.getGems());
        return wallet.getGems();
    }

    /** Solving pays: difficulty-scaled gems, clean-solve bonus, and XP. */
    @Override
    @Transactional
    public void onGameEnded(SudokuBoard board, String playerId) {
        if (board == null || playerId == null || !board.isSolved()) return;
        // The daily/duel template pseudo-player never earns.
        if (playerId.startsWith("__")) return;
        // Rewards go to the board's OWNER only — never whoever happened to end it.
        if (!playerId.equals(board.getPlayerId())) return;
        try {
            int earned = Math.max(1, board.getDifficulty()) * 10
                + (board.getHintCount() == 0 ? cleanSolveBonus : 0);
            User wallet = walletFor(playerId);
            wallet.setGems(wallet.getGems() + earned);
            wallet.addXp(earned);
            userRepository.save(wallet);
            logger.info("Player {} earned {} gems for solving {} (balance {})",
                playerId, earned, board.getGameId(), wallet.getGems());
        } catch (Exception e) {
            logger.warn("Failed to award solve gems to {}: {}", playerId, e.getMessage());
        }
    }
}
