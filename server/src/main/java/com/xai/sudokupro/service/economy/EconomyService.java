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

    /**
     * The player's wallet, provisioned with the signing bonus on first touch.
     *
     * <p>This is a check-then-insert with nothing serializing it, and the callers are
     * genuinely concurrent: two hint requests on two different games take two DIFFERENT
     * per-game locks, so both can miss the lookup and both insert. Before Flyway V9 there
     * was no unique index to stop the second one, and the resulting duplicate rows made
     * every later {@code findByUsername} — a single-result query — throw
     * {@code IncorrectResultSizeDataAccessException} permanently, login included.
     *
     * <p>V9 now rejects the losing insert, which turns silent corruption into a loud
     * {@code DataIntegrityViolationException}. That is strictly better but still a 500 for
     * a request that should simply have succeeded, so the loser re-reads the winner's row.
     * The retry is bounded and the second lookup cannot miss: the constraint firing is
     * proof the row is there.
     */
    @Transactional
    public User walletFor(String playerId) {
        var existing = userRepository.findByUsername(playerId);
        if (existing.isPresent()) return existing.get();
        try {
            User fresh = new User(null, playerId);
            fresh.setGems(startingGems);
            User saved = userRepository.saveAndFlush(fresh);
            logger.info("Provisioned wallet for {} with {} starting gems", playerId, startingGems);
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Lost the provisioning race; the winner's row is committed by definition.
            return userRepository.findByUsername(playerId).orElseThrow(() ->
                new IllegalStateException("Wallet for " + playerId + " vanished after a unique-constraint conflict", e));
        }
    }

    /**
     * Deducts the hint cost from the player's wallet.
     *
     * @return the remaining balance
     * @throws InsufficientGemsException when the wallet can't cover it
     */
    @Transactional
    public int chargeForHint(String playerId) {
        User wallet = walletFor(playerId);   // provisions the row on first touch
        // Atomic compare-and-decrement. The previous read-modify-write lost updates:
        // hint charging is serialized only by the PER-GAME lock, so concurrent requests
        // against DIFFERENT games all read the same balance and wrote back the same
        // decremented value. Measured live: six concurrent hints across six games cost a
        // total of 15 gems instead of 30 — six hints for the price of three, and the
        // balance could be driven to 0 while still serving every request.
        if (userRepository.deductGemsIfAffordable(playerId, hintCost) == 0) {
            int balance = userRepository.findByUsername(playerId)
                .map(User::getGems).orElse(wallet.getGems());
            throw new InsufficientGemsException(playerId, balance, hintCost);
        }
        int remaining = userRepository.findByUsername(playerId)
            .map(User::getGems).orElse(Math.max(0, wallet.getGems() - hintCost));
        logger.debug("Charged {} gems to {} for a hint — {} left", hintCost, playerId, remaining);
        return remaining;
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
            walletFor(playerId);   // provision on first solve

            // Credit atomically in the database rather than read-modify-write. The old
            // form — setGems(getGems() + earned) then save(wallet) — flushes a full-row
            // UPDATE built from a snapshot read earlier in the transaction, which silently
            // reverts any concurrent charge that committed in between. deductGemsIfAffordable
            // was introduced precisely because read-modify-write loses gem updates; the
            // reward side was still doing it. Measured shape: 15 gems, a payout computing
            // 45 from the stale read, a hint charge taking the balance to 10, then the
            // payout writing 45 — the hint came out free.
            int rows = userRepository.creditGemsAndXp(playerId, earned, earned);
            if (rows == 0) {
                logger.warn("Solve credit for {} matched no wallet row", playerId);
                return;
            }
            // level is a pure function of xp (1 + xp/100), so recomputing it from a fresh
            // read is safe and self-healing: a concurrent credit can only make it briefly
            // stale, and the next credit corrects it.
            userRepository.findByUsername(playerId).ifPresent(fresh -> {
                int derived = 1 + (fresh.getXp() / 100);
                if (derived > fresh.getLevel()) userRepository.updateLevel(playerId, derived);
            });
            logger.info("Player {} earned {} gems for solving {}",
                playerId, earned, board.getGameId());
        } catch (Exception e) {
            logger.warn("Failed to award solve gems to {}: {}", playerId, e.getMessage());
        }
    }
}
