package com.xai.sudokupro.service.duel;

import com.xai.sudokupro.model.GameEvent;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuGenerator;
import com.xai.sudokupro.model.User;
import com.xai.sudokupro.model.api.DuelInfo;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.AnalyticsService;
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
    /** Cap on simultaneous unanswered challenges from one player to another. */
    static final int MAX_OUTSTANDING_CHALLENGES = 3;
    static final String DUEL_PREFIX = "duel-";

    private final GameService gameService;
    private final SudokuGenerator generator;
    private final DuelStateStore duels;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AnalyticsService analyticsService;

    public DuelService(GameService gameService, SudokuGenerator generator,
                       DuelStateStore duels, UserRepository userRepository,
                       NotificationService notificationService,
                       AnalyticsService analyticsService) {
        this.gameService = gameService;
        this.generator = generator;
        this.duels = duels;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.analyticsService = analyticsService;
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
        // The opponent must exist — the same guard FriendService.request already carries.
        // Without it, challenging a nonexistent name wrote a duel record plus a
        // per-player Redis set member (24h TTL) for an account that can never accept,
        // and repeating the call against a REAL player grew their duel set without bound.
        // Their GET /api/duel does one Redis round trip per member, so their own duel
        // list degraded into an N-round-trip request that only they paid for.
        if (userRepository.findByUsername(opponent).isEmpty()) {
            throw new IllegalArgumentException("No such player: " + opponent);
        }
        int outstanding = 0;
        for (DuelRecord d : duels.findForPlayer(opponent)) {
            if ("PENDING".equals(d.status()) && challenger.equals(d.challenger())) outstanding++;
        }
        if (outstanding >= MAX_OUTSTANDING_CHALLENGES) {
            throw new IllegalStateException(
                "You already have " + outstanding + " pending challenges to " + opponent);
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

        // HARD is the generator's ceiling: EXTREME(70 removals) would leave 11
        // clues, below the 17-clue minimum for a unique solution — generation
        // always failed there. Difficulty 4 therefore also maps to HARD.
        Constants.Difficulty difficulty = switch (duel.difficulty()) {
            case 1 -> Constants.Difficulty.EASY;
            case 3, 4 -> Constants.Difficulty.HARD;
            default -> Constants.Difficulty.MEDIUM;
        };
        SudokuBoard template;
        try {
            template = generator.generate(difficulty, false, false, System.currentTimeMillis());
        } catch (RuntimeException e) {
            // HARD can exhaust the generator's retry budget (uniqueness gets
            // scarce near the 17-clue floor) — degrade rather than fail the duel.
            logger.warn("Duel generation at {} failed ({}); falling back to MEDIUM", difficulty, e.getMessage());
            template = generator.generate(Constants.Difficulty.MEDIUM, false, false, System.currentTimeMillis());
        }
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
            // Clamp the DELTA, not the result. User.setDuelRating does Math.max(0, ...),
            // so once the loser's rating fell below delta the winner still gained the full
            // amount while the loser surrendered less — the pair stopped being zero-sum and
            // rating was created from nothing. Worked example at K=32: winner 195 vs loser
            // 5 gives delta 8, so the loser drops 5 (clamped) while the winner gains 8;
            // every subsequent duel against the floored account is +8/-0, pure minting,
            // until the gap saturates around 720 points. Capping the transfer at what the
            // loser actually has keeps the exchange symmetric at the floor.
            delta = Math.max(0, Math.min(delta, l.getDuelRating()));
            w.setDuelRating(w.getDuelRating() + delta);
            l.setDuelRating(l.getDuelRating() - delta);
            w.setDuelWins(w.getDuelWins() + 1);
            l.setDuelLosses(l.getDuelLosses() + 1);
            userRepository.save(w);
            userRepository.save(l);
            // Feed the outcome to analytics. recordEvent has had DUEL_WIN / DUEL_LOSS
            // branches since it was written, but nothing ever emitted them (the enum
            // constants did not exist), so AnalyticsService.getPlayerWinRates() and
            // getDuelWins() were permanently empty — the duel.win.rate.average gauge
            // read 0.0 forever and the anti-cheat win-count cross-check compared
            // against a map that could never match. This is the only writer.
            analyticsService.recordEvent(new GameEvent(GameEvent.EventType.DUEL_WIN, winner,
                java.util.Map.of("opponent", loser)));
            analyticsService.recordEvent(new GameEvent(GameEvent.EventType.DUEL_LOSS, loser,
                java.util.Map.of("opponent", winner)));
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
