package com.xai.sudokupro.service.duel;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuGenerator;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.model.api.DuelInfo;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.GameEndListener;
import com.xai.sudokupro.service.GameService;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.service.duel.DuelStateStore.DuelRecord;
import com.xai.sudokupro.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Head-to-head duels: challenger and opponent race on IDENTICAL copies of one
 * puzzle; the first correct solve wins. Boards are ordinary games (created via
 * {@link SudokuBoard#playerCopy} and {@link GameService#adoptGame}), so moves,
 * hints, saves, and the WebSocket channel all work unchanged. The win is
 * detected through the {@link GameEndListener} hook and claimed atomically
 * across replicas in {@link DuelStateStore}.
 */
@Service
public class DuelService implements GameEndListener {

    private static final Logger logger = LoggerFactory.getLogger(DuelService.class);
    static final String DUEL_PREFIX = "duel-";

    private final GameService gameService;
    private final SudokuGenerator generator;
    private final DuelStateStore duels;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public DuelService(GameService gameService, SudokuGenerator generator,
                       DuelStateStore duels, UserRepository userRepository,
                       NotificationService notificationService) {
        this.gameService = gameService;
        this.generator = generator;
        this.duels = duels;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    static String duelGameId(String duelId, String playerId) {
        return DUEL_PREFIX + duelId + ":" + playerId;
    }

    /** Issues a challenge. Returns the duel id the opponent needs to accept. */
    public String challenge(String challenger, String opponent, int difficulty) {
        if (challenger == null || challenger.isBlank() || opponent == null || opponent.isBlank()) {
            throw new IllegalArgumentException("Both players must be named");
        }
        if (challenger.equals(opponent)) {
            throw new IllegalArgumentException("You cannot duel yourself");
        }
        int level = Math.max(1, Math.min(difficulty, 4));
        String duelId = UUID.randomUUID().toString().substring(0, 8);
        duels.save(new DuelRecord(duelId, challenger, opponent, "PENDING", null, level));
        notify(opponent, challenger + " challenges you to a duel! id=" + duelId);
        logger.info("Duel {} issued: {} -> {} (difficulty {})", duelId, challenger, opponent, level);
        return duelId;
    }

    /**
     * Opponent accepts: one puzzle is generated, both players get identical
     * copies registered as live games, and the race is on. Returns the
     * ACCEPTOR's board.
     */
    public SudokuBoard accept(String duelId, String playerId) {
        DuelRecord duel = requireDuel(duelId);
        if (!playerId.equals(duel.opponent())) {
            throw new SecurityException("Only " + duel.opponent() + " can accept duel " + duelId);
        }
        if (!"PENDING".equals(duel.status())) {
            throw new IllegalStateException("Duel " + duelId + " is " + duel.status() + ", not PENDING");
        }

        Constants.Difficulty difficulty = switch (duel.difficulty()) {
            case 1 -> Constants.Difficulty.EASY;
            case 3 -> Constants.Difficulty.HARD;
            case 4 -> Constants.Difficulty.EXTREME;
            default -> Constants.Difficulty.MEDIUM;
        };
        SudokuBoard template = generator.generate(difficulty, false, false, System.currentTimeMillis());
        template.setDifficulty(duel.difficulty());

        SudokuBoard challengerCopy = SudokuBoard.playerCopy(
            template, duelGameId(duelId, duel.challenger()), duel.challenger());
        SudokuBoard opponentCopy = SudokuBoard.playerCopy(
            template, duelGameId(duelId, duel.opponent()), duel.opponent());
        gameService.adoptGame(challengerCopy);
        gameService.adoptGame(opponentCopy);

        duels.save(duel.withStatus("ACTIVE"));
        notify(duel.challenger(), duel.opponent() + " accepted your duel! Race is on. id=" + duelId);
        notify(duel.opponent(), "Duel accepted — solve faster than " + duel.challenger() + "!");
        logger.info("Duel {} active: {} vs {}", duelId, duel.challenger(), duel.opponent());
        return opponentCopy;
    }

    /** Opponent declines a pending duel. */
    public void decline(String duelId, String playerId) {
        DuelRecord duel = requireDuel(duelId);
        if (!playerId.equals(duel.opponent())) {
            throw new SecurityException("Only " + duel.opponent() + " can decline duel " + duelId);
        }
        if (!"PENDING".equals(duel.status())) {
            throw new IllegalStateException("Duel " + duelId + " is " + duel.status() + ", not PENDING");
        }
        duels.save(duel.withStatus("DECLINED"));
        notify(duel.challenger(), duel.opponent() + " declined your duel.");
    }

    /** The caller's duels, newest data first isn't guaranteed — clients sort as needed. */
    public List<DuelInfo> duelsFor(String playerId) {
        List<DuelInfo> out = new ArrayList<>();
        for (DuelRecord d : duels.findForPlayer(playerId)) {
            String gameId = "ACTIVE".equals(d.status()) || "FINISHED".equals(d.status())
                ? duelGameId(d.duelId(), playerId) : null;
            out.add(new DuelInfo(d.duelId(), d.challenger(), d.opponent(), d.status(), d.winner(), gameId));
        }
        return out;
    }

    /** First correct solve wins — called for every finished game via the listener hook. */
    @Override
    @Transactional
    public void onGameEnded(SudokuBoard board, String playerId) {
        if (board == null || playerId == null || !board.isSolved()) return;
        String gameId = board.getGameId();
        if (!gameId.startsWith(DUEL_PREFIX) || !gameId.equals(duelGameId(duelIdOf(gameId), playerId))) return;

        String duelId = duelIdOf(gameId);
        DuelRecord duel = duels.find(duelId);
        if (duel == null || !"ACTIVE".equals(duel.status())) return;

        if (!duels.claimWin(duelId, playerId)) return; // someone was faster

        String loser = playerId.equals(duel.challenger()) ? duel.opponent() : duel.challenger();
        duels.save(duel.withWinner(playerId));
        recordResult(playerId, loser);
        notify(playerId, "You WON the duel against " + loser + "!");
        notify(loser, playerId + " solved first — duel lost. Rematch?");
        logger.info("Duel {} won by {} (loser {})", duelId, playerId, loser);
    }

    private static String duelIdOf(String gameId) {
        // duel-<id>:<player>
        int colon = gameId.indexOf(':');
        return colon < 0 ? "" : gameId.substring(DUEL_PREFIX.length(), colon);
    }

    /**
     * Rematch: either party of a FINISHED duel challenges the other again at
     * the same difficulty. Returns the new duel id (normal accept flow follows).
     */
    public String rematch(String duelId, String playerId) {
        DuelRecord old = requireDuel(duelId);
        if (!playerId.equals(old.challenger()) && !playerId.equals(old.opponent())) {
            throw new SecurityException("You were not part of duel " + duelId);
        }
        if (!"FINISHED".equals(old.status())) {
            throw new IllegalStateException("Duel " + duelId + " is " + old.status() + ", not FINISHED");
        }
        String other = playerId.equals(old.challenger()) ? old.opponent() : old.challenger();
        return challenge(playerId, other, old.difficulty());
    }

    /** Duel ladder: players with a duel history, best rating first. */
    public List<User> ladder(int limit) {
        return userRepository.findDuelLadder(
            org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }

    private static final int ELO_K = 32;

    private void recordResult(String winner, String loser) {
        try {
            User w = walletFor(winner);
            User l = walletFor(loser);
            // ELO: expected score from the rating gap, K=32.
            double expectedWin = 1.0 / (1.0 + Math.pow(10, (l.getDuelRating() - w.getDuelRating()) / 400.0));
            int delta = (int) Math.round(ELO_K * (1.0 - expectedWin));
            w.setDuelRating(w.getDuelRating() + delta);
            l.setDuelRating(l.getDuelRating() - delta);
            w.setDuelWins(w.getDuelWins() + 1);
            l.setDuelLosses(l.getDuelLosses() + 1);
            userRepository.save(w);
            userRepository.save(l);
            logger.info("Duel ratings: {} +{} → {}, {} -{} → {}",
                winner, delta, w.getDuelRating(), loser, delta, l.getDuelRating());
        } catch (Exception e) {
            logger.warn("Failed to record duel result {} beats {}: {}", winner, loser, e.getMessage());
        }
    }

    private User walletFor(String playerId) {
        return userRepository.findByUsername(playerId)
            .orElseGet(() -> userRepository.save(new User(null, playerId)));
    }

    private DuelRecord requireDuel(String duelId) {
        DuelRecord duel = duels.find(duelId);
        if (duel == null) throw new IllegalArgumentException("Duel not found: " + duelId);
        return duel;
    }

    private void notify(String playerId, String message) {
        try {
            notificationService.sendTypedNotification(playerId, "DUEL", message);
        } catch (Exception e) {
            logger.debug("Duel notification to {} failed: {}", playerId, e.getMessage());
        }
    }
}
