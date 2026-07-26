package com.xai.sudokupro.client;

import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuCell;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the defect class "the same value formatted in two places, differently".
 *
 * <p>Two shipped bugs of exactly that shape:
 * <ul>
 *   <li>The client sent a fully rendered chat line — {@code "[12:04:31] ann: hi"} —
 *       as the chat <em>payload</em>. The server relays chat with the speaker in the
 *       envelope's {@code from}, and the receiving client prepends it, so every peer
 *       read {@code "ann: [12:04:31] ann: hi"}.</li>
 *   <li>The move-history writer and the "Hints" filter each spelled the hint entry
 *       out as a literal. Any divergence leaves the filter matching nothing, which
 *       is precisely what a previous pass had to repair.</li>
 * </ul>
 */
class ChatAndMoveLabelTest {

    @Test
    void rendersOneTimestampOneSpeakerAndTheMessage() {
        String line = ChatLine.render(LocalTime.of(12, 4, 31), "ann", "hi");
        assertEquals("[12:04:31] ann: hi", line);
    }

    /**
     * The double-label reproduction: what a peer sees is the raw payload passed
     * through render() once. If the payload already carried a rendering, the name
     * would appear twice in this string.
     */
    @Test
    void aRenderedLineContainsTheSpeakerExactlyOnce() {
        String line = ChatLine.render(LocalTime.of(9, 0, 0), "ann", "gg");
        assertEquals(1, line.split("ann", -1).length - 1,
            "The speaker is added at display time only — the wire carries the message");
    }

    @Test
    void timeIsZeroPaddedToAFixedWidth() {
        assertEquals("[09:07:05] bob: x", ChatLine.render(LocalTime.of(9, 7, 5), "bob", "x"));
    }

    @Test
    void hintMovesUseTheSameLabelTheFilterMatches() {
        EnhancedMove hint = new EnhancedMove(3, 4, 0, 7, SudokuCell.MoveSource.HINT);
        String label = MoveLabels.describe(hint);

        assertEquals(MoveLabels.HINT, label);
        assertTrue(MoveLabels.isHint(label),
            "The writer and the Hints filter must agree, or the filter shows nothing");
    }

    @Test
    void coordinatesAreOneBasedForThePlayer() {
        EnhancedMove move = new EnhancedMove(0, 0, 0, 5, SudokuCell.MoveSource.PLAYER);
        assertEquals("(1,1)=5", MoveLabels.describe(move));
    }

    @Test
    void clearingACellReadsAsClearedRatherThanEqualsZero() {
        EnhancedMove clear = new EnhancedMove(8, 8, 4, 0, SudokuCell.MoveSource.PLAYER);
        assertEquals("(9,9) cleared", MoveLabels.describe(clear));
    }

    @Test
    void anOrdinaryMoveIsNotTreatedAsAHint() {
        assertFalse(MoveLabels.isHint(
            MoveLabels.describe(new EnhancedMove(1, 1, 0, 9, SudokuCell.MoveSource.PLAYER))));
    }
}
