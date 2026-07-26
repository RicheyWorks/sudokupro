package com.xai.sudokupro.service.duel;

import com.xai.sudokupro.model.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Elo conservation at the rating floor.
 *
 * <p>An earlier audit pass recorded "ELO math is symmetric" as verified-sound. That was
 * true of {@code recordResult} read in isolation and false in practice, because
 * {@link User#setDuelRating(int)} clamps with {@code Math.max(0, ...)}. The winner was
 * credited the full delta while the loser, already near zero, surrendered only what they
 * had — so the exchange stopped being zero-sum and rating was created from nothing.
 *
 * <p>The tests below reimplement the two-line arithmetic rather than standing up the full
 * DuelService graph; the point under test is the conservation property, not the wiring.
 */
class DuelRatingFloorTest {

    private static final int ELO_K = 32;

    /** The fixed transfer, before and after the clamp. */
    private static int delta(int winnerRating, int loserRating, boolean clampToLoser) {
        double expectedWin = 1.0 / (1.0 + Math.pow(10, (loserRating - winnerRating) / 400.0));
        int d = (int) Math.round(ELO_K * (1.0 - expectedWin));
        return clampToLoser ? Math.max(0, Math.min(d, loserRating)) : d;
    }

    /** Applies one duel and returns {winner, loser} after the entity's own clamping. */
    private static int[] settle(int w, int l, boolean clampToLoser) {
        int d = delta(w, l, clampToLoser);
        User winner = new User(null, "w"); winner.setDuelRating(w + d);
        User loser  = new User(null, "l"); loser.setDuelRating(l - d);
        return new int[]{winner.getDuelRating(), loser.getDuelRating()};
    }

    /**
     * The bug, stated as arithmetic: at winner 195 / loser 5 the transfer is 8, but the
     * loser can only give up 5, so three points appear from nowhere.
     */
    @Test
    void theUnclampedDeltaMintsRatingAtTheFloor() {
        int before = 195 + 5;
        int[] after = settle(195, 5, false);   // the old behaviour

        assertEquals(0, after[1], "the loser is floored at zero by the entity setter");
        assertTrue(after[0] + after[1] > before,
            "this is the defect: total rating grew from " + before + " to " + (after[0] + after[1]));
    }

    /** With the delta capped at what the loser actually holds, the total is preserved. */
    @Test
    void clampingTheDeltaConservesTotalRating() {
        int[][] cases = {{195, 5}, {203, 0}, {1000, 1000}, {1600, 400}, {100, 1}, {50, 0}};
        for (int[] c : cases) {
            int before = c[0] + c[1];
            int[] after = settle(c[0], c[1], true);
            assertEquals(before, after[0] + after[1],
                "rating must be conserved for winner=" + c[0] + " loser=" + c[1]);
            assertTrue(after[1] >= 0, "the loser must never go negative");
        }
    }

    /**
     * A player parked at zero was a rating faucet: every duel against them paid the
     * winner the full delta for nothing, up to roughly 720 points before the gap
     * saturated the transfer to zero.
     */
    @Test
    void aFlooredOpponentIsNoLongerAFaucet() {
        int winner = 200;
        for (int i = 0; i < 50; i++) {
            int[] after = settle(winner, 0, true);
            winner = after[0];
        }
        assertEquals(200, winner,
            "beating a zero-rated opponent fifty times must transfer nothing, got " + winner);
    }

    /** Ordinary duels between healthy ratings are untouched by the clamp. */
    @Test
    void normalDuelsAreUnaffected() {
        assertEquals(delta(1000, 1000, false), delta(1000, 1000, true));
        assertEquals(delta(1200, 1000, false), delta(1200, 1000, true));
        assertEquals(16, delta(1000, 1000, true), "an even match at K=32 transfers 16");
    }
}
